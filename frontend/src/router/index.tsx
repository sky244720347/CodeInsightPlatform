import { createHashRouter, Navigate } from 'react-router-dom';
import BasicLayout from '../layouts/BasicLayout';
import RequireAuth from './RequireAuth';
import RequireRole from './RequireRole';
import TaskOverview from '../pages/dashboard/tasks-overview';
import AiUsage from '../pages/dashboard/ai-usage';
import PipelineAnalysis from '../pages/dashboard/pipeline-analysis';
import SystemCoverage from '../pages/dashboard/system-coverage';
import Systems from '../pages/systems';
import Tasks from '../pages/tasks';
import TaskListTab from '../pages/tasks/TaskListTab';
import TaskQueuePage from '../pages/tasks/queue';
import TaskDispatchPage from '../pages/tasks/dispatch';
import TaskDetail from '../pages/tasks/detail';
import HierarchyReview from '../pages/tasks/hierarchy-review';
import EntrypointReview from '../pages/tasks/entrypoint-review';
import EntrypointReviewDetail from '../pages/tasks/entrypoint-review-detail';
import KnowledgeDocuments from '../pages/knowledge/documents';
import KnowledgeEntrypoints from '../pages/knowledge/entrypoints';
import KnowledgeHierarchy from '../pages/knowledge/hierarchy';
import Drafts from '../pages/drafts';
import DraftReviewDetail from '../pages/drafts/detail';
import Push from '../pages/push';
import TokenAudit from '../pages/token-audit';
import Logs from '../pages/logs';
// 基础配置模块
import BasicSubLayout from '../pages/basic/layout';
import ModelConfig from '../pages/basic/models';
import Prompts from '../pages/basic/prompts';
import Permissions from '../pages/basic/permissions';
import QuotaControl from '../pages/basic/quota-control';
import TaskOrchestration from '../pages/basic/orchestration';
import Login from '../pages/login';

/**
 * 全局 React 路由配置映射表
 * 采用 hash 路由模式（createHashRouter），便于离线部署或纯静态包服务器托管。
 * 嵌套于 BasicLayout 基础排版结构下，所有子路由都将渲染在页面的 Content 区域（通过 Outlet 组件实现）。
 */
export const router = createHashRouter([
  {
    path: '/login',
    element: <Login />,
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <BasicLayout />
      </RequireAuth>
    ),
    children: [
      // 默认跳转：进入任务概览（仪表盘第一项）
      { path: '', element: <Navigate to="/dashboard/tasks" replace /> },
      // ────────── 仪表盘 / 看板 ──────────
      {
        path: 'dashboard/tasks', // 任务概览
        element: <TaskOverview />,
      },
      {
        path: 'dashboard/ai-usage', // AI 模型用量
        element: <AiUsage />,
      },
      {
        path: 'dashboard/pipeline', // 流水线分析
        element: <PipelineAnalysis />,
      },
      {
        path: 'dashboard/coverage', // 系统覆盖报表
        element: <SystemCoverage />,
      },
      // ────────── 知识生成 ──────────
      {
        path: 'systems', // 系统接入与代码库管理
        element: <Systems />,
      },
      // 基础配置模块（4 个子页）— UI 层角色门 <RequireRole role="ADMIN">
      {
        path: 'basic',
        element: (
          <RequireRole role="ADMIN">
            <BasicSubLayout />
          </RequireRole>
        ),
        children: [
          { index: true, element: <ModelConfig /> },
          { path: 'models', element: <ModelConfig /> },
          { path: 'prompts', element: <Prompts /> },
          { path: 'permissions', element: <Permissions /> },
          { path: 'quota', element: <QuotaControl /> },
          { path: 'orchestration', element: <TaskOrchestration /> },
        ],
      },
      // 知识构建任务：子路由由侧栏独立入口访问（无页签壳）
      {
        path: 'tasks',
        element: <Tasks />,
        children: [
          { index: true, element: <TaskListTab /> },
          { path: 'query', element: <TaskListTab /> },
          { path: 'queue', element: <TaskQueuePage /> },
          { path: 'dispatch', element: <TaskDispatchPage /> },
        ],
      },
      {
        path: 'tasks/hierarchy-review', // 模块层级调试专用页
        element: <HierarchyReview />,
      },
      {
        path: 'tasks/entrypoint-review',
        element: <EntrypointReview />,
      },
      {
        path: 'tasks/entrypoint-review/:taskId',
        element: <EntrypointReviewDetail />,
      },
      {
        path: 'tasks/:id', // 任务执行详情与流程监控
        element: <TaskDetail />,
      },
      {
        path: 'knowledge/documents',
        element: <KnowledgeDocuments />,
      },
      {
        path: 'knowledge/entrypoints',
        element: <KnowledgeEntrypoints />,
      },
      {
        path: 'knowledge/hierarchy',
        element: <KnowledgeHierarchy />,
      },
      {
        path: 'knowledge/browse',
        element: <Navigate to="/knowledge/documents" replace />,
      },
      // 旧 /models / /prompts 入口已迁移到 /basic/models / /basic/prompts
      { path: 'models', element: <Navigate to="/basic/models" replace /> },
      { path: 'prompts', element: <Navigate to="/basic/prompts" replace /> },
      {
        path: 'drafts',
        element: <Drafts />,
      },
      {
        path: 'drafts/:taskId',
        element: <DraftReviewDetail />,
      },
      {
        path: 'push', // 知识推送确认与推送 Git 库版本管理
        element: <Push />,
      },
      {
        path: 'audit', // Token 消耗与计费审计报表页面
        element: <TokenAudit />,
      },
      {
        path: 'logs', // 操作历史审计日志页面
        element: <Logs />,
      },
    ],
  },
]);
