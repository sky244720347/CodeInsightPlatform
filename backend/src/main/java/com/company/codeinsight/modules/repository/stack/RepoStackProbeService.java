package com.company.codeinsight.modules.repository.stack;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.codeinsight.common.cluster.ClusterInstanceId;
import com.company.codeinsight.common.config.AsyncExecutorConfig;
import com.company.codeinsight.common.config.RepoGitCheckProperties;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.repository.model.RepoType;
import com.company.codeinsight.modules.repository.model.TechStackCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 真空仓类型/技术栈多机探测：Redis 按仓锁 + 真空 CAS，无 Leader。
 * <p>忙密闲疏：空结果退避；SKIP/FAIL Redis 冷却；创建/连通唤醒。详见 docs/repo-stack-probe-plan.md。</p>
 */
@Slf4j
@Service
public class RepoStackProbeService {

    public static final String LOCK_PREFIX = "ci:lock:stack-probe:";
    public static final String COOLDOWN_PREFIX = "ci:stack-probe:cooldown:";
    public static final String IDLE_UNTIL_KEY = "ci:stack-probe:idle-until";

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

    private static final long[] DEFAULT_BACKOFF = {60_000L, 300_000L, 900_000L};

    private final RepoGitCheckProperties properties;
    private final CodeRepositoryMapper repositoryMapper;
    private final EnvStorageResolver storageResolver;
    private final OperationLogService operationLogService;
    private final ClusterInstanceId clusterInstanceId;
    private final Executor stackProbeExecutor;

    private final AtomicLong cursorId = new AtomicLong(0L);
    private final AtomicInteger emptyStreak = new AtomicInteger(0);
    private final AtomicLong nextProbeAtMs = new AtomicLong(0L);
    private volatile Semaphore treeSemaphore;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public RepoStackProbeService(
            RepoGitCheckProperties properties,
            CodeRepositoryMapper repositoryMapper,
            EnvStorageResolver storageResolver,
            OperationLogService operationLogService,
            ClusterInstanceId clusterInstanceId,
            @Qualifier(AsyncExecutorConfig.STACK_PROBE_EXECUTOR) Executor stackProbeExecutor) {
        this.properties = properties;
        this.repositoryMapper = repositoryMapper;
        this.storageResolver = storageResolver;
        this.operationLogService = operationLogService;
        this.clusterInstanceId = clusterInstanceId;
        this.stackProbeExecutor = stackProbeExecutor;
    }

    /** 清空本机/全局空闲门闩，使下一 tick 可立刻查库 */
    public void wake() {
        emptyStreak.set(0);
        nextProbeAtMs.set(0L);
        clearGlobalIdle();
    }

    /** 唤醒并（在 on-create 开启时）异步探一仓；同时清该仓冷却 */
    public void wakeAndProbeAsync(Long repositoryId) {
        wake();
        if (repositoryId != null) {
            clearCooldown(repositoryId);
        }
        submitProbeAsync(repositoryId);
    }

    /**
     * Git 刚变为可达：清冷却并若仍真空则异步探测。
     */
    public void onGitBecameReachable(Long repositoryId) {
        if (repositoryId == null || !properties.isStackProbeEnabled()) {
            return;
        }
        wake();
        clearCooldown(repositoryId);
        stackProbeExecutor.execute(() -> {
            try {
                CodeRepository repo = repositoryMapper.selectById(repositoryId);
                if (isVacuum(repo)) {
                    probeOne(repo);
                }
            } catch (Exception e) {
                log.warn("stack probe after git-reachable failed repoId={}: {}",
                        repositoryId, e.getMessage());
            }
        });
    }

    /** 新建真空仓后异步探测（afterCommit 调用） */
    public void submitProbeAsync(Long repositoryId) {
        if (repositoryId == null || !properties.isStackProbeEnabled() || !properties.isStackProbeOnCreate()) {
            return;
        }
        stackProbeExecutor.execute(() -> {
            try {
                probeOne(repositoryId);
            } catch (Exception e) {
                log.warn("stack probe on-create failed repoId={}: {}", repositoryId, e.getMessage());
            }
        });
    }

