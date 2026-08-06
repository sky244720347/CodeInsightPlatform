package com.company.codeinsight.modules.push.retention;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.config.ReleaseRetentionProperties;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.push.mapper.PushTaskMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.repository.publish.mapper.RepositoryPublishSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReleaseRetentionServiceTest {

    @Mock
    private KnowledgeVersionMapper versionMapper;
    @Mock
    private PushTaskMapper pushTaskMapper;
    @Mock
    private RepositoryPublishSnapshotMapper snapshotMapper;
    @Mock
    private CodeRepositoryMapper repositoryMapper;
    @Mock
    private EnvStorageResolver storageResolver;
    @Mock
    private OperationLogService operationLogService;
    @Mock
    private Executor releasePruneExecutor;

    private ReleaseRetentionProperties properties;
    private ReleaseRetentionService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new ReleaseRetentionProperties();
        properties.setReleaseKeepCount(3);
        properties.setReleasePruneMaxAttempts(3);
        properties.setReleasePruneBackoffMs(0L);
        service = new ReleaseRetentionService(
                properties,
                versionMapper,
                pushTaskMapper,
                snapshotMapper,
                repositoryMapper,
                storageResolver,
                operationLogService,
                releasePruneExecutor);
        lenient().when(storageResolver.getActiveReleasesRoot()).thenReturn(tempDir.resolve("releases"));
        lenient().when(storageResolver.getActiveRuntimeRoot()).thenReturn(tempDir.resolve("runtime"));
    }

    @Test
    void markAndCollect_keepsLatestThree_softDeletesOlder() throws Exception {
        CodeRepository repo = new CodeRepository();
        repo.setId(10L);
        repo.setSystemId(1L);
        repo.setLastPublishedVersionId(5L);
        when(repositoryMapper.selectById(10L)).thenReturn(repo);

        List<KnowledgeVersion> versions = List.of(
                version(5L, "v5", LocalDateTime.now()),
                version(4L, "v4", LocalDateTime.now().minusHours(1)),
                version(3L, "v3", LocalDateTime.now().minusHours(2)),
                version(2L, "v2", LocalDateTime.now().minusHours(3)),
                version(1L, "v1", LocalDateTime.now().minusHours(4)));
        when(versionMapper.selectList(any())).thenReturn(versions);
        when(versionMapper.selectById(5L)).thenReturn(versions.get(0));

        Path repoRel = tempDir.resolve("releases").resolve("1").resolve("10");
        Files.createDirectories(repoRel.resolve("v1"));
        Files.createDirectories(repoRel.resolve("v2"));
        Files.createDirectories(repoRel.resolve("dirty-folder"));

        List<ReleaseRetentionService.PruneTarget> targets = service.markAndCollectPruneTargets(10L);

        assertTrue(targets.stream().anyMatch(t -> "v1".equals(t.versionNum())));
        assertTrue(targets.stream().anyMatch(t -> "v2".equals(t.versionNum())));
        assertFalse(targets.stream().anyMatch(t -> "v3".equals(t.versionNum())));
        assertFalse(targets.stream().anyMatch(t -> "dirty-folder".equals(t.versionNum())));

        verify(versionMapper).deleteById(1L);
        verify(versionMapper).deleteById(2L);
        verify(versionMapper, never()).deleteById(3L);
        verify(versionMapper, never()).deleteById(5L);
        verify(operationLogService).logOperation(eq(1L), any(), eq("RELEASE_PRUNE_MARKED"), any(), any(), eq(true));
    }

    @Test
    void deleteDiskWithRetry_succeedsAndLogsOk() throws Exception {
        Path release = tempDir.resolve("releases").resolve("1").resolve("10").resolve("v1");
        Files.createDirectories(release);
        Files.writeString(release.resolve("x.txt"), "x");
        Path snap = tempDir.resolve("runtime").resolve("publish-snapshots").resolve("10").resolve("99");
        Files.createDirectories(snap);

        when(storageResolver.releaseDir(1L, 10L, "v1")).thenReturn(release);
        when(storageResolver.getActiveRuntimeRoot()).thenReturn(tempDir.resolve("runtime"));

        ReleaseRetentionService.PruneTarget t =
                new ReleaseRetentionService.PruneTarget(1L, 10L, 99L, "v1");
        service.deleteDiskWithRetry(t);

        assertFalse(Files.exists(release));
        assertFalse(Files.exists(snap));
        verify(operationLogService).logOperation(eq(1L), any(), eq("RELEASE_PRUNE_OK"), any(), any(), eq(true));
    }

    @Test
    void activePointerAlwaysKeptEvenIfOutsideTopN() {
        properties.setReleaseKeepCount(2);
        CodeRepository repo = new CodeRepository();
        repo.setId(10L);
        repo.setSystemId(1L);
        repo.setLastPublishedVersionId(1L); // oldest but active
        when(repositoryMapper.selectById(10L)).thenReturn(repo);

        List<KnowledgeVersion> versions = List.of(
                version(4L, "v4", LocalDateTime.now()),
                version(3L, "v3", LocalDateTime.now().minusHours(1)),
                version(2L, "v2", LocalDateTime.now().minusHours(2)),
                version(1L, "v1", LocalDateTime.now().minusHours(3)));
        when(versionMapper.selectList(any())).thenReturn(versions);
        when(versionMapper.selectById(1L)).thenReturn(versions.get(3));

        service.markAndCollectPruneTargets(10L);

        verify(versionMapper, never()).deleteById(1L);
        verify(versionMapper, never()).deleteById(4L);
        verify(versionMapper, never()).deleteById(3L);
        verify(versionMapper).deleteById(2L);
    }

    private static KnowledgeVersion version(Long id, String num, LocalDateTime pushedAt) {
        KnowledgeVersion v = new KnowledgeVersion();
        v.setId(id);
        v.setSystemId(1L);
        v.setRepositoryId(10L);
        v.setVersionNum(num);
        v.setStatus("PUSHED");
        v.setPushedAt(pushedAt);
        return v;
    }
}
