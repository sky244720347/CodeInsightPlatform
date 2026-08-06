package com.company.codeinsight.modules.repository.stack;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.codeinsight.common.cluster.ClusterInstanceId;
import com.company.codeinsight.common.cluster.ClusterLeaderLock;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.config.RepoGitCheckProperties;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.common.util.DirectoryCleanupUtil;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.repository.model.RepoType;
import com.company.codeinsight.modules.repository.model.TechStackCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 真空仓类型/技术栈探测：仅 Leader 串行 + 每批续租/校验 + COUNT 调度冷却 + NAS 整轮统一清理。
 * <p>无整轮任务锁；防双写靠「丢 Leader 立即停探」。详见 docs/repo-stack-probe-plan.md。</p>
 */
@Slf4j
@Service
public class RepoStackProbeService {

    public static final String LEADER_LOCK_KEY = "ci:leader:repo-stack-probe";
    public static final String NEXT_RUN_AT_KEY = "ci:stack-probe:next-run-at";

    public static final String ACTION_DB_URL = "STACK_DETECT_DB_URL";
    public static final String ACTION_OK = "STACK_DETECT_OK";
    public static final String ACTION_SKIP = "STACK_DETECT_SKIP";
    public static final String ACTION_FAIL = "STACK_DETECT_FAIL";

    private static final String VACUUM_TYPE =
            "(repo_type IS NULL OR btrim(repo_type) = '')";
    private static final String VACUUM_STACK =
            "(tech_stack IS NULL OR btrim(tech_stack) = '')";
    private static final String VACUUM_TYPE_ONLY =
            "(repo_type IS NULL OR btrim(repo_type) = '')";
    private static final String ELIGIBLE_SQL =
            "(git_reachable = 1 OR git_url ~* '[_-]db(\\.git)?/*$')";

    private static final long[] DEFAULT_BACKOFF = {60_000L, 300_000L, 900_000L};
    private static final String RUN_DIR_PREFIX = "stack_probe_run_";

    private final RepoGitCheckProperties properties;
    private final CodeRepositoryMapper repositoryMapper;
    private final EnvStorageResolver storageResolver;
    private final OperationLogService operationLogService;
    private final ClusterInstanceId clusterInstanceId;
    private final ClusterLeaderLock clusterLeaderLock;
    private final ClusterProperties clusterProperties;

    private final AtomicLong cursorId = new AtomicLong(0L);
    private final AtomicInteger emptyStreak = new AtomicInteger(0);

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public RepoStackProbeService(
            RepoGitCheckProperties properties,
            CodeRepositoryMapper repositoryMapper,
            EnvStorageResolver storageResolver,
            OperationLogService operationLogService,
            ClusterInstanceId clusterInstanceId,
            ClusterLeaderLock clusterLeaderLock,
            ClusterProperties clusterProperties) {
        this.properties = properties;
        this.repositoryMapper = repositoryMapper;
        this.storageResolver = storageResolver;
        this.operationLogService = operationLogService;
        this.clusterInstanceId = clusterInstanceId;
        this.clusterLeaderLock = clusterLeaderLock;
        this.clusterProperties = clusterProperties;
    }

    /** 唤醒调度：清 Redis next-run-at 与本机 emptyStreak（不并行 clone） */
    public void wake() {
        emptyStreak.set(0);
        clearNextRunAt();
    }

    /** 新建/更新真空：只唤醒，由 Leader 下轮串行探 */
    public void wakeAndProbeAsync(Long repositoryId) {
        if (!properties.isStackProbeEnabled()) {
            return;
        }
        if (!properties.isStackProbeOnCreate()) {
            return;
        }
        wake();
        log.debug("stack probe wake (no parallel clone) repoId={}", repositoryId);
    }

    /** Git 刚可达：唤醒调度，下轮 COUNT 会纳入该仓 */
    public void onGitBecameReachable(Long repositoryId) {
        if (repositoryId == null || !properties.isStackProbeEnabled()) {
            return;
        }
        wake();
        log.debug("stack probe wake after git-reachable repoId={}", repositoryId);
    }

