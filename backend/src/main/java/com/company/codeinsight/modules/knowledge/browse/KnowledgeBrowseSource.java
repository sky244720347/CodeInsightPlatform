package com.company.codeinsight.modules.knowledge.browse;

import java.util.List;

/**
 * 知识查看的索引/清单文件数据源抽象。
 * <p>当前实现为 {@link TempReposKnowledgeBrowseSource}（从 temp_repos/task_{id}/docs/code-insight/ 读），未来 UFSP 上线后只需新增一个
 * {@code UfspKnowledgeBrowseSource implements KnowledgeBrowseSource} 并加 {@code @ConditionalOnProperty(name="code-insight.browse.source", havingValue="ufsp")}，
 * 不动 service / controller。</p>
 */
public interface KnowledgeBrowseSource {

    /**
     * 列出指定 task 生成的索引 / 清单文件。
     * <p>覆盖 {@code docs/code-insight/{index,modules,meta}/} 三类。文件不存在（temp_repos 被清理）时返回空列表，不抛异常。</p>
     *
     * @param taskId 任务 ID
     * @return 文件条目（含相对 docs/code-insight/ 的路径）
     */
    List<IndexFileEntry> listIndexFiles(Long taskId);

    /**
     * 读取指定索引 / 清单文件的 UTF-8 文本内容。
     * <p>由实现类做路径白名单校验防止越权；文件不存在 / 超过大小上限时抛 {@code BusinessException}。</p>
     *
     * @param taskId   任务 ID
     * @param filePath 相对 docs/code-insight/ 的路径（如 "index/module-index.md"）
     * @return 文件内容（UTF-8 字符串）
     */
    String readIndexFile(Long taskId, String filePath);

    /**
     * 单个索引 / 清单文件条目。
     */
    record IndexFileEntry(String relativePath, String type, long size, java.time.LocalDateTime updatedDate) {}
}