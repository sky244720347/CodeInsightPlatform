import React from 'react';
import { Navigate, useParams } from 'react-router-dom';
import HierarchyReviewWorkspace from './HierarchyReviewWorkspace';

/**
 * 模块层级复核详情：按任务 ID 打开复核工作区。
 *
 * 参照扫描入口复核的 entrypoint-review-detail.tsx 写法：从 URL 拿 taskId,无效则跳回列表页。
 */
const HierarchyReviewDetailPage: React.FC = () => {
  const { taskId } = useParams();
  const id = Number(taskId);
  if (!Number.isFinite(id) || id <= 0) {
    return <Navigate to="/tasks/hierarchy-review" replace />;
  }
  return <HierarchyReviewWorkspace taskId={id} />;
};

export default HierarchyReviewDetailPage;