    /**
     * Leader 调度入口。分段 try-catch，单点失败不拖垮整轮。
     *
     * @return 本轮成功落表仓数
     */
    public int runScheduledSweep() {
        if (!properties.isStackProbeEnabled()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        try {
            if (!isDue(now)) {
                return 0;
            }
        } catch (Exception e) {
            log.warn("stack probe next-run-at check failed: {}", e.getMessage());
            // fail-open：继续尝试
        }

        // 短 runId：毫秒 base36 + 4 位随机，目录名更短；孤儿清理只认前缀
        String runId = Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(ThreadLocalRandom.current().nextInt(0x100000), 36);
        Path runRoot = null;
        int done = 0;
        try {
            try {
                pruneOrphanRunDirs();
            } catch (Exception e) {
                log.warn("stack probe orphan prune failed: {}", e.getMessage());
            }

            long eligible;
            try {
                eligible = countEligibleVacuum();
            } catch (Exception e) {
                log.error("stack probe COUNT failed: {}", e.getMessage(), e);
                return 0;
            }

            if (eligible <= 0) {
                enterScheduleIdle(now);
                log.debug("stack probe COUNT=0 → schedule idle streak={}", emptyStreak.get());
                return 0;
            }

            emptyStreak.set(0);
            Path workspaceRoot = storageResolver.getActiveWorkspaceRoot();
            if (workspaceRoot == null) {
                log.error("stack probe aborted: workspaceRoot is null");
                return 0;
            }
            runRoot = workspaceRoot.resolve(RUN_DIR_PREFIX + runId);
            try {
                Files.createDirectories(runRoot);
            } catch (Exception e) {
                log.error("stack probe create run dir failed {}: {}", runRoot, e.getMessage());
                return 0;
            }

            done = processBatchesUntilEmptyOrDeadline(runRoot);
            scheduleActiveDelay(System.currentTimeMillis());
            if (done > 0) {
                log.info("stack probe run done={} runId={} instance={}",
                        done, runId, clusterInstanceId.get());
            }
            return done;
        } finally {
            if (runRoot != null) {
                try {
                    DirectoryCleanupUtil.deleteRecursively(runRoot);
                } catch (IOException e) {
                    log.warn("stack probe unified cleanup failed {}: {}", runRoot, e.getMessage());
                }
            }
        }
    }

    private int processBatchesUntilEmptyOrDeadline(Path runRoot) {
        int batchSize = Math.max(1, properties.getStackProbeBatchSize());
        long maxSweepMs = TimeUnit.SECONDS.toMillis(Math.max(60, properties.getStackProbeMaxSweepSeconds()));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxSweepMs);
        int done = 0;
        boolean wrappedThisRun = false;

        while (System.nanoTime() < deadline) {
            long cursor = cursorId.get();
            List<CodeRepository> listed;
            try {
                listed = listEligibleVacuumAfter(cursor, batchSize);
            } catch (Exception e) {
                log.error("stack probe list failed: {}", e.getMessage(), e);
                break;
            }

            if (listed.isEmpty()) {
                if (!wrappedThisRun && cursor > 0) {
                    wrappedThisRun = true;
                    cursorId.set(0L);
                    continue;
                }
                cursorId.set(0L);
                break;
            }

            // 每批一次续租/校验（降 Redis）；TTL 须盖住本批最坏耗时，见 leaderLockTtlSeconds()
            if (!renewLeadershipOrStop(batchSize)) {
                log.warn("stack probe stop: lost leadership before batch cursor={} size={}",
                        cursor, listed.size());
                break;
            }

            long maxId = cursor;
            for (CodeRepository repo : listed) {
                if (System.nanoTime() >= deadline) {
                    break;
                }
                if (repo.getId() != null && repo.getId() > maxId) {
                    maxId = repo.getId();
                }
                try {
                    if (probeOne(repo, runRoot)) {
                        done++;
                    }
                } catch (Exception e) {
                    log.warn("stack probe failed repoId={}: {}", repo.getId(), e.getMessage());
                    safeLog(repo.getSystemId(), ACTION_FAIL,
                            "repoId=" + repo.getId() + " instance=" + clusterInstanceId.get(),
                            e.getMessage(), false);
                }
            }
            cursorId.set(maxId);
        }
        return done;
    }

    /**
     * 每批续租一次。TTL = max(配置, 本批最坏耗时 + 余量)，避免批内锁过期被他机抢走。
     *
     * @return false 表示已不是 Leader，调用方必须停止探测（宁停勿双写）
     */
    private boolean renewLeadershipOrStop(int batchSize) {
        if (!clusterProperties.isEnabled()) {
            return true;
        }
        try {
            return clusterLeaderLock.tryAcquireLeader(LEADER_LOCK_KEY, leaderLockTtlSeconds(batchSize));
        } catch (Exception e) {
            log.warn("stack probe leader renew failed: {}", e.getMessage());
            return false;
        }
    }

    /** 至少覆盖：batchSize × tree-timeout + 2min 余量 */
    private int leaderLockTtlSeconds(int batchSize) {
        int configured = Math.max(60, properties.getStackProbeLeaderLockTtlSeconds());
        long perRepoSec = Math.max(1L, (properties.getStackProbeTreeTimeoutMs() + 999L) / 1000L);
        int batchWorst = (int) Math.min(Integer.MAX_VALUE,
                Math.max(1, batchSize) * perRepoSec + 120L);
        return Math.max(configured, batchWorst);
    }

