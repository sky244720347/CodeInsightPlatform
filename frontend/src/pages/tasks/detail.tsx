import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Col, Descriptions, Modal, Progress, Row, Space, Steps, Statistic, Tag, Timeline, Typography, message } from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  CopyOutlined,
  DownloadOutlined,
  EditOutlined,
  FileSearchOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SwapOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { getTask, getTaskExecutionLog, getTaskLogSummary, retryTask, retryBaselineInherit, startTask, terminateTask } from '../../api/task';
import { getSystem } from '../../api/system';
import { listVersions, type KnowledgeVersion } from '../../api/knowledge';
import type { PipelineStageStat, System, Task, TaskLogSummary } from '../../types';
import IncrementalImpactCard from './components/IncrementalImpactCard';

/** 构造携带当前任务上下文（systemId + taskId）的复核页跳转链接 */
const buildDraftsHref = (task: Task) => `/drafts/${task.id}`;


const { Text, Title } = Typography;

// 包含人工复核断点与发布段：处于这些状态时轮询详情
const runningStatuses = [
  'PENDING',
  'RESUME_QUEUED',
  'PULLING_CODE',
  'PARSING_CODE',
  'ENTRYPOINT_REVIEW',
  'AI_ANALYZING',
  'MODULE_HIERARCHY',
  'MODULE_HIERARCHY_REVIEW',
  'BASELINE_DOC_INHERIT',
  'GENERATING_DOC',
  'CONFIRMED',
  'PUSHING',
];

/**
 * 执行流程 Steps 索引（按「含基线复制」的完整链路编号；全量任务展示时会去掉基线步并前移）。
 * 排队(0) → 拉取(1) → 解析(2) → 入口复核(3) → AI(4) → 层级复核(5)
 * → 基线复制(6) → 生成文档(7) → 知识复核(8) → 建版(9) → NAS推送(10) → 完成(11)
 */
const FLOW_STEP_ENTRY_REVIEW = 3;
const FLOW_STEP_AI = 4;
const FLOW_STEP_HIERARCHY_REVIEW = 5;
const FLOW_STEP_BASELINE = 6;
const FLOW_STEP_DOC_GEN = 7;
const FLOW_STEP_KNOWLEDGE_REVIEW = 8;
const FLOW_STEP_CREATE_VERSION = 9;
const FLOW_STEP_PUSH = 10;
const FLOW_STEP_DONE = 11;

/** 与后端 TaskResumeConstants 对齐 */
const RESUME_AFTER_ENTRYPOINT = 'AFTER_ENTRYPOINT';
const RESUME_AFTER_HIERARCHY = 'AFTER_HIERARCHY';

const statusMeta: Record<string, { color: string; label: string; step: number }> = {
  DRAFT: { color: 'default', label: '草稿', step: -1 },
  PENDING: { color: 'blue', label: '排队中', step: 0 },
  /** step 由 resolveFlowStep 按 resumeFrom 动态计算，此处仅作兜底 */
  RESUME_QUEUED: { color: 'blue', label: '排队续跑', step: FLOW_STEP_AI },
  PULLING_CODE: { color: 'blue', label: '拉取代码', step: 1 },
  PARSING_CODE: { color: 'cyan', label: '解析代码', step: 2 },
  ENTRYPOINT_REVIEW: { color: 'cyan', label: '入口复核', step: FLOW_STEP_ENTRY_REVIEW },
  AI_ANALYZING: { color: 'orange', label: 'AI 分析中', step: FLOW_STEP_AI },
  MODULE_HIERARCHY: { color: 'gold', label: '模块层级提炼', step: FLOW_STEP_AI },
  MODULE_HIERARCHY_REVIEW: { color: 'geekblue', label: '模块层级复核', step: FLOW_STEP_HIERARCHY_REVIEW },
  BASELINE_DOC_INHERIT: { color: 'cyan', label: '基线文档继承', step: FLOW_STEP_BASELINE },
  GENERATING_DOC: { color: 'gold', label: '生成文档', step: FLOW_STEP_DOC_GEN },
  PENDING_REVIEW: { color: 'magenta', label: '知识复核', step: FLOW_STEP_KNOWLEDGE_REVIEW },
  REVIEWING: { color: 'geekblue', label: '知识复核中', step: FLOW_STEP_KNOWLEDGE_REVIEW },
  CONFIRMED: { color: 'green', label: '已建版', step: FLOW_STEP_CREATE_VERSION },
  PUSHING: { color: 'purple', label: '推送中', step: FLOW_STEP_PUSH },
  PUSHED: { color: 'green', label: '已完成', step: FLOW_STEP_DONE },
  /** @deprecated 历史任务可能卡在此状态 */
  SPLITTING_TASK: { color: 'purple', label: '任务切片（已废弃）', step: 2 },
  FAILED: { color: 'red', label: '失败', step: -1 },
  CANCELLED: { color: 'default', label: '已取消', step: -1 },
};

