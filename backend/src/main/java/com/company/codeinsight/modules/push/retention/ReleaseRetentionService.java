package com.company.codeinsight.modules.push.retention;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.config.AsyncExecutorConfig;
import com.company.codeinsight.common.config.ReleaseRetentionProperties;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.common.util.DirectoryCleanupUtil;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.push.entity.PushTask;
import com.company.codeinsight.modules.push.mapper.PushTaskMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryPublishSnapshot;
import com.company.codeinsight.modules.repository.publish.mapper.RepositoryPublishSnapshotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * 推送成功后：每仓只保留最近 N 个 PUSHED 版本；其余软删 + 异步删 release / publish-snapshot。
 * <p>详见 docs/release-retention-prune-plan.md。</p>
 */
@Slf4j
@Service
public class ReleaseRetentionService {

    private static final Pattern VERSION_NUM_PATTERN = Pattern.compile("^v\\d+$");
    private static final String LOCK_PREFIX = "ci:lock:release-prune:";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final ReleaseRetentionProperties properties;
    private final KnowledgeVersionMapper versionMapper;
    private final PushTaskMapper pushTaskMapper;
    private final RepositoryPublishSnapshotMapper snapshotMapper;
    private final CodeRepositoryMapper repositoryMapper;
    private final EnvStorageResolver storageResolver;
    private final OperationLogService operationLogService;
    private final Executor releasePruneExecutor;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public ReleaseRetentionService(
            ReleaseRetentionProperties properties,
            KnowledgeVersionMapper versionMapper,
            PushTaskMapper pushTaskMapper,
            RepositoryPublishSnapshotMapper snapshotMapper,
            CodeRepositoryMapper repositoryMapper,
            EnvStorageResolver storageResolver,
            OperationLogService operationLogService,
            @Qualifier(AsyncExecutorConfig.RELEASE_PRUNE_EXECUTOR) Executor releasePruneExecutor) {
        this.properties = properties;
        this.versionMapper = versionMapper;
        this.pushTaskMapper = pushTaskMapper;
        this.snapshotMapper = snapshotMapper;
        this.repositoryMapper = repositoryMapper;
        this.storageResolver = storageResolver;
        this.operationLogService = operationLogService;
        this.releasePruneExecutor = releasePruneExecutor;
    }

    /**
     * 推送成功后调用：同步软删超额版本，异步删盘。失败不影响推送 SUCCESS。
     */
    public void submitAfterPushSuccess(Long repositoryId) {
        if (repositoryId == null) {
            return;
        }
        try {
            pruneRepository(repositoryId, true);
        } catch (Exception e) {
            log.error("release 保留清理提交失败 repositoryId={}: {}", repositoryId, e.getMessage(), e);
        }
    }