    private boolean probeOne(CodeRepository repo, Path runRoot) {
        if (!isVacuum(repo)) {
            return false;
        }
        if (RepoStackUrlRules.isDbByUrl(repo.getGitUrl())) {
            return applyDbByUrl(repo);
        }
        return applyTreeProbe(repo, runRoot);
    }

    private boolean applyDbByUrl(CodeRepository repo) {
        String dialect = RepoStackUrlRules.guessDbTechStack(repo.getGitUrl()).orElse(null);
        boolean ok;
        String detail;
        if (StringUtils.hasText(dialect) && TechStackCatalog.isValidPair(RepoType.DB.getCode(), dialect)) {
            ok = casWritePair(repo.getId(), RepoType.DB.getCode(), dialect);
            detail = "repoId=" + repo.getId() + " type=DB stack=" + dialect
                    + " byUrl name=" + RepoStackUrlRules.extractRepoName(repo.getGitUrl()).orElse("?");
        } else {
            ok = casWriteTypeOnly(repo.getId(), RepoType.DB.getCode());
            detail = "repoId=" + repo.getId() + " type=DB stack=(empty)"
                    + " byUrl name=" + RepoStackUrlRules.extractRepoName(repo.getGitUrl()).orElse("?");
        }
        safeLog(repo.getSystemId(), ACTION_DB_URL, detail, null, ok);
        return ok;
    }

    private boolean applyTreeProbe(CodeRepository repo, Path runRoot) {
        Integer reachable = repo.getGitReachable();
        boolean isLocalDir = RepoGitUrlKind.isExistingLocalDirectory(repo.getGitUrl());
        if (!isLocalDir && (reachable == null || reachable != 1)) {
            safeLog(repo.getSystemId(), ACTION_SKIP,
                    "repoId=" + repo.getId() + " reason=git_unreachable", null, true);
            return false;
        }
        Path workDir = runRoot.resolve(String.valueOf(repo.getId()));
        try {
            List<String> paths = RepoStackTreeFetcher.fetchPaths(
                    repo.getGitUrl(),
                    repo.getBranch(),
                    repo.getUsername(),
                    repo.getPassword(),
                    workDir,
                    properties.getStackProbeTreeTimeoutMs(),
                    false);
            RepoStackTreeClassifier.Result result = RepoStackTreeClassifier.classify(paths);
            if (result == null || !result.isHighEnough()
                    || !StringUtils.hasText(result.getRepoType())
                    || !StringUtils.hasText(result.getTechStack())) {
                safeLog(repo.getSystemId(), ACTION_SKIP,
                        "repoId=" + repo.getId() + " reason=low_confidence paths=" + paths.size()
                                + " evidence=" + (result == null ? "null" : result.getEvidence()),
                        null, true);
                return false;
            }
            boolean ok = casWritePair(repo.getId(), result.getRepoType(), result.getTechStack());
            safeLog(repo.getSystemId(), ACTION_OK,
                    "repoId=" + repo.getId()
                            + " type=" + result.getRepoType()
                            + " stack=" + result.getTechStack()
                            + " confidence=" + result.getConfidence()
                            + " paths=" + paths.size()
                            + " instance=" + clusterInstanceId.get(),
                    null, ok);
            return ok;
        } catch (Exception e) {
            safeLog(repo.getSystemId(), ACTION_FAIL,
                    "repoId=" + repo.getId() + " instance=" + clusterInstanceId.get(),
                    e.getMessage(), false);
            return false;
        }
    }

    private void safeLog(Long systemId, String action, String detail, String err, boolean success) {
        try {
            operationLogService.logOperation(systemId, null, action, detail, err, success);
        } catch (Exception e) {
            log.warn("stack probe op-log failed action={}: {}", action, e.getMessage());
        }
    }

    private long countEligibleVacuum() {
        LambdaQueryWrapper<CodeRepository> q = eligibleVacuumWrapper();
        Long n = repositoryMapper.selectCount(q);
        return n == null ? 0L : n;
    }

    private List<CodeRepository> listEligibleVacuumAfter(long afterId, int limit) {
        LambdaQueryWrapper<CodeRepository> q = eligibleVacuumWrapper();
        q.gt(afterId > 0, CodeRepository::getId, afterId)
                .orderByAsc(CodeRepository::getId)
                .last("LIMIT " + Math.max(1, limit));
        return repositoryMapper.selectList(q);
    }

    private LambdaQueryWrapper<CodeRepository> eligibleVacuumWrapper() {
        LambdaQueryWrapper<CodeRepository> q = new LambdaQueryWrapper<>();
        q.apply(VACUUM_TYPE)
                .apply(VACUUM_STACK)
                .apply(ELIGIBLE_SQL);
        return q;
    }