/** RESUME_QUEUED：进度停在下一执行步，副标题「排队中」 */
function resolveResumeQueuedFlowStep(task: Task): number {
  if (task.resumeFrom === RESUME_AFTER_HIERARCHY) {
    return task.type === 'INCREMENTAL' ? FLOW_STEP_BASELINE : FLOW_STEP_DOC_GEN;
  }
  // AFTER_ENTRYPOINT 或未知：落在 AI 分析
  return FLOW_STEP_AI;
}

function resolveFlowStep(task: Task): number {
  if (task.status === 'RESUME_QUEUED') {
    return resolveResumeQueuedFlowStep(task);
  }
  return statusMeta[task.status]?.step ?? -1;
}

/**
 * 任务执行详情监控组件 (TaskDetail)
 * 展示任务的静态配置（负责人、代码库 ID、模型、耗时及日志存储路径）、
 * 串联 Steps 指引任务当前流转到哪一步骤，并在底部提供流式模拟终端日志呈现和 Token 预估分析栏。
 */
const TaskDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const taskId = Number(id);

  // 任务实体数据及所属系统实体
  const [task, setTask] = useState<Task | null>(null);
  const [system, setSystem] = useState<System | null>(null);
  
  // 数据加载 loading 与操作按钮的 actionLoading 状态
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);

  // 结构化摘要 / 弹窗日志
  const [summary, setSummary] = useState<TaskLogSummary | null>(null);
  const [logModalOpen, setLogModalOpen] = useState(false);
  const [execLogContent, setExecLogContent] = useState('');
  const [logLoading, setLogLoading] = useState(false);
  const prevTaskStatusRef = useRef<string | null>(null);
  /** 本任务关联的知识版本（建版/推送步展示 versionNum） */
  const [taskVersion, setTaskVersion] = useState<KnowledgeVersion | null>(null);

  const clearExecutionLogs = useCallback(() => {
    setSummary(null);
    setExecLogContent('');
  }, []);

  const loadSummary = useCallback(async () => {
    if (!taskId) return;
    try {
      const data = await getTaskLogSummary(taskId);
      setSummary(data);
    } catch {
      // 静默失败：保留上一次成功拉到的摘要，避免运行中一闪而过
    }
  }, [taskId]);

  // 与 raw 卡片日志同步轮询，避免额外定时器漂移
  useEffect(() => {
    loadSummary();
  }, [loadSummary]);

  useEffect(() => {
    if (!task || !runningStatuses.includes(task.status)) return;
    const timer = window.setInterval(loadSummary, 2500);
    return () => window.clearInterval(timer);
  }, [loadSummary, task]);

  const openExecLog = async () => {
    if (!taskId) return;
    setLogModalOpen(true);
    setLogLoading(true);
    try {
      const content = await getTaskExecutionLog(taskId);
      setExecLogContent(content || '(暂无日志)');
    } catch {
      setExecLogContent('(加载失败)');
    } finally {
      setLogLoading(false);
    }
  };
  const copyExecLog = async () => {
    if (!execLogContent) return;
    try {
      await navigator.clipboard.writeText(execLogContent);
      message.success('日志已复制到剪贴板');
    } catch {
      message.error('复制失败');
    }
  };

  const downloadExecLog = () => {
    if (!taskId) return;
    const blob = new Blob([execLogContent || ''], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `task_${taskId}_pipeline.log`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const fetchTaskDetails = useCallback(
    async (showLoading = true) => {
      if (!taskId) {
        return;
      }
      if (showLoading) {
        setLoading(true);
      }
      try {
        const taskData = await getTask(taskId);
        setTask(taskData);
        const systemData = await getSystem(taskData.systemId);
        setSystem(systemData);
      } // 忽略捕获以依靠全局 Axios 异常拦截
      finally {
        if (showLoading) {
          setLoading(false);
        }
      }
    },
    [taskId],
  );

  useEffect(() => {
    fetchTaskDetails();
  }, [fetchTaskDetails]);

  // 建版及之后：拉取本任务知识版本号，挂在「建版」步描述上
  useEffect(() => {
    if (!task?.repositoryId || !taskId) {
      setTaskVersion(null);
      return;
    }
    if (!['CONFIRMED', 'PUSHING', 'PUSHED'].includes(task.status)) {
      setTaskVersion(null);
      return;
    }
    let cancelled = false;
    listVersions({ current: 1, size: 50, repositoryId: task.repositoryId, systemId: task.systemId })
      .then((page) => {
        if (cancelled) return;
        const match = (page?.records ?? []).find((v) => v.taskId === taskId);
        setTaskVersion(match ?? null);
      })
      .catch(() => {
        if (!cancelled) setTaskVersion(null);
      });
    return () => {
      cancelled = true;
    };
  }, [task?.repositoryId, task?.systemId, task?.status, taskId]);

  /**
   * 启动任务轮询机制
   * 若任务处于运行态，则每 2.5 秒刷新一次最新进度与状态，在组件卸载或任务结束时自动销毁定时器。
   */
  useEffect(() => {
    if (!task || !runningStatuses.includes(task.status)) {
      return;
    }
    const timer = window.setInterval(() => fetchTaskDetails(false), 2500);
    return () => window.clearInterval(timer);
  }, [fetchTaskDetails, task]);

  // 包装各类流程操作事件（启动/重跑/终止），并在操作完成后自动重载数据
  const runAction = async (action: () => Promise<void>, success: string, resetLogs = false) => {
    if (!task) {
      return;
    }
    setActionLoading(true);
    try {
      if (resetLogs) {
        clearExecutionLogs();
      }
      await action();
      message.success(success);
      await fetchTaskDetails();
      await Promise.all([loadSummary()]);
    } finally {
      setActionLoading(false);
    }
  };

  /** 任务从失败/取消重新进入运行态时，丢弃上一轮日志缓存 */
  useEffect(() => {
    if (!task) return;
    const prev = prevTaskStatusRef.current;
    const cur = task.status;
    if (
      prev &&
      ['FAILED', 'CANCELLED'].includes(prev) &&
      runningStatuses.includes(cur)
    ) {
      clearExecutionLogs();
      loadSummary();
    }
    prevTaskStatusRef.current = cur;
  }, [task, clearExecutionLogs, loadSummary]);

  const meta = task
    ? {
        ...(statusMeta[task.status] ?? { color: 'default', label: task.status, step: -1 }),
        step: resolveFlowStep(task),
      }
    : null;

/** 把单个阶段统计转成 antd Timeline 的 item 配置 */
const timelineItem = (s: PipelineStageStat) => {
  const color =
    s.status === 'done' ? 'green'
    : s.status === 'running' ? 'blue'
    : s.status === 'error' ? 'red'
    : 'gray';
  const suffix =
    s.status === 'running' ? ' · 进行中'
    : s.status === 'pending' ? ' · 待开始'
    : s.status === 'error' ? ' · 异常'
    : s.status === 'skipped' ? ' · 已跳过'
    : '';
  const duration = s.durationMs && s.durationMs > 0
    ? `执行耗时 ${(s.durationMs / 1000).toFixed(1)} 秒`
    : '—';
  return {
    color,
    children: (
      <Space direction="vertical" size={0}>
        <Text strong>{s.label}{suffix}</Text>
        <Text type="secondary" style={{ fontSize: 12 }}>{duration}</Text>
      </Space>
    ),
  };
};

  // 当前进行中的阶段中文名（用于 Timeline 中高亮提示 + 友好提示的默认值）
  const currentStageLabel = summary?.pipeline?.find((s) => s.status === 'running')?.label ?? '';

  // 友好提示：失败时不暴露具体异常，仅指向"查看完整日志"
  const friendlyHint = (() => {
    if (!task) return '';
    const durMs = summary?.durationMs || task.durationMs || 0;
    const sec = durMs > 0 ? (durMs / 1000).toFixed(1) : '0.0';
    if (task.status === 'FAILED') {
      return '任务失败，请查看完整日志';
    }
    if (['PUSHED', 'CANCELLED', 'ARCHIVED'].includes(task.status)) {
      return `任务已结束 · 执行耗时 ${sec} 秒`;
    }
    if (['PENDING_REVIEW', 'REVIEWING', 'CONFIRMED'].includes(task.status)) {
      return `等待人工复核 · 执行耗时 ${sec} 秒`;
    }
    const done = summary?.pipeline?.filter((s) => s.status === 'done' || s.status === 'skipped').length ?? 0;
    const total = summary?.pipeline?.length ?? 9;
    return `正在：${currentStageLabel || meta?.label || task.status} · 已完成 ${done}/${total} 阶段 · 执行 ${sec} 秒`;
  })();

  // Mock 模式 / 真实模型文案
  const aiModeLabel = summary?.aiMock ? 'Mock 模式' : '真实模型';
  const aiModeColor = summary?.aiMock ? 'gold' : 'geekblue';

  if (loading) {
    return <Card loading style={{ minHeight: 420 }} />;
  }

  if (!task || !meta) {
    return (
      <Alert
        type="error"
        showIcon
        message="任务不存在"
        description={`未找到任务 #${taskId}。`}
        action={<Button onClick={() => navigate('/tasks')}>返回手动下发</Button>}
      />
    );
  }

  /** undefined 时按后端默认 TRUE：启用入口复核 / 知识复核 */
  const entryReviewEnabled = task.requireEntrypointReview !== false;
  const knowledgeReviewEnabled = task.requireKnowledgeReview !== false;
  const isIncremental = task.type === 'INCREMENTAL';
  const flowCurrent = meta.step < 0 ? 0 : meta.step;
  const entryReviewSkipped = !entryReviewEnabled && flowCurrent > FLOW_STEP_ENTRY_REVIEW;
  const knowledgeReviewSkipped = !knowledgeReviewEnabled && flowCurrent > FLOW_STEP_KNOWLEDGE_REVIEW;

  // 展示序号：全量任务去掉「基线复制」后，后续步骤整体前移 1
  const toDisplayIndex = (flowStep: number) =>
    !isIncremental && flowStep > FLOW_STEP_BASELINE ? flowStep - 1 : flowStep;
  const displayCurrent =
    meta.step < 0
      ? (task.status === 'FAILED' ? 0 : -1)
      : toDisplayIndex(
          !isIncremental && flowCurrent === FLOW_STEP_BASELINE
            ? FLOW_STEP_DOC_GEN
            : flowCurrent,
        );

  type StepItem = {
    title: string;
    description?: string;
    status?: 'finish' | 'wait' | 'process' | 'error';
    icon?: React.ReactNode;
  };

  const resolveStatus = (displayIndex: number, forced?: StepItem['status']): StepItem['status'] => {
    if (forced) return forced;
    if (task.status === 'PUSHED') return 'finish';
    if (displayCurrent < 0) return 'wait';
    if (task.status === 'FAILED' && displayIndex === displayCurrent) return 'error';
    if (displayIndex < displayCurrent) return 'finish';
    if (displayIndex === displayCurrent) return 'process';
    return 'wait';
  };

  const makeItem = (
    title: string,
    displayIndex: number,
    opts?: { forced?: StepItem['status']; description?: string },
  ): StepItem => {
    const status = resolveStatus(displayIndex, opts?.forced);
    return {
      title,
      description: opts?.description,
      status,
      // 连续编号跨两行；完成态交给 Ant Design 显示勾选
      icon: status === 'finish' || status === 'error' ? undefined : displayIndex + 1,
    };
  };

  // 上排固定 6：全量 6+5，增量含基线 6+6
  const resumeQueuedWaiting =
    task.status === 'RESUME_QUEUED' ? '排队中' : undefined;
  const resumeOnAi =
    task.status === 'RESUME_QUEUED'
    && (task.resumeFrom === RESUME_AFTER_ENTRYPOINT || !task.resumeFrom);
  const resumeOnBaseline =
    task.status === 'RESUME_QUEUED'
    && task.resumeFrom === RESUME_AFTER_HIERARCHY
    && isIncremental;
  const resumeOnDocGen =
    task.status === 'RESUME_QUEUED'
    && task.resumeFrom === RESUME_AFTER_HIERARCHY
    && !isIncremental;

  const allStepItems: StepItem[] = [
    makeItem('排队', toDisplayIndex(0)),
    makeItem('拉取代码', toDisplayIndex(1)),
    makeItem('静态解析', toDisplayIndex(2)),
    makeItem('入口复核', toDisplayIndex(FLOW_STEP_ENTRY_REVIEW), {
      forced: entryReviewSkipped ? 'finish' : !entryReviewEnabled ? 'wait' : undefined,
      description: entryReviewEnabled ? undefined : (entryReviewSkipped ? '已跳过' : '未启用'),
    }),
    makeItem('AI 分析', toDisplayIndex(FLOW_STEP_AI), {
      description: resumeOnAi ? resumeQueuedWaiting : undefined,
    }),
    makeItem('模块层级复核', toDisplayIndex(FLOW_STEP_HIERARCHY_REVIEW)),
    ...(isIncremental
      ? [makeItem('基线复制', toDisplayIndex(FLOW_STEP_BASELINE), {
          description: resumeOnBaseline ? resumeQueuedWaiting : undefined,
        })]
      : []),
    makeItem('生成文档', toDisplayIndex(FLOW_STEP_DOC_GEN), {
      description: resumeOnDocGen ? resumeQueuedWaiting : undefined,
    }),
    makeItem('知识复核', toDisplayIndex(FLOW_STEP_KNOWLEDGE_REVIEW), {
      forced: knowledgeReviewSkipped ? 'finish' : !knowledgeReviewEnabled ? 'wait' : undefined,
      description: knowledgeReviewEnabled
        ? undefined
        : (knowledgeReviewSkipped ? '已跳过' : '未启用'),
    }),
    makeItem('建版', toDisplayIndex(FLOW_STEP_CREATE_VERSION), {
      description: taskVersion?.versionNum,
    }),
    makeItem('NAS推送', toDisplayIndex(FLOW_STEP_PUSH), {
      description:
        task.status === 'PUSHED' ? (taskVersion?.pushMethod || 'NAS') : undefined,
    }),
    makeItem('完成', toDisplayIndex(FLOW_STEP_DONE)),
  ];

  // 固定上排 6 个：全量 6+5，增量（含基线）6+6
  const row1Count = 6;
  const row1Items = allStepItems.slice(0, row1Count);
  const row2Items = allStepItems.slice(row1Count);
  // 两排各自的 current：未进入该排时用 -1
  const row1Current = displayCurrent < 0 ? -1 : Math.min(displayCurrent, row1Count - 1);
  const row2Current =
    displayCurrent < row1Count ? -1 : Math.min(displayCurrent - row1Count, row2Items.length - 1);
  const row2InboundReady = displayCurrent >= row1Count - 1 || task.status === 'PUSHED';

  return (
    <div className="ci-page ci-task-detail-page">
      {/* 头部面板与快捷动作操作条 */}
      <Card>
        <div className="ci-detail-header">
          <Space wrap>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/tasks')}>
              返回
            </Button>
            <div>
              <Title level={4}>知识构建任务 #{task.id}</Title>
              <Text type="secondary">{system?.name ?? `系统 #${task.systemId}`}</Text>
            </div>
            <Tag color={meta.color}>{meta.label}</Tag>
            <Tag color={task.type === 'INITIAL' ? 'geekblue' : 'green'}>{task.type === 'INITIAL' ? '全量扫描' : '增量扫描'}</Tag>
          </Space>

          <Space wrap>
            {task.status === 'DRAFT' && (
              <Button
                type="primary"
                icon={<PlayCircleOutlined />}
                loading={actionLoading}
                onClick={() => runAction(() => startTask(task.id), '任务已启动', true)}
              >
                启动
              </Button>
            )}
            {runningStatuses.includes(task.status) && (
              <Button
                danger
                icon={<CloseCircleOutlined />}
                loading={actionLoading}
                onClick={() => runAction(() => terminateTask(task.id), '终止请求已发送')}
              >
                终止
              </Button>
            )}
            {['FAILED', 'CANCELLED'].includes(task.status) && (
              <Button
                type="primary"
                icon={<ReloadOutlined />}
                loading={actionLoading}
                onClick={() => runAction(() => retryTask(task.id), '任务已重新启动', true)}
              >
                重试
              </Button>
            )}
            {task.status === 'FAILED' && task.type === 'INCREMENTAL' && (
              <Button
                icon={<ReloadOutlined />}
                loading={actionLoading}
                onClick={() => runAction(() => retryBaselineInherit(task.id), '基线文档重新继承已启动', true)}
              >
                重新继承基线文档
              </Button>
            )}
            {['PENDING_REVIEW', 'REVIEWING', 'CONFIRMED'].includes(task.status) && (
              <Button type="primary" icon={<EditOutlined />} onClick={() => navigate(buildDraftsHref(task))}>
                复核草稿
              </Button>
            )}
            <Button icon={<FileSearchOutlined />} onClick={() => navigate(`/logs?taskId=${task.id}`)}>
              查看日志
            </Button>
          </Space>
        </div>
      </Card>

      {/* 执行流程：上排 6 节点；下排首节点双倍宽以容纳等长导入线，整行拉满右对齐 */}
      <Card title="执行流程状态" className="ci-task-flow-card" styles={{ body: { padding: '10px 16px 8px' } }}>
        <div className="ci-task-flow-rows">
          <Steps
            size="small"
            current={row1Current}
            items={row1Items}
            className="ci-task-flow-row"
          />
          <Steps
            size="small"
            current={row2Current}
            items={row2Items}
            className={`ci-task-flow-row ci-task-flow-row-inbound${
              row2InboundReady ? ' ci-task-flow-inbound-ready' : ''
            }`}
          />
        </div>
      </Card>

      {/* 入口复核提示：跳转到入口复核详情页 */}
      {task.status === 'ENTRYPOINT_REVIEW' && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="知识入口需要人工复核"
          description="静态解析与入口识别已完成，需要您确认入口类与方法清单。请点击下方按钮前往「入口复核」页面处理。"
          action={
            <Button type="primary" icon={<SwapOutlined />} onClick={() => navigate(`/tasks/entrypoint-review/${task.id}`)}>
              前往入口复核
            </Button>
          }
        />
      )}

      {/* 模块层级复核提示：统一跳转到「模块层级复核」专用页面处理 */}
      {task.status === 'MODULE_HIERARCHY_REVIEW' && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="模块层级需要人工复核"
          description="AI 已完成模块层级提炼，需要您复核并确认模块与功能结构。请点击下方按钮前往「模块层级复核」页面集中处理。"
          action={
            <Button type="primary" icon={<SwapOutlined />} onClick={() => navigate('/tasks/hierarchy-review')}>
              前往模块层级复核
            </Button>
          }
        />
      )}

      {/* 草稿就绪提示横幅 */}
      {['PENDING_REVIEW', 'REVIEWING', 'CONFIRMED'].includes(task.status) && (
        <Alert
          type="success"
          showIcon
          message="知识草稿已就绪"
          description="生成的 Markdown 已进入平台草稿区，仍需人工复核后才能成为确认的知识版本。"
          action={
            <Button type="primary" icon={<CheckCircleOutlined />} onClick={() => navigate(buildDraftsHref(task))}>
              打开复核
            </Button>
          }
        />
      )}

      {/* 任务失败错误提示框 */}
      {task.status === 'FAILED' && task.errorReason && (
        <Alert
          type="error"
          showIcon
          message="执行错误"
          description={task.errorReason}
          action={
            <Button size="small" icon={<FileSearchOutlined />} onClick={() => navigate(`/logs?taskId=${task.id}`)}>
              查看日志
            </Button>
          }
        />
      )}

      {task.type === 'INCREMENTAL' && task.triggerSource !== 'KNOWLEDGE_REMEDIATION' && (
        <IncrementalImpactCard
          taskId={task.id}
          polling={runningStatuses.includes(task.status)}
        />
      )}

      <Row gutter={[16, 16]}>
        {/* 左侧：任务静态指标表格 */}
        <Col xs={24} xl={12}>
          <Card title="任务配置" style={{ height: '100%' }}>
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label="业务系统">{system?.name ?? `系统 #${task.systemId}`}</Descriptions.Item>
              <Descriptions.Item label="负责人">{system?.owner || '-'}</Descriptions.Item>
              <Descriptions.Item label="代码库 ID">{task.repositoryId}</Descriptions.Item>
              <Descriptions.Item label="提示词版本">v{task.promptVersion || 1}</Descriptions.Item>
              <Descriptions.Item label="AI模型">{task.modelName || '-'}</Descriptions.Item>
              <Descriptions.Item label="进度">
                <Progress percent={task.progress} size="small" status={task.status === 'FAILED' ? 'exception' : 'active'} />
              </Descriptions.Item>
              <Descriptions.Item label="执行耗时">{task.durationMs ? `${(task.durationMs / 1000).toFixed(1)} 秒` : '-'}</Descriptions.Item>
              <Descriptions.Item label="开始时间">{task.startedAt ? new Date(task.startedAt).toLocaleString() : '-'}</Descriptions.Item>
              <Descriptions.Item label="结束时间">{task.endedAt ? new Date(task.endedAt).toLocaleString() : '-'}</Descriptions.Item>
              <Descriptions.Item label="日志 URI">
                <Text code>{`local://storage/task_${task.id}/pipeline.log`}</Text>
              </Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>

        {/* 右侧：执行日志卡片（结构化概览；无异常堆栈） */}
        <Col xs={24} xl={12}>
          <Card
            title="执行日志"
            extra={
              <Space size={4} wrap>
                <Tag color={aiModeColor}>{aiModeLabel}</Tag>
                <Tag color="purple">{summary?.modelName || task.modelName || '未指定模型'}</Tag>
                <Button size="small" icon={<FileSearchOutlined />} onClick={openExecLog}>
                  查看完整日志
                </Button>
              </Space>
            }
            style={{ height: '100%' }}
            styles={{ body: { padding: '12px 16px' } }}
          >
            {/* 1. 状态条 + 友好提示 */}
            <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 8 }} wrap>
              <Space size={6}>
                <Tag color={meta.color}>{meta.label}</Tag>
                {currentStageLabel && <Text type="secondary" style={{ fontSize: 12 }}>当前：{currentStageLabel}</Text>}
              </Space>
              <Text type="secondary" style={{ fontSize: 12 }}>{friendlyHint}</Text>
            </Space>
            <Progress
              percent={summary?.progress ?? task.progress}
              size="small"
              status={task.status === 'FAILED' ? 'exception' : 'active'}
            />

            {/* 2. KPI 行：扫描文件 / 模块数 / AI 调用统计 */}
            <div className="ci-kpi-grid" style={{ marginTop: 12 }}>
              <Card size="small" className="ci-stat-card">
                <Statistic
                  title="扫描文件"
                  value={
                    summary
                      ? `${summary.counters.totalFiles || summary.current.totalFiles || 0}`
                      : 0
                  }
                  valueStyle={{ fontSize: 20 }}
                />
              </Card>
              <Card size="small" className="ci-stat-card">
                <Statistic
                  title="模块数"
                  value={
                    summary
                      ? summary.current?.moduleTotal ?? 0
                      : 0
                  }
                  valueStyle={{ fontSize: 20 }}
                />
              </Card>
              <Card size="small" className="ci-stat-card">
                <Statistic
                  title="模块提炼 AI"
                  value={summary?.hierarchyAiCalls?.success ?? summary?.aiCalls.success ?? 0}
                  valueStyle={{ color: '#16a34a', fontSize: 20 }}
                  prefix={<CheckCircleOutlined />}
                  suffix={
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      / {(summary?.hierarchyAiCalls?.total ?? 0)} 调用
                      {(summary?.hierarchyAiCalls?.failed ?? 0) > 0 &&
                        <Text type="danger" style={{ fontSize: 12 }}> · {(summary?.hierarchyAiCalls?.failed ?? 0)} 失败</Text>
                      }
                    </Text>
                  }
                />
              </Card>
              <Card size="small" className="ci-stat-card">
                <Statistic
                  title="文档生成 AI"
                  value={summary?.docAiCalls?.success ?? 0}
                  valueStyle={{ color: '#16a34a', fontSize: 20 }}
                  prefix={<CheckCircleOutlined />}
                  suffix={
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      / {(summary?.docAiCalls?.total ?? 0)} 调用
                      {(summary?.docAiCalls?.failed ?? 0) > 0 &&
                        <Text type="danger" style={{ fontSize: 12 }}> · {(summary?.docAiCalls?.failed ?? 0)} 失败</Text>
                      }
                    </Text>
                  }
                />
              </Card>
            </div>

            {/* 3. 失败/完成友好提示（仅一行，不暴露堆栈） */}
            {task.status === 'FAILED' && (
              <Alert
                type="warning"
                showIcon
                style={{ marginTop: 12 }}
                message="任务失败，请查看完整日志"
                description={summary?.lastError ? `原因：${summary.lastError}` : undefined}
                action={
                  <Button size="small" icon={<FileSearchOutlined />} onClick={openExecLog}>
                    查看完整日志
                  </Button>
                }
              />
            )}
            {['PUSHED', 'CONFIRMED'].includes(task.status) && (
              <Alert type="success" showIcon style={{ marginTop: 12 }} message="任务已完成" />
            )}

            {/* 4. 阶段 Timeline */}
            {summary?.pipeline && summary.pipeline.length > 0 && (
              <Timeline
                key={`pipeline-${summary.pipeline.length}`}
                style={{ marginTop: 12 }}
                items={summary.pipeline.map((s) => ({ key: s.key, ...timelineItem(s) }))}
              />
            )}
          </Card>
        </Col>
      </Row>

      {/* 底部统计及预估栏目 */}
      {['PENDING_REVIEW', 'REVIEWING', 'CONFIRMED', 'PUSHED'].includes(task.status) && (
        <div className="ci-kpi-grid">
          <Card size="small">
            <Statistic title="模块数" value={summary?.current?.moduleTotal ?? 0} />
          </Card>
          <Card size="small">
            <Statistic title="AI 调用数" value={65} />
          </Card>
          <Card size="small">
            <Statistic title="Token 预估" value={98420} />
          </Card>
          <Card size="small">
            <Statistic title="成本预估" value={0.59} prefix="$" precision={2} />
          </Card>
        </div>
      )}

      <Modal
        title={`执行日志 — 任务 #${task.id}`}
        open={logModalOpen}
        onCancel={() => setLogModalOpen(false)}
        width={960}
        footer={(
          <Space>
            <Button icon={<CopyOutlined />} onClick={copyExecLog} disabled={!execLogContent}>
              复制
            </Button>
            <Button icon={<DownloadOutlined />} onClick={downloadExecLog} disabled={!execLogContent}>
              导出
            </Button>
            <Button onClick={() => setLogModalOpen(false)}>关闭</Button>
          </Space>
        )}
        destroyOnClose
      >
        {/* 顶部粘性信息条：Mock / 模型 / 执行耗时 / 状态 / 日志 URI */}
        <div
          style={{
            position: 'sticky',
            top: 0,
            background: '#fff',
            zIndex: 1,
            padding: '8px 0',
            borderBottom: '1px solid #f0f0f0',
            marginBottom: 8,
          }}
        >
          <Space wrap>
            <Tag color={aiModeColor}>{aiModeLabel}</Tag>
            <Tag color="purple">模型：{summary?.modelName || task.modelName || '未指定'}</Tag>
            <Tag color="blue">
              执行耗时 {(((summary?.durationMs ?? task.durationMs) || 0) / 1000).toFixed(1)} 秒
            </Tag>
            <Tag color={meta.color}>{meta.label}</Tag>
            <Text type="secondary" copyable={{ text: `local://storage/task_${task.id}/pipeline.log` }}>
              日志 URI：`local://storage/task_${task.id}/pipeline.log`
            </Text>
            <Button
              size="small"
              icon={<ReloadOutlined />}
              onClick={openExecLog}
              disabled={logLoading}
            >
              刷新
            </Button>
          </Space>
        </div>

        {logLoading ? (
          <Card loading style={{ minHeight: 200 }} />
        ) : (
          <pre
            className="ci-terminal"
            style={{
              fontSize: 12,
              maxHeight: 480,
              overflow: 'auto',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
              margin: 0,
              background: '#1e1e1e',
              color: '#d4d4d4',
              padding: 12,
              borderRadius: 4,
            }}
          >
            {execLogContent || '(暂无日志)'}
          </pre>
        )}
      </Modal>
    </div>
  );
};

export default TaskDetail;
