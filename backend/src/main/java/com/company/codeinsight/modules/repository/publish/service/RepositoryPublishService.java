package com.company.codeinsight.modules.repository.publish.service;

import com.company.codeinsight.modules.repository.publish.dto.RepositoryPublishSnapshotView;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryPublishSnapshot;

import java.nio.file.Path;
import java.util.List;

/**
 * 推送成功后「应用到仓库」：覆盖仓库扫描配置、提示词、入口与模块层级，并留存版本快照供回滚。
 */
public interface RepositoryPublishService {

    /**
     * 从任务产物应用到仓库并写入发布快照（推送 SUCCESS 后调用）。
     */
    RepositoryPublishSnapshot applyFromTask(Long taskId, Long versionId, String operator);

    /**
     * 按知识版本回滚仓库发布态（恢复该版本推送时的快照）。
     */
    RepositoryPublishSnapshot rollbackToVersion(Long repositoryId, Long versionId, String operator);

    /**
     * 按知识版本 ID 回滚仓库发布态（从快照表解析 repositoryId）。
     */
    RepositoryPublishSnapshot rollbackToVersionByVersionId(Long versionId, String operator);

    /** 仓库发布历史（按时间降序，含 release 目录与生效标记） */
    List<RepositoryPublishSnapshotView> listSnapshots(Long repositoryId);

    /** 导出 artifacts 到 NAS 发布目录 */
    void exportArtifactsToRelease(Path releaseDir, RepositoryPublishSnapshot snapshot);
}