    static boolean isVacuum(CodeRepository repo) {
        if (repo == null) {
            return false;
        }
        return !StringUtils.hasText(repo.getRepoType()) && !StringUtils.hasText(repo.getTechStack());
    }

    private boolean casWritePair(Long id, String repoType, String techStack) {
        LambdaUpdateWrapper<CodeRepository> u = new LambdaUpdateWrapper<>();
        u.eq(CodeRepository::getId, id)
                .apply(VACUUM_TYPE)
                .apply(VACUUM_STACK)
                .set(CodeRepository::getRepoType, repoType)
                .set(CodeRepository::getTechStack, techStack)
                .set(CodeRepository::getUpdatedDate, LocalDateTime.now());
        return repositoryMapper.update(null, u) > 0;
    }

    private boolean casWriteTypeOnly(Long id, String repoType) {
        LambdaUpdateWrapper<CodeRepository> u = new LambdaUpdateWrapper<>();
        u.eq(CodeRepository::getId, id)
                .apply(VACUUM_TYPE_ONLY)
                .set(CodeRepository::getRepoType, repoType)
                .set(CodeRepository::getUpdatedDate, LocalDateTime.now());
        return repositoryMapper.update(null, u) > 0;
    }

    private boolean isDue(long nowMs) {
        if (redisTemplate == null) {
            return true;
        }
        try {
            String v = redisTemplate.opsForValue().get(NEXT_RUN_AT_KEY);
            if (!StringUtils.hasText(v)) {
                return true;
            }
            long next = Long.parseLong(v.trim());
            return nowMs >= next;
        } catch (Exception e) {
            log.warn("stack probe read next-run-at failed: {}", e.getMessage());
            return true;
        }
    }

    private void enterScheduleIdle(long nowMs) {
        int streak = emptyStreak.incrementAndGet();
        long delay = backoffDelayMs(streak);
        writeNextRunAt(nowMs + delay, delay);
    }

    private void scheduleActiveDelay(long nowMs) {
        long delay = Math.max(1_000L, properties.getStackProbeActiveDelayMs());
        writeNextRunAt(nowMs + delay, delay);
    }

    private void writeNextRunAt(long epochMs, long ttlHintMs) {
        if (redisTemplate == null) {
            return;
        }
        try {
            long ttl = Math.max(ttlHintMs, epochMs - System.currentTimeMillis());
            redisTemplate.opsForValue().set(
                    NEXT_RUN_AT_KEY,
                    String.valueOf(epochMs),
                    Duration.ofMillis(Math.max(1_000L, ttl)));
        } catch (Exception e) {
            log.warn("stack probe write next-run-at failed: {}", e.getMessage());
        }
    }

    private void clearNextRunAt() {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(NEXT_RUN_AT_KEY);
        } catch (Exception e) {
            log.warn("stack probe clear next-run-at failed: {}", e.getMessage());
        }
    }

    private void pruneOrphanRunDirs() throws IOException {
        Path workspaceRoot = storageResolver.getActiveWorkspaceRoot();
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            return;
        }
        long maxAgeMs = TimeUnit.HOURS.toMillis(Math.max(1, properties.getStackProbeOrphanMaxAgeHours()));
        Instant cutoff = Instant.now().minusMillis(maxAgeMs);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workspaceRoot)) {
            for (Path p : stream) {
                if (!Files.isDirectory(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                boolean orphanRun = name.startsWith(RUN_DIR_PREFIX);
                boolean legacyPerRepo = name.startsWith("stack_probe_") && !orphanRun;
                if (!orphanRun && !legacyPerRepo) {
                    continue;
                }
                FileTime ft = Files.getLastModifiedTime(p);
                if (ft.toInstant().isBefore(cutoff)) {
                    try {
                        DirectoryCleanupUtil.deleteRecursively(p);
                        log.info("stack probe pruned orphan dir {}", p);
                    } catch (IOException e) {
                        log.warn("stack probe prune failed {}: {}", p, e.getMessage());
                    }
                }
            }
        }
    }

    long backoffDelayMs(int streak) {
        long[] ladder = parseBackoffLadder(properties.getStackProbeIdleBackoffMs());
        int idx = Math.min(Math.max(streak, 1), ladder.length) - 1;
        return ladder[idx];
    }

    static long[] parseBackoffLadder(String raw) {
        if (!StringUtils.hasText(raw)) {
            return DEFAULT_BACKOFF.clone();
        }
        String[] parts = raw.split(",");
        List<Long> list = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                long v = Long.parseLong(t);
                if (v > 0) {
                    list.add(v);
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        if (list.isEmpty()) {
            return DEFAULT_BACKOFF.clone();
        }
        return list.stream().mapToLong(Long::longValue).toArray();
    }

    int getEmptyStreak() {
        return emptyStreak.get();
    }
}
