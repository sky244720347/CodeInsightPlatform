package com.company.codeinsight.modules.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;

/**
 * 知识推送与版本管理服务接口
 * 负责定义生成正式知识版本、推送到目标 Git 仓库、打包导出 ZIP 二进制压缩包、以及分页查询等业务规则。
 */
public interface KnowledgeService {

    /**
     * 生成知识版本记录，并在磁盘上编译出完整的 Markdown 规范化文档目录结构
     *
     * @param taskId      复核完成的任务 ID
     * @param versionNum  自定义的版本号名称
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