    /** 调度 tick：处理一批真空仓（可能零 SQL） */
    public int probeBatch() {
        if (!properties.isStackProbeEnabled()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        if (now < nextProbeAtMs.get()) {
            return 0;
        }
        if (isGlobalIdleActive(now)) {
            return 0;
        }

        int batch = Math.max(1, properties.getStackProbeBatchSize());
        long cursor = cursorId.get();
        // 热路径只拉「可达」或「URL 可判 DB」的真空仓；git_reachable=0 不进定时批次（等连通唤醒）
        List<CodeRepository> batch1 = listEligibleVacuumAfter(cursor, batch);
        List<CodeRepository> listed = batch1;
        if (batch1.size() < batch && cursor > 0 && !batch1.isEmpty()) {
            List<CodeRepository> wrap = listEligibleVacuumAfter(0L, batch - batch1.size());
            listed = new ArrayList<>(batch1);
            listed.addAll(wrap);
        } else if (batch1.isEmpty() && cursor > 0) {
            listed = listEligibleVacuumAfter(0L, batch);
        }

        if (listed.isEmpty()) {
            enterIdle(now);
            return 0;
        }

        long listedMaxId = cursor;
        for (CodeRepository repo : listed) {
            if (repo.getId() != null && repo.getId() > listedMaxId) {
                listedMaxId = repo.getId();
            }
        }

        List<CodeRepository> candidates = new ArrayList<>(listed.size());
        for (CodeRepository repo : listed) {
            if (repo.getId() != null && inCooldown(repo.getId())) {
                continue;
            }
            candidates.add(repo);
        }
        if (candidates.isEmpty()) {
            // 本批全冷却：推进游标越过它们，避免低 id 不可达/失败仓永久堵死后面的可达仓
            cursorId.set(listedMaxId);
            enterIdle(now);
            return 0;
        }

        leaveIdle();

        int done = 0;
        long maxId = cursor;
        for (CodeRepository repo : candidates) {
            if (repo.getId() != null && repo.getId() > maxId) {
                maxId = repo.getId();
            }
            try {
                if (probeOne(repo)) {
                    done++;
                }
            } catch (Exception e) {
                log.warn("stack probe failed repoId={}: {}", repo.getId(), e.getMessage());
                markCooldown(repo.getId(), "FAIL", properties.getStackProbeCooldownFailMs());
                operationLogService.logOperation(
                        repo.getSystemId(), null, ACTION_FAIL,
                        "repoId=" + repo.getId() + " instance=" + clusterInstanceId.get(),
                        e.getMessage(), false);
            }
        }
        cursorId.set(Math.max(maxId, listedMaxId));
        if (done > 0) {
            log.info("stack probe batch done={} tried={} cursor={} instance={}",
                    done, candidates.size(), cursorId.get(), clusterInstanceId.get());
        }
        return done;
    }

    public boolean probeOne(Long repositoryId) {
        if (repositoryId == null) {
            return false;
        }
        if (inCooldown(repositoryId)) {
            return false;
        }
        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        if (repo == null) {
            return false;
        }
        return probeOne(repo);
    }

    private boolean probeOne(CodeRepository repo) {
        if (!isVacuum(repo)) {
            return false;
        }
        if (repo.getId() != null && inCooldown(repo.getId())) {
            return false;
        }
        String lockKey = LOCK_PREFIX + repo.getId();
        if (!tryLock(lockKey)) {
            return false;
        }
        try {
            // 不再二次 selectById：靠真空 CAS 防覆盖；锁防并发拉树
            if (RepoStackUrlRules.isDbByUrl(repo.getGitUrl())) {
                boolean ok = applyDbByUrl(repo);
                if (ok) {
                    clearCooldown(repo.getId());
                }
                return ok;
            }
            return applyTreeProbe(repo);
        } finally {
            unlock(lockKey);
        }
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
        operationLogService.logOperation(
                repo.getSystemId(), null, ACTION_DB_URL, detail, null, ok);
        return ok;
    }

    private boolean applyTreeProbe(CodeRepository repo) {
        Integer reachable = repo.getGitReachable();
        boolean isLocalDir = RepoGitUrlKind.isExistingLocalDirectory(repo.getGitUrl());
        if (!isLocalDir && (reachable == null || reachable != 1)) {
            markCooldown(repo.getId(), "UNREACHABLE", properties.getStackProbeCooldownUnreachableMs());
            operationLogService.logOperation(
                    repo.getSystemId(), null, ACTION_SKIP,
                    "repoId=" + repo.getId() + " reason=git_unreachable", null, true);
            return false;
        }

        Semaphore sem = treeSemaphore();
        boolean acquired = false;
        try {
            acquired = sem.tryAcquire();
            if (!acquired) {
                // 并发满不冷却，下轮可再试
                operationLogService.logOperation(
                        repo.getSystemId(), null, ACTION_SKIP,
                        "repoId=" + repo.getId() + " reason=tree_concurrency", null, true);
                return false;
            }
            Path workDir = storageResolver.getActiveWorkspaceRoot()
                    .resolve("stack_probe_" + repo.getId());
            List<String> paths = RepoStackTreeFetcher.fetchPaths(
                    repo.getGitUrl(),
                    repo.getBranch(),
                    repo.getUsername(),
                    repo.getPassword(),
                    workDir,
                    properties.getStackProbeTreeTimeoutMs());
            RepoStackTreeClassifier.Result result = RepoStackTreeClassifier.classify(paths);
            if (result == null || !result.isHighEnough()
                    || !StringUtils.hasText(result.getRepoType())
                    || !StringUtils.hasText(result.getTechStack())) {
                markCooldown(repo.getId(), "LOW", properties.getStackProbeCooldownFailMs());
                operationLogService.logOperation(
                        repo.getSystemId(), null, ACTION_SKIP,
                        "repoId=" + repo.getId() + " reason=low_confidence paths=" + paths.size()
                                + " evidence=" + (result == null ? "null" : result.getEvidence()),
                        null, true);
                return false;
            }
            boolean ok = casWritePair(repo.getId(), result.getRepoType(), result.getTechStack());
            if (ok) {
                clearCooldown(repo.getId());
            }
            operationLogService.logOperation(
                    repo.getSystemId(), null, ACTION_OK,
                    "repoId=" + repo.getId()
                            + " type=" + result.getRepoType()
                            + " stack=" + result.getTechStack()
                            + " confidence=" + result.getConfidence()
                            + " paths=" + paths.size()
                            + " instance=" + clusterInstanceId.get(),
                    null, ok);
            return ok;
        } catch (Exception e) {
            markCooldown(repo.getId(), "FAIL", properties.getStackProbeCooldownFailMs());
            operationLogService.logOperation(
                    repo.getSystemId(), null, ACTION_FAIL,
                    "repoId=" + repo.getId() + " instance=" + clusterInstanceId.get(),
                    e.getMessage(), false);
            return false;
        } finally {
            if (acquired) {
                sem.release();
            }
        }
    }

    private void enterIdle(long nowMs) {
        int streak = emptyStreak.incrementAndGet();
        long delay = backoffDelayMs(streak);
        nextProbeAtMs.set(nowMs + delay);
        if (properties.isStackProbeGlobalIdle()) {
            writeGlobalIdle(nowMs + delay);
        }
        log.debug("stack probe idle streak={} nextDelayMs={} instance={}",
                streak, delay, clusterInstanceId.get());
    }

    private void leaveIdle() {
        emptyStreak.set(0);
        nextProbeAtMs.set(0L);
        clearGlobalIdle();
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
                // skip bad token
            }
        }
        if (list.isEmpty()) {
            return DEFAULT_BACKOFF.clone();
        }
        return list.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * 定时批次候选：真空 且（Git 已连通 或 URL 可零远程判 DB）。
     * <p>{@code git_reachable = 0 / NULL} 的非 DB 仓不进热路径，避免数百不可达占满 LIMIT、拖死可达仓。</p>
     */
    private List<CodeRepository> listEligibleVacuumAfter(long afterId, int limit) {
        LambdaQueryWrapper<CodeRepository> q = new LambdaQueryWrapper<>();
        q.apply(VACUUM_TYPE)
                .apply(VACUUM_STACK)
                .and(w -> w.eq(CodeRepository::getGitReachable, 1)
                        .or()
                        .apply("git_url ~* '[_-]db(\\.git)?/*$'"))
                .gt(afterId > 0, CodeRepository::getId, afterId)
                .orderByAsc(CodeRepository::getId)
                .last("LIMIT " + Math.max(1, limit));
        return repositoryMapper.selectList(q);
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

    private Semaphore treeSemaphore() {
        Semaphore current = treeSemaphore;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (treeSemaphore == null) {
                treeSemaphore = new Semaphore(Math.max(1, properties.getStackProbeTreeConcurrency()));
            }
            return treeSemaphore;
        }
    }

