package com.company.codeinsight.modules.repository.publish.service.impl;

import com.company.codeinsight.common.config.CodeInsightEnvProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.common.storage.StorageProperties;
import com.company.codeinsight.common.util.DataUriUtil;
import com.company.codeinsight.modules.entrypoint.mapper.EntrypointMapper;
import com.company.codeinsight.modules.hierarchy.mapper.ModuleHierarchyNodeMapper;
import com.company.codeinsight.modules.knowledge.browse.RepositoryActiveKnowledgeResolver;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.prompt.mapper.DecompilePromptMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryPublishSnapshot;
import com.company.codeinsight.modules.repository.publish.mapper.RepositoryEntrypointMapper;
import com.company.codeinsight.modules.repository.publish.mapper.RepositoryModuleHierarchyMapper;
import com.company.codeinsight.modules.repository.publish.mapper.RepositoryPublishSnapshotMapper;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryPublishServiceRollbackTest {

    @Mock private DecompileTaskMapper taskMapper;
    @Mock private KnowledgeVersionMapper versionMapper;
    @Mock private CodeRepositoryMapper repositoryMapper;
    @Mock private EntrypointMapper entrypointMapper;
    @Mock private ModuleHierarchyNodeMapper moduleHierarchyNodeMapper;
    @Mock private RepositoryEntrypointMapper repositoryEntrypointMapper;
    @Mock private RepositoryModuleHierarchyMapper repositoryModuleHierarchyMapper;
    @Mock private RepositoryPublishSnapshotMapper snapshotMapper;
    @Mock private DecompilePromptMapper promptMapper;

    private EnvStorageResolver storageResolver;
    private RepositoryActiveKnowledgeResolver activeKnowledgeResolver;
    private RepositoryPublishServiceImpl service;

    @TempDir
    Path tempRoot;

    @BeforeEach
    void setUp() {
        storageResolver = new EnvStorageResolver(new CodeInsightEnvProperties(), new StorageProperties());
        storageResolver.overrideRootsForTest(
                tempRoot.resolve("data"),
                tempRoot.resolve("ws"),
                tempRoot.resolve("releases"));
        activeKnowledgeResolver = new RepositoryActiveKnowledgeResolver(repositoryMapper, versionMapper, storageResolver);
        service = new RepositoryPublishServiceImpl(
                taskMapper,
                versionMapper,
                repositoryMapper,
                entrypointMapper,
                moduleHierarchyNodeMapper,
                repositoryEntrypointMapper,
                repositoryModuleHierarchyMapper,
                snapshotMapper,
                promptMapper,
                new ObjectMapper(),
                activeKnowledgeResolver,
                storageResolver
        );
    }

    @Test
    void rollbackToVersion_syncsPublishedBaselineCommit() throws Exception {
        CodeRepository repo = new CodeRepository();
        repo.setId(10L);
        repo.setLastPublishedVersionId(100L);
        repo.setLastCommitId("commit-current");
        when(repositoryMapper.selectById(10L)).thenReturn(repo);

        KnowledgeVersion version = new KnowledgeVersion();
        version.setId(99L);
        version.setRepositoryId(10L);
        version.setSystemId(1L);
        version.setVersionNum("v1.0.0");
        version.setStatus("PUSHED");
        version.setSourceCommit("commit-rollback-target");
        when(versionMapper.selectById(99L)).thenReturn(version);

        String epUri = DataUriUtil.buildSnapshotEntrypointsUri(10L, 99L);
        String hierUri = DataUriUtil.buildSnapshotModuleHierarchyUri(10L, 99L);
        DataUriUtil.writeUtf8(epUri, "[]", storageResolver);
        DataUriUtil.writeUtf8(hierUri, "[]", storageResolver);

        RepositoryPublishSnapshot snapshot = new RepositoryPublishSnapshot();
        snapshot.setRepositoryId(10L);
        snapshot.setVersionId(99L);
        snapshot.setTaskId(7L);
        snapshot.setEntrypointsUri(epUri);
        snapshot.setModuleHierarchyUri(hierUri);
        when(snapshotMapper.selectByVersionId(99L)).thenReturn(snapshot);

        Path releaseDir = storageResolver.releaseDir(1L, 10L, "v1.0.0");
        Files.createDirectories(releaseDir);

        service.rollbackToVersion(10L, 99L, "tester");

        org.mockito.ArgumentCaptor<CodeRepository> captor = org.mockito.ArgumentCaptor.forClass(CodeRepository.class);
        verify(repositoryMapper).updateById(captor.capture());
        Assertions.assertEquals("commit-rollback-target", captor.getValue().getLastCommitId());
        Assertions.assertEquals(99L, captor.getValue().getLastPublishedVersionId());
    }

    @Test
    void rollbackToVersion_rejectsWhenAlreadyActive() {
        CodeRepository repo = new CodeRepository();
        repo.setId(10L);
        repo.setLastPublishedVersionId(99L);
        when(repositoryMapper.selectById(10L)).thenReturn(repo);

        KnowledgeVersion version = new KnowledgeVersion();
        version.setId(99L);
        version.setRepositoryId(10L);
        version.setStatus("PUSHED");
        when(versionMapper.selectById(99L)).thenReturn(version);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> service.rollbackToVersion(10L, 99L, "tester"));
        Assertions.assertTrue(ex.getMessage().contains("已是当前生效版本"));
    }

    @Test
    void rollbackToVersion_rejectsWhenReleaseDirMissing() {
        CodeRepository repo = new CodeRepository();
        repo.setId(10L);
        when(repositoryMapper.selectById(10L)).thenReturn(repo);

        KnowledgeVersion version = new KnowledgeVersion();
        version.setId(99L);
        version.setRepositoryId(10L);
        version.setSystemId(1L);
        version.setVersionNum("v1.0.0");
        version.setStatus("PUSHED");
        when(versionMapper.selectById(99L)).thenReturn(version);

        String epUri = DataUriUtil.buildSnapshotEntrypointsUri(10L, 99L);
        String hierUri = DataUriUtil.buildSnapshotModuleHierarchyUri(10L, 99L);
        DataUriUtil.writeUtf8(epUri, "[]", storageResolver);
        DataUriUtil.writeUtf8(hierUri, "[]", storageResolver);

        RepositoryPublishSnapshot snapshot = new RepositoryPublishSnapshot();
        snapshot.setRepositoryId(10L);
        snapshot.setVersionId(99L);
        snapshot.setEntrypointsUri(epUri);
        snapshot.setModuleHierarchyUri(hierUri);
        when(snapshotMapper.selectByVersionId(99L)).thenReturn(snapshot);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> service.rollbackToVersion(10L, 99L, "tester"));
        Assertions.assertTrue(ex.getMessage().contains("无发布产物目录"));
    }
}
