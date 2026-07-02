import type { EntryScanConfig, EntryScanTypeKey, TypeIncludeRules } from '../types';

export const ENTRY_SCAN_TYPE_KEYS: EntryScanTypeKey[] = [
  'CONTROLLER',
  'SCHEDULED_JOB',
  'MQ_LISTENER',
  'OTHER',
];

export const ENTRY_SCAN_TYPE_LABELS: Record<EntryScanTypeKey, string> = {
  CONTROLLER: 'Controller',
  SCHEDULED_JOB: 'Job',
  MQ_LISTENER: 'MQ',
  OTHER: '其他',
};

const DEFAULT_EXCLUDE_CLASSPATHS = ['**/*Test', '**/*Tests', '**/*TestCase'];

export const defaultIncludesByType = (): Record<EntryScanTypeKey, TypeIncludeRules> => ({
  CONTROLLER: {
    includeAnnotations: ['RestController', 'Controller', 'RequestMapping'],
    includeClasspaths: [],
    includeExtends: [],
  },
  SCHEDULED_JOB: {
    includeAnnotations: ['Scheduled', 'EnableScheduling'],
    includeClasspaths: [],
    includeExtends: [
      'org.springframework.boot.CommandLineRunner',
      'org.springframework.boot.ApplicationRunner',
    ],
  },
  MQ_LISTENER: {
    includeAnnotations: ['RabbitListener', 'KafkaListener', 'JmsListener', 'RocketMQMessageListener'],
    includeClasspaths: [],
    includeExtends: [],
  },
  OTHER: {
    includeAnnotations: [],
    includeClasspaths: [],
    includeExtends: [],
  },
});

/** 新 schema 默认配置；旧 flat JSON 会在后端 decode 时替换为默认四套 */
export const buildScanConfigWithDefaults = (
  config?: EntryScanConfig | Record<string, unknown> | null,
): EntryScanConfig => {
  const raw = (config || {}) as Record<string, unknown>;
  if (raw.includeAnnotations && !raw.includesByType) {
    return {
      includesByType: defaultIncludesByType(),
      excludeClasspaths: [...DEFAULT_EXCLUDE_CLASSPATHS],
      excludePackages: [],
      excludeAnnotations: [],
      excludeTargets: [],
    };
  }
  const base = config as EntryScanConfig | undefined;
  const defaults = defaultIncludesByType();
  const mergedIncludes = { ...defaults };
  if (base?.includesByType) {
    for (const key of ENTRY_SCAN_TYPE_KEYS) {
      const from = base.includesByType[key];
      if (from) {
        mergedIncludes[key] = {
          includeAnnotations: from.includeAnnotations ?? defaults[key].includeAnnotations ?? [],
          includeClasspaths: from.includeClasspaths ?? [],
          includeExtends: from.includeExtends ?? [],
        };
      }
    }
  }
  return {
    includesByType: mergedIncludes,
    excludeClasspaths:
      base?.excludeClasspaths && base.excludeClasspaths.length > 0
        ? [...base.excludeClasspaths]
        : [...DEFAULT_EXCLUDE_CLASSPATHS],
    excludePackages: base?.excludePackages ? [...base.excludePackages] : [],
    excludeAnnotations: base?.excludeAnnotations ? [...base.excludeAnnotations] : [],
    excludeTargets: base?.excludeTargets ? [...base.excludeTargets] : [],
  };
};