    /** 对账：扫 releasesRoot 下各仓，按 keep 规则软删 + 删盘。 */
    public void reconcileAll() {
        Path releasesRoot = storageResolver.getActiveReleasesRoot();
        if (releasesRoot == null || !Files.isDirectory(releasesRoot)) {
            return;
        }
        int repos = 0;
        try (DirectoryStream<Path> systems = Files.newDirectoryStream(releasesRoot)) {
            for (Path sysDir : systems) {
                if (!Files.isDirectory(sysDir)) {
                    continue;
                }
                if (parseLongDir(sysDir.getFileName().toString()) == null) {
                    continue;
                }
                try (DirectoryStream<Path> reposDirs = Files.newDirectoryStream(sysDir)) {
                    for (Path repoDir : reposDirs) {
                        if (!Files.isDirectory(repoDir)) {
                            continue;
                        }
                        Long repositoryId = parseLongDir(repoDir.getFileName().toString());
                        if (repositoryId == null) {
                            continue;
                        }
                        repos++;
                        try {
                            pruneRepository(repositoryId, true);
                        } catch (Exception e) {
                            log.warn("release 对账清理失败 repositoryId={}: {}", repositoryId, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("release 对账扫描失败 root={}: {}", releasesRoot, e.getMessage(), e);
            return;
        }
        log.info("release 对账完成 scannedRepos={}", repos);
    }

    void pruneRepository(Long repositoryId, boolean enqueueDisk) {
        String lockKey = LOCK_PREFIX + repositoryId;
        boolean locked = tryLock(lockKey);
        if (!locked) {
            log.debug("release prune 跳过（未拿到锁）repositoryId={}", repositoryId);
            return;
        }
        List<PruneTarget> toPrune;
        try {
            toPrune = markAndCollectPruneTargets(repositoryId);
        } finally {
            unlock(lockKey);
        }
        if (toPrune.isEmpty() || !enqueueDisk) {
            return;
        }
        for (PruneTarget t : toPrune) {
            releasePruneExecutor.execute(() -> deleteDiskWithRetry(t));
        }
    }

    /**
     * 计算 keep / prune，软删 prune，返回待删盘目标（含盘孤儿）。
     */
    List<PruneTarget> markAndCollectPruneTargets(Long repositoryId) {
        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        if (repo == null) {
            return List.of();
        }
        int keep = Math.max(1, properties.getReleaseKeepCount());
        List<KnowledgeVersion> pushed = versionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeVersion>()
                        .eq(KnowledgeVersion::getRepositoryId, repositoryId)
                        .eq(KnowledgeVersion::getStatus, "PUSHED")
                        .orderByDesc(KnowledgeVersion::getPushedAt)
                        .orderByDesc(KnowledgeVersion::getId));

        Set<Long> keepIds = new LinkedHashSet<>();
        for (KnowledgeVersion v : pushed) {
            if (keepIds.size() >= keep) {
                break;
            }
            keepIds.add(v.getId());
        }
        Long activeId = repo.getLastPublishedVersionId();
        if (activeId != null) {
            keepIds.add(activeId);
        }

        Set<String> keepNums = new LinkedHashSet<>();
        for (KnowledgeVersion v : pushed) {
            if (keepIds.contains(v.getId()) && StringUtils.hasText(v.getVersionNum())) {
                keepNums.add(v.getVersionNum());
            }
        }
        if (activeId != null) {
            KnowledgeVersion active = versionMapper.selectById(activeId);
            if (active != null && StringUtils.hasText(active.getVersionNum())) {
                keepNums.add(active.getVersionNum());
            }
        }

        List<PruneTarget> targets = new ArrayList<>();
        List<String> markedNums = new ArrayList<>();
        for (KnowledgeVersion v : pushed) {
            if (keepIds.contains(v.getId())) {
                continue;
            }
            softDeleteVersionCascade(v.getId());
            markedNums.add(v.getVersionNum());
            targets.add(new PruneTarget(
                    v.getSystemId() != null ? v.getSystemId() : repo.getSystemId(),
                    repositoryId,
                    v.getId(),
                    v.getVersionNum()));
        }

        for (PruneTarget orphan : collectDiskOrphans(repo, keepNums, keepIds)) {
            if (targets.stream().noneMatch(t -> Objects.equals(t.versionNum(), orphan.versionNum())
                    && Objects.equals(t.versionId(), orphan.versionId()))) {
                targets.add(orphan);
            }
        }

        if (!markedNums.isEmpty()) {
            operationLogService.logOperation(
                    repo.getSystemId(),
                    null,
                    "RELEASE_PRUNE_MARKED",
                    "repositoryId=" + repositoryId + " softDeleted=" + String.join(",", markedNums)
                            + " keep=" + String.join(",", keepNums),
                    null,
                    true);
        }
        return targets;
    }

    private List<PruneTarget> collectDiskOrphans(CodeRepository repo, Set<String> keepNums, Set<Long> keepIds) {
        Long systemId = repo.getSystemId();
        Long repositoryId = repo.getId();
        if (systemId == null || repositoryId == null) {
            return List.of();
        }
        List<PruneTarget> orphans = new ArrayList<>();
        Path repoReleaseRoot = storageResolver.getActiveReleasesRoot()
                .resolve(String.valueOf(systemId))
                .resolve(String.valueOf(repositoryId));
        if (Files.isDirectory(repoReleaseRoot)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(repoReleaseRoot)) {
                for (Path child : stream) {
                    if (!Files.isDirectory(child)) {
                        continue;
                    }
                    String name = child.getFileName().toString();
                    if (!VERSION_NUM_PATTERN.matcher(name).matches()) {
                        continue;
                    }
                    if (keepNums.contains(name)) {
                        continue;
                    }
                    KnowledgeVersion v = versionMapper.selectOne(
                            new LambdaQueryWrapper<KnowledgeVersion>()
                                    .eq(KnowledgeVersion::getRepositoryId, repositoryId)
                                    .eq(KnowledgeVersion::getVersionNum, name)
                                    .last("LIMIT 1"));
                    Long versionId = null;
                    if (v != null) {
                        versionId = v.getId();
                        if (!keepIds.contains(v.getId())) {
                            softDeleteVersionCascade(v.getId());
                        }
                    }
                    orphans.add(new PruneTarget(systemId, repositoryId, versionId, name));
                }
            } catch (Exception e) {
                log.warn("扫描 release 目录失败 path={}: {}", repoReleaseRoot, e.getMessage());
            }
        }

        // publish-snapshots/{repoId}/{versionId} 不在 keepIds 则删
        Path snapRoot = storageResolver.getActiveRuntimeRoot()
                .resolve("publish-snapshots")
                .resolve(String.valueOf(repositoryId));
        if (Files.isDirectory(snapRoot)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(snapRoot)) {
                for (Path child : stream) {
                    if (!Files.isDirectory(child)) {
                        continue;
                    }
                    Long versionId = parseLongDir(child.getFileName().toString());
                    if (versionId == null || keepIds.contains(versionId)) {
                        continue;
                    }
                    orphans.add(new PruneTarget(systemId, repositoryId, versionId, null));
                }
            } catch (Exception e) {
                log.warn("扫描 publish-snapshots 失败 path={}: {}", snapRoot, e.getMessage());
            }
        }
        return orphans;
    }

    private void softDeleteVersionCascade(Long versionId) {
        if (versionId == null) {
            return;
        }
        snapshotMapper.delete(new LambdaQueryWrapper<RepositoryPublishSnapshot>()
                .eq(RepositoryPublishSnapshot::getVersionId, versionId));
        pushTaskMapper.delete(new LambdaQueryWrapper<PushTask>()
                .eq(PushTask::getVersionId, versionId));
        versionMapper.deleteById(versionId);
    }

    void deleteDiskWithRetry(PruneTarget target) {
        int maxAttempts = Math.max(1, properties.getReleasePruneMaxAttempts());
        long backoff = Math.max(0L, properties.getReleasePruneBackoffMs());
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                deleteDiskOnce(target);
                operationLogService.logOperation(
                        target.systemId(),
                        null,
                        "RELEASE_PRUNE_OK",
                        "repositoryId=" + target.repositoryId()
                                + " versionNum=" + target.versionNum()
                                + " versionId=" + target.versionId()
                                + " attempt=" + attempt,
                        null,
                        true);
                return;
            } catch (Exception e) {
                last = e;
                log.warn("release 删盘失败 attempt={}/{} repo={} versionNum={} versionId={}: {}",
                        attempt, maxAttempts, target.repositoryId(), target.versionNum(),
                        target.versionId(), e.getMessage());
                if (attempt < maxAttempts && backoff > 0) {
                    try {
                        Thread.sleep(backoff * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        String err = last != null ? last.getMessage() : "unknown";
        operationLogService.logOperation(
                target.systemId(),
                null,
                "RELEASE_PRUNE_FAILED",
                "repositoryId=" + target.repositoryId()
                        + " versionNum=" + target.versionNum()
                        + " versionId=" + target.versionId()
                        + " attempts=" + maxAttempts,
                err,
                false);
        log.error("release 删盘放弃 repo={} versionNum={} versionId={} after {} attempts: {}",
                target.repositoryId(), target.versionNum(), target.versionId(), maxAttempts, err);
    }

    private void deleteDiskOnce(PruneTarget target) {
        if (StringUtils.hasText(target.versionNum())) {
            Path releaseDir = storageResolver.releaseDir(
                    target.systemId(), target.repositoryId(), target.versionNum());
            DirectoryCleanupUtil.deleteRecursivelyUnchecked(releaseDir);
        }
        if (target.versionId() != null) {
            Path snapDir = storageResolver.getActiveRuntimeRoot()
                    .resolve("publish-snapshots")
                    .resolve(String.valueOf(target.repositoryId()))
                    .resolve(String.valueOf(target.versionId()));
            DirectoryCleanupUtil.deleteRecursivelyUnchecked(snapDir);
        }
    }

    private boolean tryLock(String lockKey) {
        if (redisTemplate == null) {
            return true;
        }
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("release prune 加锁失败，继续执行: {}", e.getMessage());
            return true;
        }
    }

    private void unlock(String lockKey) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(lockKey);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static Long parseLongDir(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        try {
            return Long.parseLong(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** versionNum 可为 null（仅清 snapshot）；versionId 可为 null（仅清 release）。 */
    record PruneTarget(Long systemId, Long repositoryId, Long versionId, String versionNum) {
    }
}
