package com.company.codeinsight.common.util;

import java.util.regex.Pattern;

/**
 * 从大模型原始输出中提取可解析的 JSON 文本。
 * <p>兼容 MiniMax 等模型返回的 {@code <think>}、Markdown 代码围栏等包裹格式。</p>
 */
public final class AiResponseJsonExtractor {

    private static final Pattern THINKING_BLOCK = Pattern.compile(
            "<\\s*(?:redacted_thinking|think|thinking)\\s*>[\\s\\S]*?<\\s*/\\s*(?:redacted_thinking|think|thinking)\\s*>",
            Pattern.CASE_INSENSITIVE
    );

    private AiResponseJsonExtractor() {
    }

    /**
     * 剥离模型思考链等无关包裹，保留正文（Markdown / JSON 均适用）。
     */
    public static String stripModelArtifacts(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text;
        String previous;
        do {
            previous = cleaned;
            cleaned = THINKING_BLOCK.matcher(cleaned).replaceAll("");
            cleaned = cleaned.replaceAll("(?is)<\\s*/?\\s*(?:redacted_thinking|think|thinking)\\s*>", "");
        } while (!cleaned.equals(previous));
        return cleaned.trim();
    }

    /**
     * 去掉 Markdown JSON 代码围栏。
     */
    public static String stripCodeFence(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNewline = t.indexOf('\n');
        if (firstNewline > 0) {
            t = t.substring(firstNewline + 1);
        }
        if (t.endsWith("```")) {
            t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    /**
     * 从混合文本中提取首个完整 JSON 对象或数组，供 Jackson 解析。
     */
    public static String extractJsonPayload(String text) {
        String cleaned = stripCodeFence(stripModelArtifacts(text));
        if (cleaned.isEmpty()) {
            return cleaned;
        }
        if (cleaned.charAt(0) == '{' || cleaned.charAt(0) == '[') {
            return cleaned;
        }
        int objectStart = cleaned.indexOf('{');
        int arrayStart = cleaned.indexOf('[');
        int start = -1;
        if (objectStart >= 0 && arrayStart >= 0) {
            start = Math.min(objectStart, arrayStart);
        } else if (objectStart >= 0) {
            start = objectStart;
        } else if (arrayStart >= 0) {
            start = arrayStart;
        }
        if (start < 0) {
            return cleaned;
        }
        int end = findBalancedEnd(cleaned, start);
        if (end < 0) {
            return cleaned.substring(start).trim();
        }
        return cleaned.substring(start, end + 1).trim();
    }

    private static int findBalancedEnd(String text, int start) {
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
