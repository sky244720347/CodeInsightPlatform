/**
 * 将毫秒耗时格式化为中文时分秒，例如：`1时2分3秒` / `5分12秒` / `8秒`。
 * 无效或非正数返回 fallback（默认 `-`）。
 */
export function formatDurationMs(ms?: number | null, fallback = '-'): string {
  if (ms == null || !Number.isFinite(ms) || ms <= 0) return fallback;
  const totalSec = Math.round(ms / 1000);
  const hours = Math.floor(totalSec / 3600);
  const minutes = Math.floor((totalSec % 3600) / 60);
  const seconds = totalSec % 60;
  if (hours > 0) return `${hours}时${minutes}分${seconds}秒`;
  if (minutes > 0) return `${minutes}分${seconds}秒`;
  return `${seconds}秒`;
}
