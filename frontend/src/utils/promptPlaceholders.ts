/**
 * 提示词模板占位符工具（与 PromptTemplateLoader 一致）。
 *
 * 抽到 utils/ 是为了让「默认提示词试跑」与「仓库配置提示词试跑」共用同一套占位符识别
 * 与替换逻辑。`pages/basic/prompts/utils.ts` 会从本文件 re-export 以保持向后兼容。
 */

/** 流水线模板占位符：{java_code}、{module_hierarchy.json}、{公共模块名称} 等 */
export const BRACE_PLACEHOLDER_PATTERN =
  /\{([a-zA-Z_一-鿿][a-zA-Z0-9_.一-鿿-]*)\}/g;

/** 部分旧模板/文档仍使用 ${var_name} 形式 */
export const DOLLAR_PLACEHOLDER_PATTERN = /\$\{([a-zA-Z_][a-zA-Z0-9_]*)\}/g;

/** 试跑时可用下方 Java 示例代码自动填充的变量名 */
export const JAVA_CODE_VAR_NAMES = new Set(['source_code', 'java_code', 'java.code']);

/** 后端试跑接口会从 sampleCode 解析的变量（前端仅作展示提示） */
export const AUTO_EXTRACTED_VARS = new Set(['class_name', 'method_name', ...JAVA_CODE_VAR_NAMES]);

const PLACEHOLDER_NAME_RE = /^[a-zA-Z_一-鿿][a-zA-Z0-9_.一-鿿-]*$/;

function collectPlaceholderNames(template: string, pattern: RegExp): string[] {
  const seen = new Set<string>();
  const order: string[] = [];
  const re = new RegExp(pattern.source, 'g');
  let m: RegExpExecArray | null;
  while ((m = re.exec(template)) !== null) {
    const name = m[1];
    if (!PLACEHOLDER_NAME_RE.test(name) || seen.has(name)) continue;
    seen.add(name);
    order.push(name);
  }
  return order;
}

/**
 * 从提示词模板抽取占位符变量名。
 * 支持 `{java_code}` / `{business_knowledge.md}` 与 `${code}` 两种写法，去重并按首次出现顺序返回。
 */
export const extractPlaceholders = (template?: string): string[] => {
  if (!template) return [];
  const braceNames = collectPlaceholderNames(template, BRACE_PLACEHOLDER_PATTERN);
  const dollarNames = collectPlaceholderNames(template, DOLLAR_PLACEHOLDER_PATTERN);
  const seen = new Set(braceNames);
  const merged = [...braceNames];
  for (const name of dollarNames) {
    if (!seen.has(name)) {
      seen.add(name);
      merged.push(name);
    }
  }
  return merged;
};

/** UI 展示占位符原文形式（识别 ${var} 优先） */
export const formatPlaceholderToken = (name: string, template?: string): string => {
  if (template?.includes(`\${${name}}`)) return `\${${name}}`;
  return `{${name}}`;
};

export const isFilledBySampleCode = (name: string, sampleCode: string): boolean =>
  JAVA_CODE_VAR_NAMES.has(name) && !!sampleCode.trim();

/**
 * 将模板中的 {var} / ${var} 替换为 vars 中的值；缺失时保留原占位符。
 */
export const substitutePlaceholders = (
  template: string | undefined,
  vars: Record<string, string | undefined>,
): string => {
  if (!template) return '';
  let result = template;
  for (const [name, value] of Object.entries(vars)) {
    if (value == null) continue;
    result = result.split(`{${name}}`).join(value);
    result = result.split(`\${${name}}`).join(value);
  }
  return result;
};

/**
 * 把 `snake_case` / `camelCase` 转成中文友好的 Label：如 class_name → Class Name
 */
export const formatVariableLabel = (name: string): string => {
  const withSpaces = name.replace(/[_-]+/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2');
  return withSpaces.replace(/\b\w/g, (c) => c.toUpperCase());
};
