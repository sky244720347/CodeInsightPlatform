import React, { useEffect, useState } from 'react';
import { Button, Drawer, Popconfirm, Space, message } from 'antd';
import { CheckCircleOutlined } from '@ant-design/icons';
import {
  getModuleHierarchy,
  replaceModuleHierarchy,
  resumeModuleHierarchyReview,
} from '../api/task';
import type { ModuleHierarchy } from '../types';
import ModuleHierarchyEditor from './ModuleHierarchyEditor';

export interface ModuleHierarchyEditorDrawerProps {
  open: boolean;
  taskId: number | null;
  onClose: () => void;
  /** 保存并继续后通知父组件刷新列表 */
  onSubmitted?: () => void;
}

/**
 * 模块层级调试抽屉（任务流专用包装器）：
 * - 自管理数据加载：`getModuleHierarchy(taskId)`
 * - 自管理提交流水线：`replaceModuleHierarchy(taskId)` + `resumeModuleHierarchyReview(taskId)`
 * - 把受控后的 `ModuleHierarchyEditor` 作为子组件使用
 *
 * 知识查看页的「调整并重跑」走另一条路径：`KnowledgeHierarchyRemediationDrawer`。
 */
const ModuleHierarchyEditorDrawer: React.FC<ModuleHierarchyEditorDrawerProps> = ({
  open,
  taskId,
  onClose,
  onSubmitted,
}) => {
  const [hierarchy, setHierarchy] = useState<ModuleHierarchy | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  // taskId 变化时重新加载
  useEffect(() => {
    if (taskId == null) {
      setHierarchy(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    getModuleHierarchy(taskId)
      .then((data) => {
        if (cancelled) return;
        setHierarchy(data ?? { taskId, modules: {} });
      })
      .catch(() => {
        // request.ts 拦截器已统一弹错
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [taskId]);

  const handleSubmit = async () => {
    if (!hierarchy || taskId == null) return;
    setSaving(true);
    try {
      await replaceModuleHierarchy(taskId, hierarchy);
      await resumeModuleHierarchyReview(taskId);
      message.success('已保存并提交继续生成文档');
      onClose();
      onSubmitted?.();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      title={taskId ? `模块层级调试 #${taskId}` : '模块层级调试'}
      open={open}
      width={1060}
      onClose={onClose}
      destroyOnHidden
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
        </Space>
      }
    >
      <ModuleHierarchyEditor
        value={hierarchy}
        loading={loading}
        saving={saving}
        onChange={setHierarchy}
        onSubmit={handleSubmit}
        renderSubmit={(submit, savingFlag) => (
          <Popconfirm
            title="确认提交并继续生成文档？"
            description="保存当前修改后将进入 GENERATING_DOC，无法再返回调试状态。"
            okText="确认并继续"
            cancelText="再检查下"
            onConfirm={submit}
          >
            <Button type="primary" icon={<CheckCircleOutlined />} loading={savingFlag}>
              保存并继续
            </Button>
          </Popconfirm>
        )}
      />
    </Drawer>
  );
};

export default ModuleHierarchyEditorDrawer;
