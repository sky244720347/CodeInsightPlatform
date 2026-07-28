package com.company.codeinsight.modules.ai.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 功能文档生成的源码包：prompt 拼接正文与代码来源 refs 同源。
 */
@Data
public class FunctionSourceBundle {

    public static final String REF_KIND_ROOT = "ROOT";
    public static final String REF_KIND_REACHABLE = "REACHABLE";

    /** 喂给 AI 的 java 代码块（含 // === Class === 头） */
    private String promptText = "";

    /** 与 prompt 同源的来源引用（按 BFS 发现序） */
    private List<RefItem> refs = new ArrayList<>();

    private Set<String> rootSignatures = new LinkedHashSet<>();

    private String functionNodeId;

    public boolean hasPromptText() {
        return promptText != null && !promptText.isBlank();
    }

    @Data
    public static class RefItem {
        private String filePath;
        private String className;
        private String methodSignature;
        private Integer startLine;
        private Integer endLine;
        /** ROOT | REACHABLE */
        private String refKind;
        private int bfsOrder;
    }
}
