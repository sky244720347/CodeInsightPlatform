package com.company.codeinsight.modules.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;

/**
 * 知识推送与版本管理服务接口
 * 负责定义生成正式知识版本、推送到目标 Git 仓库、打包导出 ZIP 二进制压缩包、以及分页查询等业务规则。
 */
public interface KnowledgeService {

    /**
     * 将已确认草稿组装为 {@code workspaces/task_{id}/docs/code-insight} 发布包（不含源码类索引）。
     */
    void assemblePublishPackage(Long taskId);

    /**
     * 按仓库已有 {@code v1,v2,…} 取最大数字 +1；无则 {@code v1}。
     */
    String nextSimpleVersionNum(Long repositoryId);

    /**
     * 生成知识版本记录；若发布包尚未组装则先组装。推送只依赖 docs/code-insight。
     *
     * @param taskId      复核完成的任务 ID
     * @param versionNum  版本号（如 v1）
     * @param confirmedBy 操作确认用户名
     * @return 刚被创建的知识版本对象
     */
    KnowledgeVersion createVersion(Long taskId, String versionNum, String confirmedBy);

    /**
     * 将该版本对应的所有 Markdown 知识文档压缩并导出为 ZIP 二进制流
     *
     * @param versionId 知识版本 ID
     * @return ZIP 文件的字节数组载荷
     */
    byte[] exportZip(Long versionId);

    /**
     * 分页条件查询知识发布版本列表
     */
    Page<KnowledgeVersion> listVersionsPage(int current, int size, Long systemId, Long repositoryId);
}

