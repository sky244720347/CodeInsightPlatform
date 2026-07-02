package com.company.codeinsight.modules.prompt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提示词从 classpath 资源同步到 DB 的结果 DTO。
 * <p>用于 {@code POST /prompts/sync-from-resource} 接口：
 * 业务背景是 classpath 中的 {@code analyze_prompt.md} / {@code module_doc_prompt.md}
 * 在工程升级后与 DB 里旧的 RELEASED 提示词出现不一致；
 * 运维调用此接口可一键把新版本内容以"新版本号 + RELEASED + 默认"的方式落表，
 * 旧版本自动归档。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncPromptFromResourceResultDto {

    /** 是否真的写入了新版本（content 与现有默认一致时为 false） */
    private boolean changed;

    /** 新写入的提示词 ID（changed = true 时非空） */
    private Long newPromptId;

    /** 被取代的旧默认提示词 ID（无旧默认或第一次同步时为 null） */
    private Long oldPromptId;

    /** 同步结果的原因（"content changed" / "content identical" / "first seed"） */
    private String reason;

    /** 新版本的 version 字段 */
    private Integer newVersion;

    /** 来源 classpath 资源路径（仅作审计） */
    private String resourcePath;
}
