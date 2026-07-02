package com.company.codeinsight.modules.knowledge.browse;

import com.company.codeinsight.common.storage.StorageProperties;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryActiveKnowledgeResolverTest {

    @Mock
    private CodeRepositoryMapper repositoryMapper;

    @Mock
    private KnowledgeVersionMapper versionMapper;

    private StorageProperties storageProperties;
    private RepositoryActiveKnowledgeResolver resolver;

    @TempDir
    Path tempRoot;

    @BeforeEach
    void setUp() {
        storageProperties = new StorageProperties();
        storageProperties.setMode(com.company.codeinsight.common.storage.StorageMode.LOCAL);
        storageProperties.setLocalPath(tempRoot.toString());
        resolver = new RepositoryActiveKnowledgeResolver(repositoryMapper, versionMapper, storageProperties);
    }

    @Test
    void resolve_emptyWhenNoActivePointer() {
        CodeRepository repo = new CodeRepository();
        repo.setId(10L);
        repo.setLastPublishedVersionId(null);
        when(repositoryMapper.selectById(10L)).thenReturn(repo);

        Assertions.assertTrue(resolver.resolve(10L).isEmpty());
    }

    @Test
    void resolve_emptyWhenVersionNotPushed() {
        CodeRepository repo = new CodeRepository();
        repo.setId(10L);
        repo.setLastPublishedVersionId(99L);
        when(repositoryMapper.selectById(10L)).thenReturn(repo);

        KnowledgeVersion version = new KnowledgeVersion();
        version.setId(99L);
        version.setStatus("DRAFT");
        when(versionMapper.selectById(99L)).thenReturn(version);

        Assertions.assertTrue(resolver.resolve(10L).isEmpty());
    }

    @Test
    void resolve_populatesContextWhenReleaseDirExists() throws Exception {
        CodeRepository repo = new CodeRepository();
        repo.setId(10L);
        repo.setLastPublishedVersionId(99L);
        when(repositoryMapper.selectById(10L)).thenReturn(repo);

        KnowledgeVersion version = new KnowledgeVersion();
        version.setId(99L);
        version.setSystemId(1L);
        version.setRepositoryId(10L);
        version.setTaskId(5L);
        version.setVersionNum("v1.0.0");
        version.setStatus("PUSHED");
        when(versionMapper.selectById(99L)).thenReturn(version);

        Path releaseDir = storageProperties.releaseDir(1L, 10L, "v1.0.0");
        Files.createDirectories(releaseDir.resolve("modules"));

        Optional<ActiveKnowledgeContext> ctx = resolver.resolve(10L);
        Assertions.assertTrue(ctx.isPresent());
        Assertions.assertEquals(99L, ctx.get().getVersionId());
        Assertions.assertEquals("v1.0.0", ctx.get().getVersionNum());
        Assertions.assertTrue(ctx.get().isReleaseDirExists());
    }

    @Test
    void require_throwsWhenReleaseDirMissing() {
        CodeRepository repo = new CodeRepository();
        repo.setId(10L);
        repo.setLastPublishedVersionId(99L);
        when(repositoryMapper.selectById(10L)).thenReturn(repo);

        KnowledgeVersion version = new KnowledgeVersion();
        version.setId(99L);
        version.setSystemId(1L);
        version.setRepositoryId(10L);
        version.setVersionNum("v2.0.0");
        version.setStatus("PUSHED");
        when(versionMapper.selectById(99L)).thenReturn(version);

        Assertions.assertThrows(com.company.codeinsight.common.exception.BusinessException.class,
                () -> resolver.require(10L));
    }
}
