import { Alert, Card, Collapse, Space, Statistic, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { getTaskIncrementalImpact } from '../../../api/task';
import type { ImpactTraceDto, IncrementalImpactDto } from '../../../types';

const { Text } = Typography;

const kindLabel: Record<string, string> = {
  ENTRY_DIRECT: '入口直接变更',
  REVERSE_BFS: '反向调用链',
  DEGRADED_CLASS_PATH: 'classPaths 降级',
};

interface Props {
  taskId: number;
  polling: boolean;
}

const IncrementalImpactCard: React.FC<Props> = ({ taskId, polling }) => {
  const [impact, setImpact] = useState<IncrementalImpactDto | null>(null);

  const loadImpact = useCallback(async () => {
    try {
      const data = await getTaskIncrementalImpact(taskId);
      setImpact(data);
    } catch {
      // 静默失败，保留上次结果
    }
  }, [taskId]);

  useEffect(() => {
    loadImpact();
  }, [loadImpact]);

  useEffect(() => {
    if (!polling) return;
    const timer = window.setInterval(loadImpact, 2500);
    return () => window.clearInterval(timer);
  }, [loadImpact, polling]);

  if (!impact) {
    return null;
  }

  if (!impact.incremental) {
    return null;
  }

  if (!impact.available) {
    return (
      <Alert
        type="info"
        showIcon
        message="增量影响分析"
        description={impact.message ?? '影响分析将在入口确认后生成'}
      />
    );
  }

  const traces = (impact.traces ?? []).slice(0, 20);

  return (
    <Card title="增量影响分析">
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Space wrap>
          <Tag color="green">增量扫描</Tag>
          {impact.baselineCommitId && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              基线 {impact.baselineCommitId.slice(0, 8)} → HEAD {impact.headCommitId?.slice(0, 8) ?? '-'}
            </Text>
          )}
          {impact.scanMode === 'DEGRADED_FULL' && (
            <Tag color="orange">已降级全量扫描</Tag>
          )}
        </Space>

        <div className="ci-kpi-grid">
          <Card size="small" className="ci-stat-card">
            <Statistic title="变更文件" value={impact.changedPaths?.length ?? 0} valueStyle={{ fontSize: 20 }} />
          </Card>
          <Card size="small" className="ci-stat-card">
            <Statistic title="删除文件" value={impact.deletedPaths?.length ?? 0} valueStyle={{ fontSize: 20 }} />
          </Card>
          <Card size="small" className="ci-stat-card">
            <Statistic title="重算层级入口" value={impact.hierarchyRetargetEntryCount ?? 0} valueStyle={{ fontSize: 20 }} />
          </Card>
          <Card size="small" className="ci-stat-card">
            <Statistic
              title="重生成文档模块"
              value={impact.docRetargetModuleIds?.length ?? 0}
              valueStyle={{ fontSize: 20 }}
            />
          </Card>
        </div>

        {(impact.degradedModuleCount ?? 0) > 0 && (
          <Text type="secondary">降级命中：{impact.degradedModuleCount} 个模块（classPaths 直接匹配）</Text>
        )}

        {traces.length > 0 && (
          <Collapse
            size="small"
            items={[
              {
                key: 'traces',
                label: `影响链（${impact.traces?.length ?? 0} 条）`,
                children: (
                  <Space direction="vertical" size={8} style={{ width: '100%' }}>
                    {traces.map((trace: ImpactTraceDto, idx: number) => (
                      <Card key={`${trace.moduleId}-${idx}`} size="small" type="inner">
                        <Space direction="vertical" size={2}>
                          <Text>{trace.path}</Text>
                          <Text type="secondary">
                            → 模块「{trace.moduleName}」
                            <Tag style={{ marginLeft: 8 }}>{kindLabel[trace.kind] ?? trace.kind}</Tag>
                          </Text>
                        </Space>
                      </Card>
                    ))}
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Space>
    </Card>
  );
};

export default IncrementalImpactCard;
