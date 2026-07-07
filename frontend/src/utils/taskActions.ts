/** 允许从列表物理删除的任务状态 */
export const DELETABLE_TASK_STATUSES = [
  'DRAFT',
  'PENDING',
  'FAILED',
  'CANCELLED',
  'ARCHIVED',
] as const;

export function isTaskDeletable(status: string): boolean {
  return (DELETABLE_TASK_STATUSES as readonly string[]).includes(status);
}
