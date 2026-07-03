import React from 'react';
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Space,
  Table,
  Tag,
} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { Repository, System } from '../../types';
import { useRepositoryColumns } from './drawerColumns';

interface Props {
  open: boolean;
  system: System | null;
  repositories: Repository[];
  loading: boolean;
  onClose: () => void;
  onAddRepo: () => void;
  onEditRepo: (repo: Repository) => void;
  onDeleteRepo: (repo: Repository) => void;
  onScan: (repo: Repository) => void;
  onScanConfig?: (repo: Repository) => void;
  onBindPrompts?: (repo: Repository) => void;
  onScanWindow?: (repo: Repository) => void;
}

/**
 * 系统详情 Drawer
 * 上半区：系统信息（代码库数 / 知识版本数 / 最近扫描等聚合指标）
 * 下半区：代码库列表（行内操作：扫描 / 编辑 / 测试 / 删除）
 *
 * 列表列定义已抽到 drawerColumns.tsx，本组件只负责布局 + 信息卡
 */
const RepositoryDrawer: React.FC<Props> = ({
  open,
  system,
  repositories,
  loading,
  onClose,
  onAddRepo,
  onEditRepo,
  onDeleteRepo,
  onScan,
  onScanConfig,
  onBindPrompts,
  onScanWindow,
}) => {
  const columns = useRepositoryColumns({
    onEdit: onEditRepo,
    onDelete: onDeleteRepo,
    onScan,
    onScanConfig,
    onBindPrompts,
    onScanWindow,
  });

  return (
    <Drawer
      title={system ? `${system.name} · 代码库` : '代码库'}
      width={1000}
      onClose={onClose}
      open={open}
      destroyOnHidden
    >
      {system && (
        <Space direction="vertical" size={18} style={{ width: '100%' }}>
          <Card size="small" title="系统信息">
            <Descriptions column={3} size="small">
              <Descriptions.Item label="中文名称">
                {system.nameCn || '未填写'}
              </Descriptions.Item>
              <Descriptions.Item label="负责人">
                <Tag color="blue">{system.owner}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="代码库数">{system.repositoryCount ?? 0}</Descriptions.Item>
              <Descriptions.Item label="知识版本数">{system.knowledgeVersionCount ?? 0}</Descriptions.Item>
              <Descriptions.Item label="最近扫描" span={2}>
                {system.lastDecompileAt
                  ? new Date(system.lastDecompileAt).toLocaleString()
                  : '未扫描'}
              </Descriptions.Item>
              <Descriptions.Item label="描述" span={3}>
                {system.description || '暂无描述'}
              </Descriptions.Item>
            </Descriptions>
          </Card>

          <Card
            size="small"
            title="Git 代码库"
            extra={
              <Button
                type="primary"
                size="small"
                icon={<PlusOutlined />}
                onClick={onAddRepo}
              >
                添加代码库
              </Button>
            }
          >
            <Table
              dataSource={repositories}
              rowKey="id"
              loading={loading}
              size="small"
              pagination={false}
              scroll={{ x: 880 }}
              columns={columns}
            />
          </Card>
        </Space>
      )}
    </Drawer>
  );
};

export default RepositoryDrawer;
