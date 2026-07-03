import type { AiModel } from '../../../types';

export {
  BRACE_PLACEHOLDER_PATTERN,
  DOLLAR_PLACEHOLDER_PATTERN,
  JAVA_CODE_VAR_NAMES,
  AUTO_EXTRACTED_VARS,
  extractPlaceholders,
  formatPlaceholderToken,
  isFilledBySampleCode,
  substitutePlaceholders,
  formatVariableLabel,
} from '../../../utils/promptPlaceholders';

export const getPreferredModel = (models: AiModel[]): AiModel | undefined => {
  const availableModels = models.filter((model) => model.status !== 0 && model.hasApiKey);
  return availableModels.find((model) => model.isDefault === 'true') ?? availableModels[0] ?? models.find((model) => model.status !== 0) ?? models[0];
};

export const getModelOptionDisabled = (model: AiModel): boolean => {
  return model.status === 0 || !model.hasApiKey;
};

export const formatDateTime = (value?: string): string => {
  return value ? new Date(value).toLocaleString() : '-';
};