    private boolean tryLock(String lockKey) {
        if (redisTemplate == null) {
            return true;
        }
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(
                    lockKey,
                    clusterInstanceId.get(),
                    Duration.ofSeconds(Math.max(30, properties.getStackProbeLockTtlSeconds())));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("stack probe lock acquire failed key={}: {}", lockKey, e.getMessage());
            return false;
        }
    }

    private void unlock(String lockKey) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(lockKey);
        } catch (Exception e) {
            log.warn("stack probe lock release failed key={}: {}", lockKey, e.getMessage());
        }
    }

    private void markCooldown(Long repoId, String kind, long ttlMs) {
        if (repoId == null || redisTemplate == null || ttlMs <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    COOLDOWN_PREFIX + repoId,
                    kind == null ? "1" : kind,
                    Duration.ofMillis(ttlMs));
        } catch (Exception e) {
            log.warn("stack probe cooldown set failed repoId={}: {}", repoId, e.getMessage());
        }
    }

    private boolean inCooldown(Long repoId) {
        if (repoId == null || redisTemplate == null) {
            return false;
        }
        try {
            Boolean has = redisTemplate.hasKey(COOLDOWN_PREFIX + repoId);
            return Boolean.TRUE.equals(has);
        } catch (Exception e) {
            log.warn("stack probe cooldown check failed repoId={}: {}", repoId, e.getMessage());
            return false;
        }
    }

    private void clearCooldown(Long repoId) {
        if (repoId == null || redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(COOLDOWN_PREFIX + repoId);
        } catch (Exception e) {
            log.warn("stack probe cooldown clear failed repoId={}: {}", repoId, e.getMessage());
        }
    }

    private boolean isGlobalIdleActive(long nowMs) {
        if (!properties.isStackProbeGlobalIdle() || redisTemplate == null) {
            return false;
        }
        try {
            String v = redisTemplate.opsForValue().get(IDLE_UNTIL_KEY);
            if (!StringUtils.hasText(v)) {
                return false;
            }
            long until = Long.parseLong(v.trim());
            return nowMs < until;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeGlobalIdle(long untilMs) {
        if (!properties.isStackProbeGlobalIdle() || redisTemplate == null) {
            return;
        }
        try {
            long ttl = Math.max(1_000L, untilMs - System.currentTimeMillis());
            redisTemplate.opsForValue().set(IDLE_UNTIL_KEY, String.valueOf(untilMs), Duration.ofMillis(ttl));
        } catch (Exception e) {
            log.warn("stack probe global idle write failed: {}", e.getMessage());
        }
    }

    private void clearGlobalIdle() {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(IDLE_UNTIL_KEY);
        } catch (Exception e) {
            log.warn("stack probe global idle clear failed: {}", e.getMessage());
        }
    }

    /** 测试可见 */
    long getNextProbeAtMs() {
        return nextProbeAtMs.get();
    }

    int getEmptyStreak() {
        return emptyStreak.get();
    }
}
