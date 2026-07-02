import type { EntryScanConfig, Repository } from '../../types';
import { buildScanConfigWithDefaults } from '../../utils/scanConfigDefaults';

export { buildScanConfigWithDefaults } from '../../utils/scanConfigDefaults';

export const parseRepoEntryScanConfig = (repo: Repository | undefined): EntryScanConfig => {
  if (!repo?.entryScanConfig) return buildScanConfigWithDefaults(undefined);
  if (typeof repo.entryScanConfig === 'string') {
    try {
      return buildScanConfigWithDefaults(JSON.parse(repo.entryScanConfig));
    } catch {
      return buildScanConfigWithDefaults(undefined);
    }
  }
  return buildScanConfigWithDefaults(repo.entryScanConfig);
};
