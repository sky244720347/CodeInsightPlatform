package com.company.codeinsight.modules.repository.publish.service;

import com.company.codeinsight.modules.repository.publish.entity.RepositoryEntrypointEntity;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryModuleHierarchyNode;

import java.util.List;
import java.util.Optional;

/**
 * 读取仓库已发布产物（供远期知识查看 / 任务复用预留）。
 */
public interface RepositoryArtifactService {

    Optional<String> getPublishedScanConfig(Long repositoryId);

    List<RepositoryEntrypointEntity> listPublishedEntrypoints(Long repositoryId);

    List<RepositoryModuleHierarchyNode> listPublishedHierarchy(Long repositoryId);
}
