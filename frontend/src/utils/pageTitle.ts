/**
 * 静态路径 → 显示标题 映射。
 * 父级路由（/basic、/tasks、/tasks/jobs）在子路由打开时合成更具体的标题。
 */

export interface PathMeta {
  /** 静态标题（路径完全匹配时使用） */
  title: string;
  /** 父级路径（用于面包屑/子路由判断） */
  parent?: string;
}

export const PATH_META: Record<string, PathMeta> = {
  '/systems': { title: '系统与仓库' },

  '/basic/models': { title: '模型配置', parent: '基础配置' },
  '/basic/prompts': { title: '提示词', parent: '基础配置' },
  '/basic/prompts/defaults': { title: '默认提示词维护', parent: '基础配置' },
  '/basic/permissions': { title: '权限管理', parent: '基础配置' },
  '/basic/quota': { title: '流量管控', parent: '基础配置' },

  '/tasks/query': { title: '任务查询', parent: '知识构建任务' },
  '/tasks/queue': { title: '任务队列', parent: '知识构建任务' },
  '/tasks/dispatch': { title: '手动下发', parent: '知识构建任务' },
  '/tasks/jobs': { title: 'JOB配置', parent: '知识构建任务' },
  '/tasks/jobs/new': { title: '新建定时任务', parent: '知识构建任务' },
  '/tasks/entrypoint-review': { title: '入口复核' },
  '/tasks/hierarchy-review': { title: '模块层级复核' },

  '/drafts': { title: '知识复核' },
  '/push': { title: '推送记录' },
  '/audit': { title: 'Token 审计' },
  '/logs': { title: '操作日志' },
  '/knowledge/entrypoints': { title: '扫描入口', parent: '知识查询' },
  '/knowledge/hierarchy': { title: '模块层级', parent: '知识查询' },
  '/knowledge/documents': { title: '知识文档', parent: '知识查询' },
  '/knowledge/browse': { title: '知识文档', parent: '知识查询' },
};

/**
 * 根据 pathname 计算标签显示标题。
 *  - 完全匹配 → 静态标题
 *  - 动态路由 → 父标题 + ID（"/tasks/123" → "任务详情 #123"）
 *  - 未知路径 → 路径本身去前导斜杠
 */
export function getPageTitle(pathname: string): string {
  if (PATH_META[pathname]) return PATH_META[pathname].title;

  // 动态任务详情 /tasks/:id
  const taskMatch = pathname.match(/^\/tasks\/(\d+)$/);
  if (taskMatch) return `任务详情 #${taskMatch[1]}`;

  const entryReviewMatch = pathname.match(/^\/tasks\/entrypoint-review\/(\d+)$/);
  if (entryReviewMatch) return `入口复核 #${entryReviewMatch[1]}`;

  const hierarchyReviewMatch = pathname.match(/^\/tasks\/hierarchy-review\/(\d+)$/);
  if (hierarchyReviewMatch) return `模块层级复核 #${hierarchyReviewMatch[1]}`;

  // 知识复核详情 /drafts/:taskId
  const draftReviewMatch = pathname.match(/^\/drafts\/(\d+)$/);
  if (draftReviewMatch) return `知识复核 #${draftReviewMatch[1]}`;

  // 定时任务编辑 /tasks/jobs/:id/edit
  const jobEditMatch = pathname.match(/^\/tasks\/jobs\/(\d+)\/edit$/);
  if (jobEditMatch) return `编辑定时任务 #${jobEditMatch[1]}`;

  // 定时任务详情 /tasks/jobs/:id
  const jobDetailMatch = pathname.match(/^\/tasks\/jobs\/(\d+)$/);
  if (jobDetailMatch) return `定时任务 #${jobDetailMatch[1]}`;

  // 未知路径：用 pathname 兜底
  return pathname.replace(/^\//, '') || '未知页面';
}

/**
 * 菜单 key 集合：哪些路径在侧栏里点得到 → 这些路径在 tab 里可以靠 PATH_META 取到标题。
 */
export const isSidebarRoute = (pathname: string): boolean => {
  if (PATH_META[pathname]) return true;
  if (/^\/tasks\/(\d+)$/.test(pathname)) return true;
  if (/^\/tasks\/entrypoint-review\/(\d+)$/.test(pathname)) return true;
  if (/^\/tasks\/hierarchy-review\/(\d+)$/.test(pathname)) return true;
  if (/^\/drafts\/(\d+)$/.test(pathname)) return true;
  if (/^\/tasks\/jobs\/(\d+)(?:\/edit)?$/.test(pathname)) return true;
  return false;
};
