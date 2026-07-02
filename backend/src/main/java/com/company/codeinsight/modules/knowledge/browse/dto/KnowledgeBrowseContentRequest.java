package com.company.codeinsight.modules.knowledge.browse.dto;

import lombok.Data;

/**
 * 知识查看内容读取入参。
 * <p>根据 type 决定读取路径：</p>
 * <ul>
 *   <li>DRAFT：通过 {@code draftId} 读取 {@code ci_knowledge_draft.content_uri} 指向的物理 Markdown</li>
 *   <li>INDEX / MANIFEST：通过 {@code taskId + filePath}（相对 docs/code-insight/）读取 temp_repos 下的文件</li>
 *   <li>已发布产物：通过 {@code contentUri}（release:...）直接读取 NAS / 本地 releases 目录</li>
 * </ul>
 */
@Data
public class KnowledgeBrowseContentRequest {

    /** 必填：DRAFT / INDEX / MANIFEST */
    private String type;

    /** type=DRAFT 时必填：草稿 ID（ci_knowledge_draft.id） */
    private Long id;

    /** 已发布产物 URI（release:sys:repo:ver/path），优先于 taskId+filePath */
    private String contentUri;

    /** type=INDEX/MANIFEST 时必填：任务 ID（temp_repos/task_{id}） */
    private Long taskId;

    /** type=INDEX/MANIFEST 时必填：相对 docs/code-insight/ 的路径（如 "index/module-index.md"） */
    private String filePath;
}