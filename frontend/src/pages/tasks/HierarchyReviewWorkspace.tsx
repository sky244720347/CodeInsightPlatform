import React, { useEffect, useState } from 'react';
import {
  Button,
  Card,
  Popconfirm,
  Space,
  Spin,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  getModuleHierarchy,
  getTask,
  replaceModuleHierarchy,
  resumeModuleHierarchyReview,
} from '../../api/task';
import type { ModuleHierarchy, Task } from '../../types';
import PageHelpHint from '../../components/PageHelpHint';
import ModuleHierarchyEditor from '../../components/ModuleHierarchyEditor';
import { hierarchyReviewDetailHelp } from '../../constants/reviewPageHelp';

const { Text } = Typography;

export interface HierarchyReviewWorkspaceProps {
  taskId: number;
  /** 提交成功后的回调（如刷新父级列表）。一般不需要,工作区内部会自行 navigate 回列表页。 */
  onSubmitted?: () => void;
}

/**
 * 模块层级复核工作区（独立全屏页）：
 * - 自管理数据加载：`getModuleHierarchy(taskId)` + `getTask(taskId)`
 * - 自管理提交流水线：`replaceModuleHierarchy(taskId)` + `resumeModuleHierarchyReview(taskId)`
 * - 把受控后的 `ModuleHierarchyEditor` 渲染为整页内容,带顶栏(返回/状态/保存并继续)
 * - 替代原先的 `ModuleHierarchyEditorDrawer` 抽屉模式
 */
const HierarchyReviewWorkspace: React.FC<HierarchyReviewWorkspaceProps> = ({
  taskId,
  onSubmitted,
}) => {
  const navigate = useNavigate();
  const [task, setTask] = useState<Task | null>(null);
  const [taskLoading, setTaskLoading] = useState(false);
  const [hierarchy, setHierarchy] = useState<ModuleHierarchy | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  // 任务元信息
  useEffect(() => {
    setTaskLoading(true);
    getTask(taskId)
      .then(setTask)
      .catch(() => setTask(null))
      .finally(() => setTaskLoading(false));
  }, [taskId]);

  // 模块层级数据
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getModuleHierarchy(taskId)
      .then((data) => {
        if (cancelled) return;
        setHierarchy(data ?? { taskId, modules: {} });
      })
      .catch(() => {
        if (!cancelled) setHierarchy({ taskId, modules: {} });
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [taskId]);

  const handleSubmit = async () => {
    if (!hierarchy) return;
    setSaving(true);
    try {
      await replaceModuleHierarchy(taskId, hierarchy);
      await resumeModuleHierarchyReview(taskId);
      message.success('已保存并提交,任务继续生成文档');
      onSubmitted?.();
      navigate('/tasks/hierarchy-review');
    } finally {
      setSaving(false);
    }
  };

  const canReview = task?.status === 'MODULE_HIERARCHY_REVIEW';

  return (
    <div className="ci-page ci-hierarchy-review-detail-page">
      <Card
        title={
          <Space wrap>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate('/tasks/hierarchy-review')}
            >
              返回任务列表
            </Button>
            <Text strong>模块层级复核 · 任务 #{taskId}</Text>
            <PageHelpHint
              title={hierarchyReviewDetailHelp.title}
              content={hierarchyReviewDetailHelp.content}
            />
            {task && (
              <>
                <Tag color={task.type === 'INITIAL' ? 'geekblue' : 'green'}>
                  {task.type === 'INITIAL' ? '全量' : '增量'}
                </Tag>
                <Tag color="geekblue">{task.status}</Tag>
              </>
            )}
            {taskLoading && <Text type="secondary">加载中…</Text>}
          </Space>
        }
        extra={
          canReview ? (
            <Space>
              <Popconfirm
                title="确认提交并继续生成文档？"
                description="保存当前修改后将进入 GENERATING_DOC,无法再返回调试状态。"
                okText="确认并继续"
                cancelText="再检查下"
                onConfirm={handleSubmit}
              >
                <Button
                  type="primary"
                  icon={<CheckCircleOutlined />}
                  loading={saving}
                >
                  保存并继续
                </Button>
              </Popconfirm>
            </Space>
          ) : (
            <Text type="secondary">当前任务不在模块层级复核状态,仅可浏览</Text>
          )
        }
      >
        {loading ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <Spin indicator={<LoadingOutlined />} /> 正在加载模块层级…
          </div>
        ) : (
          <ModuleHierarchyEditor
            value={hierarchy}
            loading={loading}
            saving={saving}
            onChange={setHierarchy}
            renderAlert={() => null}
          />
        )}
      </Card>
    </div>
  );
};

export default HierarchyReviewWorkspace;