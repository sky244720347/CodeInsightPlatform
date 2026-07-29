package com.company.codeinsight.modules.ai.support;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档生成源码预算裁剪（方案 A）：按 class 块保序保留，超长块截断。
 */
public final class DocSourceBudgetShrinker {

    private static final Pattern CLASS_HEADER = Pattern.compile("(?m)^// === Class: .* ===\\s*$");

    private DocSourceBudgetShrinker() {
    }

    public record ShrinkResult(String text, int fromChars, int toChars, int keptBlocks, int totalBlocks, boolean changed) {
        public String summary() {
            return String.format("chars=%d→%d classes=%d/%d", fromChars, toChars, keptBlocks, totalBlocks);
        }
    }

    /**
     * 将源码裁到不超过 {@code maxChars}；单块不超过 {@code maxBlockChars}。
     * 若已在预算内且块均不超长，返回 {@code changed=false}。
     */
    public static ShrinkResult shrink(String source, int maxChars, int maxBlockChars) {
        String input = source == null ? "" : source;
        int from = input.length();
        if (!StringUtils.hasText(input)) {
            return new ShrinkResult("", 0, 0, 0, 0, false);
        }
        int safeMax = Math.max(1_000, maxChars);
        int safeBlock = Math.max(500, maxBlockChars);

        List<ClassBlock> blocks = splitClassBlocks(input);
        int totalBlocks = blocks.size();
        if (totalBlocks == 0) {
            String truncated = truncateRaw(input, safeMax);
            boolean changed = truncated.length() != from;
            if (changed) {
                truncated = truncated + "\n\n[已截断：raw chars=" + from + "→" + truncated.length() + "]\n";
            }
            return new ShrinkResult(truncated, from, truncated.length(), 0, 0, changed);
        }

        List<ClassBlock> normalized = new ArrayList<>(totalBlocks);
        boolean blockTruncated = false;
        for (ClassBlock b : blocks) {
            if (b.body.length() > safeBlock) {
                String body = b.body.substring(0, safeBlock)
                        + "\n// ... truncated (" + b.body.length() + "→" + safeBlock + " chars) ...\n";
                normalized.add(new ClassBlock(b.header, body));
                blockTruncated = true;
            } else {
                normalized.add(b);
            }
        }

        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (ClassBlock b : normalized) {
            String piece = b.header + "\n" + b.body;
            if (!b.body.endsWith("\n")) {
                piece = piece + "\n";
            }
            piece = piece + "\n";
            if (kept > 0 && sb.length() + piece.length() > safeMax) {
                break;
            }
            if (kept == 0 && piece.length() > safeMax) {
                // 单块仍超总预算：再硬截
                String hard = truncateRaw(piece, safeMax);
                sb.append(hard);
                kept = 1;
                blockTruncated = true;
                break;
            }
            sb.append(piece);
            kept++;
        }

        boolean dropped = kept < totalBlocks;
        boolean changed = blockTruncated || dropped;
        if (!changed) {
            // 未裁剪：原样返回，避免因换行归一化误标 changed
            return new ShrinkResult(input, from, from, totalBlocks, totalBlocks, false);
        }
        sb.append("[已截断：classes=").append(kept).append("/").append(totalBlocks)
                .append("，chars=").append(from).append("→").append(sb.length()).append("]\n");
        return new ShrinkResult(sb.toString(), from, sb.length(), kept, totalBlocks, true);
    }

    /**
     * 相对当前源码再压一档（用于 AI 失败重试）。
     */
    public static ShrinkResult shrinkFurther(String source, int maxBlockChars, double shrinkFactor) {
        String input = source == null ? "" : source;
        double factor = shrinkFactor > 0 && shrinkFactor < 1 ? shrinkFactor : 0.5;
        int target = Math.max(2_000, (int) (input.length() * factor));
        return shrink(input, target, maxBlockChars);
    }

    private static List<ClassBlock> splitClassBlocks(String source) {
        Matcher m = CLASS_HEADER.matcher(source);
        List<Integer> starts = new ArrayList<>();
        List<String> headers = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            headers.add(m.group().trim());
        }
        List<ClassBlock> blocks = new ArrayList<>();
        if (starts.isEmpty()) {
            return blocks;
        }
        // 头部之前的前言忽略（通常为空）
        for (int i = 0; i < starts.size(); i++) {
            int headerStart = starts.get(i);
            int bodyStart = source.indexOf('\n', headerStart);
            if (bodyStart < 0) {
                bodyStart = source.length();
            } else {
                bodyStart++;
            }
            int bodyEnd = (i + 1 < starts.size()) ? starts.get(i + 1) : source.length();
            String body = source.substring(bodyStart, bodyEnd);
            // 去掉块末多余空行，组装时再补
            while (body.endsWith("\n")) {
                body = body.substring(0, body.length() - 1);
            }
            blocks.add(new ClassBlock(headers.get(i), body));
        }
        return blocks;
    }

    private static String truncateRaw(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n// ... truncated ...\n";
    }

    private record ClassBlock(String header, String body) {
    }
}
