package com.company.codeinsight.modules.scanwindow.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.cluster.ClusterLeaderLock;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.config.ScanProperties;
import com.company.codeinsight.modules.repository.dto.GitRemoteHeadResult;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.repository.service.RepoGitConnectivityService;
import com.company.codeinsight.modules.scanwindow.entity.ScanProbeRecordEntity;
import com.company.codeinsight.modules.scanwindow.entity.ScanWindowEntity;
import com.company.codeinsight.modules.scanwindow.service.ScanProbeRecordService;
import com.company.codeinsight.modules.scanwindow.service.ScanWindowService;
import com.company.codeinsight.modules.scanwindow.support.ScanDailyCoverageStore;
import com.company.codeinsight.modules.scanwindow.support.ScanDispatchAction;
import com.company.codeinsight.modules.scanwindow.support.ScanDispatchDecision;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.exception.ErrorCode;
import com.company.codeinsight.modules.scanwindow.support.ScanProbeStatus;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时 commit 轮询扫描：Leader 探测远端 HEAD，按基线决策下发 INITIAL / INCREMENTAL。
 * <p>全局模式默认按自然日覆盖：失败/超时不记完成，后续 tick 重试，直至当日全部探测完毕。
 * 见 docs/scheduled-commit-poll-scan-plan.md。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanWindowScheduler {

    public static final String LEADER_LOCK_KEY = "ci:leader:scan-commit-poll";

    private static final String REDIS_KEY_CRON = "scan:scheduler:cron";
    private static final String REDIS_KEY_ENABLED = "scan:scheduler:enabled";
    private static final DateTimeFormatter SLOT_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            TaskStatus.FAILED.name(),
            TaskStatus.CANCELLED.name(),
            TaskStatus.ARCHIVED.name(),
            TaskStatus.PUSHED.name()
    );

    private final ScanWindowService scanWindowService;
    private final DecompileTaskService decompileTaskService;
    private final CodeRepositoryMapper repositoryMapper;
    private final RepoGitConnectivityService gitConnectivityService;
    private final ScanDailyCoverageStore coverageStore;
    private final ScanProbeRecordService probeRecordService;
    private final TaskScheduler taskScheduler;
    private final ScanProperties scanProperties;
    private final ClusterLeaderLock clusterLeaderLock;
    private final ClusterProperties clusterProperties;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private volatile boolean enabled = true;
    private volatile String currentCron = "0 */5 * * * *";
    private volatile ScheduledFuture<?> scheduledFuture;

    private final AtomicBoolean tickRunning = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        enabled = scanProperties.isEnabled();
        currentCron = StringUtils.hasText(scanProperties.getCron())
                ? scanProperties.getCron() : "0 */5 * * * *";

        if (redisTemplate != null) {
            try {
                String redisCron = redisTemplate.opsForValue().get(REDIS_KEY_CRON);
                if (StringUtils.hasText(redisCron)) {
                    currentCron = redisCron;
                }
                String redisEnabled = redisTemplate.opsForValue().get(REDIS_KEY_ENABLED);
                if (redisEnabled != null) {
                    enabled = Boolean.parseBoolean(redisEnabled);
                }
            } catch (Exception e) {
                log.warn("ScanWindowScheduler 从 Redis 恢复配置失败，使用本地默认 — {}", e.toString());
            }
        }
        start();
    }

    private void start() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        if (!enabled) {
            log.info("ScanWindowScheduler disabled");
            return;
        }
        try {
            CronExpression.parse(currentCron);
            scheduledFuture = taskScheduler.schedule(this::tick, new CronTrigger(currentCron));
            log.info("ScanWindowScheduler started: cron={} enabled={} globalPoll={} forceFull={} dailyCoverage={}",
                    currentCron, enabled,
                    scanProperties.isGlobalPollEnabled(),
                    scanProperties.isForceFullOnUnchanged(),
                    scanProperties.isDailyCoverageEnabled());
        } catch (Exception e) {
            log.error("Invalid cron: {}", currentCron, e);
        }
    }

    public String getCurrentCron() {
        return currentCron;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> getNextRuns(int count) {
        List<String> out = new ArrayList<>();
        try {
            CronExpression expr = CronExpression.parse(currentCron);
            LocalDateTime t = LocalDateTime.now();
            for (int i = 0; i < count; i++) {
                t = expr.next(t);
                if (t == null) {
                    break;
                }
                out.add(t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
        } catch (Exception ignored) {
            // ignore
        }
        return out;
    }

    public void updateCron(String cron) {
        CronExpression.parse(cron);
        currentCron = cron;
        if (redisTemplate != null) {
            redisTemplate.opsForValue().set(REDIS_KEY_CRON, cron);
        }
        start();
    }

    public void setEnabled(boolean e) {
        enabled = e;
        if (redisTemplate != null) {
            redisTemplate.opsForValue().set(REDIS_KEY_ENABLED, String.valueOf(e));
        }
        if (e) {
            start();
        } else {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }
            scheduledFuture = null;
        }
    }

    /* ============================== 调度核心 ============================== */

    public void tick() {
        if (!enabled) {
            return;
        }
        if (!tickRunning.compareAndSet(false, true)) {
            log.info("ScanWindowScheduler previous tick still running, skip");
            return;
        }
        try {
            if (!shouldRunAsLeader()) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            String slot = now.format(SLOT_FMT);
            boolean globalPoll = scanProperties.isGlobalPollEnabled();
            List<CodeRepository> candidates = globalPoll
                    ? loadGlobalCandidates()
                    : loadWindowMatchedCandidates(now);
            if (candidates.isEmpty()) {
                if (globalPoll && scanProperties.isDailyCoverageEnabled()) {
                    long target = probeRecordService.countProbeTargets();
                    long done = coverageStore.doneCount();
                    if (target > 0 && done >= target) {
                        log.info("scan daily coverage complete: done≈{}/target={}", done, target);
                    }
                }
                return;
            }

            int concurrency = Math.max(1, scanProperties.getPollConcurrency());
            int maxSweepSec = Math.max(1, scanProperties.getMaxSweepSeconds());
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxSweepSec);
            AtomicInteger fired = new AtomicInteger();
            AtomicInteger probed = new AtomicInteger();
            AtomicInteger retryLater = new AtomicInteger();

            int i = 0;
            while (i < candidates.size()) {
                // 到点后不再开新波次；已启动波次会等完（不 cancel），保证进行中的探测不半截丢弃
                if (i > 0 && System.nanoTime() >= deadlineNanos) {
                    log.info("scan poll wall-clock budget reached ({}s), stop new waves; processed={}/{}",
                            maxSweepSec, i, candidates.size());
                    break;
                }
                renewLeader();
                int end = Math.min(i + concurrency, candidates.size());
                List<CodeRepository> wave = candidates.subList(i, end);
                List<CompletableFuture<Void>> futures = new ArrayList<>(wave.size());
                for (CodeRepository repo : wave) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            ProcessResult r = processOne(repo, now, slot);
                            switch (r) {
                                case FIRED -> fired.incrementAndGet();
                                case PROBED -> probed.incrementAndGet();
                                case RETRY_LATER -> retryLater.incrementAndGet();
                            }
                        } catch (Exception e) {
                            retryLater.incrementAndGet();
                            log.error("scan poll failed repoId={}", repo.getId(), e);
                        } finally {
                            renewLeader();
                        }
                    }));
                }
                try {
                    // 等当前波次全部结束，避免 cancel 导致探测结果丢失
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                } catch (Exception e) {
                    log.warn("scan poll wave join failed: {}", e.toString());
                }
                i = end;
            }

            long done = coverageStore.doneCount();
            long target = globalPoll ? probeRecordService.countProbeTargets() : -1L;
            log.info("scan poll tick: mode={} candidates={} fired={} probed={} retryLater={} coverage≈{}/target={}",
                    globalPoll ? "GLOBAL" : "WINDOW",
                    candidates.size(), fired.get(), probed.get(), retryLater.get(),
                    done, target >= 0 ? target : "?");
        } finally {
            tickRunning.set(false);
        }
    }

    private boolean shouldRunAsLeader() {
        if (!clusterProperties.isEnabled()) {
            return true;
        }
        return clusterLeaderLock.tryAcquireLeader(LEADER_LOCK_KEY);
    }

    private void renewLeader() {
        if (!clusterProperties.isEnabled()) {
            return;
        }
        try {
            clusterLeaderLock.tryAcquireLeader(LEADER_LOCK_KEY);
        } catch (Exception ignored) {
            // ignore renew failures
        }
    }

    /**
     * 全局候选：优先「今日尚未探测完成」的需探测仓；非探测目标（本地/空 URL）顺手记流水并 markDone。
     */
    private List<CodeRepository> loadGlobalCandidates() {
        int limit = Math.max(1, Math.min(scanProperties.getPollBatchSize(), 500));
        List<CodeRepository> out = new ArrayList<>(limit);
        long afterId = 0L;
        int pageSize = Math.min(200, Math.max(limit, 50));
        int guard = 0;
        boolean useCoverage = scanProperties.isDailyCoverageEnabled();
        while (out.size() < limit && guard++ < 50) {
            List<CodeRepository> page = repositoryMapper.selectList(new LambdaQueryWrapper<CodeRepository>()
                    .gt(CodeRepository::getId, afterId)
                    .orderByAsc(CodeRepository::getId)
                    .last("LIMIT " + pageSize));
            if (page.isEmpty()) {
                break;
            }
            for (CodeRepository repo : page) {
                afterId = repo.getId() != null ? repo.getId() : afterId;
                if (useCoverage && coverageStore.isDone(repo.getId())) {
                    continue;
                }
                if (!isProbeTarget(repo.getGitUrl())) {
                    if (useCoverage && !coverageStore.isDone(repo.getId())) {
                        writeProbeRecord(repo, ScanProbeStatus.SKIPPED_LOCAL, null, null, "NONE", null,
                                isLocalPathRepo(repo.getGitUrl()) ? "本地路径，跳过远程探测" : "Git 地址为空");
                        markCoveredIfEnabled(repo.getId());
                    }
                    continue;
                }
                out.add(repo);
                if (out.size() >= limit) {
                    break;
                }
            }
            if (page.size() < pageSize) {
                break;
            }
        }
        return out;
    }

    private List<CodeRepository> loadWindowMatchedCandidates(LocalDateTime now) {
        int todayBit = dayBit(now.getDayOfWeek());
        int hour = now.getHour();
        int minute = now.getMinute();
        List<ScanWindowEntity> windows;
        try {
            windows = scanWindowService.listEnabled();
        } catch (Exception e) {
            return List.of();
        }
        if (windows.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, scanProperties.getPollBatchSize());
        List<CodeRepository> out = new ArrayList<>();
        for (ScanWindowEntity w : windows) {
            if (out.size() >= limit) {
                break;
            }
            if ((w.getWeekDays() & todayBit) == 0) {
                continue;
            }
            if (!w.getHour().equals(hour) || !w.getMinute().equals(minute)) {
                continue;
            }
            if (w.getLastFiredAt() != null) {
                LocalDateTime last = w.getLastFiredAt();
                if (last.getDayOfWeek() == now.getDayOfWeek()
                        && last.getHour() == hour && last.getMinute() == minute) {
                    continue;
                }
            }
            CodeRepository repo = repositoryMapper.selectById(w.getRepositoryId());
            if (repo != null) {
                out.add(repo);
            }
        }
        return out;
    }

    enum ProcessResult {
        /** 已建任务并 start */
        FIRED,
        /** 探测完成（含无变动跳过下发 / 本地路径 / 明确失败），当日不再重探 */
        PROBED,
        /** 超时/抢锁失败等，当日后续 tick 重试 */
        RETRY_LATER
    }

    ProcessResult processOne(CodeRepository repo, LocalDateTime now, String slot) {
        if (repo == null || repo.getId() == null) {
            return ProcessResult.RETRY_LATER;
        }
        if (scanProperties.isGlobalPollEnabled()
                && scanProperties.isDailyCoverageEnabled()
                && coverageStore.isDone(repo.getId())) {
            return ProcessResult.PROBED;
        }

        // 本地路径 / 空 URL：不计入需探测总量；流水审计 + 记 done 以免空转
        if (isLocalPathRepo(repo.getGitUrl())) {
            log.info("scan mark done local-path repoId={}", repo.getId());
            writeProbeRecord(repo, ScanProbeStatus.SKIPPED_LOCAL, null, null, "NONE", null, "本地路径，跳过远程探测");
            markCoveredIfEnabled(repo.getId());
            return ProcessResult.PROBED;
        }
        if (!StringUtils.hasText(repo.getGitUrl())) {
            log.warn("scan mark done empty gitUrl repoId={}", repo.getId());
            writeProbeRecord(repo, ScanProbeStatus.SKIPPED_LOCAL, null, null, "NONE", null, "Git 地址为空");
            markCoveredIfEnabled(repo.getId());
            return ProcessResult.PROBED;
        }

        String lockKey = "scan:fire:" + repo.getId() + ":" + slot;
        if (!tryLock(lockKey)) {
            return ProcessResult.RETRY_LATER;
        }
        try {
            GitRemoteHeadResult headResult = gitConnectivityService.resolveRemoteHeadWithRetry(
                    repo,
                    scanProperties.getProbeTimeoutSeconds(),
                    scanProperties.getProbeMaxAttempts(),
                    scanProperties.getProbeRetryBackoffMs());
            if (headResult == null || !StringUtils.hasText(headResult.getHeadCommit())) {
                if (headResult != null && headResult.isInconclusive()) {
                    log.warn("scan probe inconclusive repoId={} msg={}",
                            repo.getId(), headResult.getMessage());
                    writeProbeRecord(repo, ScanProbeStatus.INCONCLUSIVE, null, repo.getLastCommitId(),
                            null, null, headResult.getMessage());
                    releaseLock(lockKey);
                    return ProcessResult.RETRY_LATER;
                }
                String msg = headResult != null ? headResult.getMessage() : "null";
                log.error("scan probe definitive fail repoId={} msg={}", repo.getId(), msg);
                writeProbeRecord(repo, ScanProbeStatus.FAILED, null, repo.getLastCommitId(),
                        "NONE", null, msg);
                markCoveredIfEnabled(repo.getId());
                return ProcessResult.PROBED;
            }

            String head = headResult.getHeadCommit();

            if (hasBlockingNonTerminalTask(repo.getId())) {
                log.info("scan probed but skip dispatch (blocking task) repoId={} head={}",
                        repo.getId(), head);
                writeProbeRecord(repo, ScanProbeStatus.SUCCESS, head, repo.getLastCommitId(),
                        "NONE", null, "同仓存在未终态任务，跳过下发");
                markCoveredIfEnabled(repo.getId());
                return ProcessResult.PROBED;
            }

            boolean hasBaseline = ScanDispatchDecision.hasBaseline(
                    repo.getLastPublishedVersionId(), repo.getLastCommitId());
            ScanDispatchAction action = ScanDispatchDecision.decide(
                    hasBaseline, head, repo.getLastCommitId(),
                    scanProperties.isForceFullOnUnchanged());
            if (action == ScanDispatchAction.SKIP) {
                log.debug("scan probed unchanged repoId={} head={}", repo.getId(), head);
                writeProbeRecord(repo, ScanProbeStatus.SUCCESS, head, repo.getLastCommitId(),
                        "NONE", null, "HEAD 无变化，不下发");
                markCoveredIfEnabled(repo.getId());
                return ProcessResult.PROBED;
            }

            try {
                DecompileTask task;
                if (action == ScanDispatchAction.INITIAL) {
                    task = decompileTaskService.createInitialTask(
                            repo.getSystemId(), repo.getId(),
                            null, null, null, null,
                            Boolean.FALSE, Boolean.FALSE, "SCHEDULED");
                } else {
                    task = decompileTaskService.createIncrementalTask(
                            repo.getSystemId(), repo.getId(),
                            null, null, null, null,
                            Boolean.FALSE, Boolean.FALSE, "SCHEDULED");
                }
                decompileTaskService.startTask(task.getId());
                log.info("scan fire ok: repoId={} taskId={} action={} → PENDING",
                        repo.getId(), task.getId(), action);
                writeProbeRecord(repo, ScanProbeStatus.SUCCESS, head, repo.getLastCommitId(),
                        action.name(), task.getId(), null);
                markCoveredIfEnabled(repo.getId());
                touchWindowLastFired(repo.getId(), now);
                return ProcessResult.FIRED;
            } catch (Exception e) {
                // 技术栈未打标/不支持等：不记日覆盖，释放锁，后续 tick 可再下发
                String msg = formatDispatchDeferMessage(e);
                log.warn("scan dispatch deferred repoId={} action={} — {}", repo.getId(), action, msg);
                writeProbeRecord(repo, ScanProbeStatus.DEFERRED_DISPATCH, head, repo.getLastCommitId(),
                        action.name(), null, msg);
                releaseLock(lockKey);
                return ProcessResult.RETRY_LATER;
            }
        } catch (Exception e) {
            log.error("scan process failed repoId={}", repo.getId(), e);
            writeProbeRecord(repo, ScanProbeStatus.INCONCLUSIVE, null, repo.getLastCommitId(),
                    null, null, truncateMsg(e));
            releaseLock(lockKey);
            return ProcessResult.RETRY_LATER;
        }
    }

    /** 下发延期说明（技术栈门禁优先展示错误码语义）。 */
    static String formatDispatchDeferMessage(Throwable e) {
        if (e instanceof BusinessException be) {
            int code = be.getCode();
            if (code == ErrorCode.TECH_STACK_NOT_CONFIGURED.getCode()) {
                return "技术栈未配置，等待打标后重试: " + truncateMsg(e);
            }
            if (code == ErrorCode.TECH_STACK_UNSUPPORTED.getCode()) {
                return "技术栈暂不支持下发，后续批次重试: " + truncateMsg(e);
            }
            if (code == ErrorCode.GIT_UNREACHABLE.getCode()) {
                return "Git 未连通，后续批次重试: " + truncateMsg(e);
            }
            return "下发暂缓(code=" + code + "): " + truncateMsg(e);
        }
        return "下发暂缓: " + truncateMsg(e);
    }

    private void writeProbeRecord(CodeRepository repo, String status, String remoteHead,
                                  String baseline, String dispatchAction, Long taskId, String message) {
        ScanProbeRecordEntity row = new ScanProbeRecordEntity();
        row.setRepositoryId(repo.getId());
        row.setSystemId(repo.getSystemId());
        row.setStatus(status);
        row.setRemoteHead(remoteHead);
        row.setBaselineCommit(baseline);
        row.setDispatchAction(dispatchAction);
        row.setTaskId(taskId);
        row.setMessage(message);
        probeRecordService.insert(row);
    }

    private static String truncateMsg(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }

    private void markCoveredIfEnabled(Long repositoryId) {
        if (scanProperties.isGlobalPollEnabled() && scanProperties.isDailyCoverageEnabled()) {
            coverageStore.markDone(repositoryId);
        }
    }

    private void touchWindowLastFired(Long repositoryId, LocalDateTime now) {
        try {
            ScanWindowEntity existing = scanWindowService.getByRepository(repositoryId);
            if (existing == null) {
                return;
            }
            existing.setLastFiredAt(now);
            scanWindowService.upsert(existing);
        } catch (Exception e) {
            log.warn("scan update lastFiredAt failed repoId={}: {}", repositoryId, e.toString());
        }
    }

    boolean hasBlockingNonTerminalTask(Long repositoryId) {
        Long count = decompileTaskService.lambdaQuery()
                .eq(DecompileTask::getRepositoryId, repositoryId)
                .notIn(DecompileTask::getStatus, TERMINAL_STATUSES)
                .count();
        return count != null && count > 0;
    }

    static boolean isProbeTarget(String gitUrl) {
        return StringUtils.hasText(gitUrl) && !isLocalPathRepo(gitUrl);
    }

    static boolean isLocalPathRepo(String gitUrl) {
        if (!StringUtils.hasText(gitUrl)) {
            return false;
        }
        File probe = new File(gitUrl.trim());
        return probe.exists() && probe.isDirectory();
    }

    private boolean tryLock(String key) {
        if (redisTemplate == null) {
            return !clusterProperties.isEnabled();
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", java.time.Duration.ofMinutes(2)));
        } catch (Exception e) {
            return false;
        }
    }

    private void releaseLock(String key) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(key);
        } catch (Exception ignored) {
            // ignore
        }
    }

    static int dayBit(DayOfWeek d) {
        return 1 << (d.getValue() - 1);
    }
}
