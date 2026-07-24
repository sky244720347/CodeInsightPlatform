package com.company.codeinsight.modules.knowledge.service;

import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 知识库三级索引生成服务
 * 基于 ModuleHierarchy DTO（Module/SubModule/Function）+ KnowledgeDraft 列表，
 * 生成 docs/code-insight/module-index.md 与 meta/document-index.md。
 */
public interface KnowledgeIndexService {

    /**
     * 生成三级索引 md 文件（覆盖写入）
     * 索引内容按"模块 → 子模块 → 功能"三层 Markdown 表格，每个功能链接到对应 module.md。
     *
     * @param docsPath   module-index.md 所在目录（通常 docs/code-insight/）
     * @param hierarchy  ModuleHierarchy DTO（项 2 产出，含三级结构）
     * @param drafts     任务下所有 KnowledgeDraft（用于校验 module → md 文件存在性）
     * @return 生成的 module-index.md 完整路径
     * @throws IOException 写文件失败时
     */
    Path generateModuleIndex(Path docsPath, ModuleHierarchy hierarchy, List<KnowledgeDraft> drafts) throws IOException;

    /**
     * 生成知识文档路径索引（覆盖写入 {@code meta/document-index.md}）。
     * <p>路径与 ZIP 导出一致：相对 {@code code-insight/} 的扁平文件名
     * {@code modules/{breadcrumb 空白与 / () 换成 _}.md}。</p>
     *
     * @param docsPath   docs/code-insight/ 根目录
     * @param hierarchy  模块层级 DTO
     * @param drafts     草稿列表（hierarchy 为空时降级为按 draft 罗列）
     * @return 生成的 document-index.md 完整路径
     * @throws IOException 写文件失败时
     */
    Path generateDocumentIndex(Path docsPath, ModuleHierarchy hierarchy, List<KnowledgeDraft> drafts) throws IOException;
}