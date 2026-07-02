package com.company.codeinsight.modules.entrypoint.trial;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.response.PageResult;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.modules.entrypoint.model.DiscoveredEntrypoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfigCodec;
import com.company.codeinsight.modules.entrypoint.service.EntryPointDiscoveryService;
import com.company.codeinsight.modules.scanner.model.ScanResult;
import com.company.codeinsight.modules.scanner.service.CodeScannerService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrialRunServiceImpl implements TrialRunService {

    private static final String LOCK_KEY_PREFIX = "trial-run:repo:";
    /** Redis 锁 TTL：30 分钟 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);
    /** 超过该时间未更新的 PENDING/RUNNING 视为僵尸试跑 */
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(35);
    private static final int HISTORY_RETENTION_DAYS = 30;
    private static final int MAX_HISTORY_PER_REPO = 50;

    private static final String STALE_ERROR = "试跑中断或超时（服务重启/进程异常）";

    private final EntryScanTrialMapper trialMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    @Lazy
    private EntryPointDiscoveryService entryPointDiscoveryService;
    @Autowired
    @Lazy
    private CodeScannerService codeScannerService;
    @Autowired
    private TaskWorkspacePaths taskWorkspacePaths;

    @PostConstruct
    public void onStartup() {
        reconcileStaleTrials();
    }

    @Scheduled(fixedDelayString = "${code-insight.trial.reconcile-interval-ms:300000}")
    public void scheduledReconcile() {
        reconcileStaleTrials();
    }

    @Scheduled(cron = "${code-insight.trial.cleanup-cron:0 0 3 * * ?}")
    public void scheduledCleanup() {
        cleanupOldTrials();
    }

    @Override
    @Transactional
    public EntryScanTrialEntity trigger(Long systemId, Long repositoryId, EntryPointConfig config, String operator) {
        reconcileStaleForRepo(repositoryId);

        EntryScanTrialEntity active = findActiveTrial(repositoryId);
        if (active != null) {
            throw new BusinessException("该仓库有试跑任务正在执行（#" + active.getId() + "），请稍候完成或取消后再试");
        }

        String lockKey = LOCK_KEY_PREFIX + repositoryId;
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "pending", LOCK_TTL.toSeconds(), TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(acquired)) {
            throw new BusinessException("该仓库有试跑任务正在执行，请稍候完成后再试");
        }

        try {
            EntryScanTrialEntity trial = new EntryScanTrialEntity();
            trial.setSystemId(systemId);
            trial.setRepositoryId(repositoryId);
            trial.setUserId(operator);
            trial.setStatus(EntryScanTrialEntity.STATUS_PENDING);
            trial.setConfigSnapshot(EntryPointConfigCodec.encode(config));
            trial.setStartedAt(LocalDateTime.now());
            trial.setCreatedAt(LocalDateTime.now());
            trial.setUpdatedAt(LocalDateTime.now());
            trialMapper.insert(trial);

            final Long trialId = trial.getId();
            stringRedisTemplate.opsForValue().set(lockKey, String.valueOf(trialId),
                    LOCK_TTL.toSeconds(), TimeUnit.SECONDS);

            CompletableFuture.runAsync(() -> executeAsync(trialId));
            return trial;
        } catch (RuntimeException e) {
            safeUnlock(lockKey, null);
            throw e;
        }
    }

    @Override
    public void executeAsync(Long trialId) {
        String lockKey = null;
        try {
            EntryScanTrialEntity trial = trialMapper.selectById(trialId);
            if (trial == null) return;
            if (isTerminal(trial.getStatus())) return;

            lockKey = LOCK_KEY_PREFIX + trial.getRepositoryId();
            renewLock(lockKey, trialId);
            log.info("trial run start: trialId={} repoId={} sysId={}", trialId, trial.getRepositoryId(), trial.getSystemId());

            updateStatus(trialId, EntryScanTrialEntity.STATUS_RUNNING, null, null, null);
            renewLock(lockKey, trialId);

            ScanResult scanResult = codeScannerService.pullAndScan(
                    trialId, trial.getRepositoryId(), "INITIAL");
            File projectDir = scanResult.getProjectDir();
            renewLock(lockKey, trialId);

            EntryPointConfig config = EntryPointConfigCodec.decode(trial.getConfigSnapshot());
            List<DiscoveredEntrypoint> discovered = entryPointDiscoveryService
                    .discoverEntriesWithMethods(trialId, projectDir, config);

            String resultJson = objectMapper.writeValueAsString(discovered);
            if (canUpdateRunningTrial(trialId)) {
                updateStatus(trialId, EntryScanTrialEntity.STATUS_SUCCESS, LocalDateTime.now(), resultJson, null);
                log.info("trial run success: trialId={} entryCount={}", trialId, discovered.size());
            } else {
                log.info("trial run finished but skipped status update (cancelled/stale): trialId={}", trialId);
            }
        } catch (Exception e) {
            log.error("trial run failed: trialId={}", trialId, e);
            if (canUpdateRunningTrial(trialId)) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (msg.length() > 1000) msg = msg.substring(0, 1000);
                updateStatus(trialId, EntryScanTrialEntity.STATUS_FAILED, LocalDateTime.now(), null, msg);
            }
        } finally {
            if (lockKey != null) safeUnlock(lockKey, null);
        }
    }

    @Override
    public EntryScanTrialEntity get(Long trialId) {
        return trialMapper.selectById(trialId);
    }

    @Override
    public EntryScanTrialEntity getActive(Long repositoryId) {
        reconcileStaleForRepo(repositoryId);
        return findActiveTrial(repositoryId);
    }

    @Override
    public EntryScanTrialSummary getLatestSummary(Long repositoryId) {
        EntryScanTrialEntity trial = trialMapper.selectOne(
                new LambdaQueryWrapper<EntryScanTrialEntity>()
                        .eq(EntryScanTrialEntity::getRepositoryId, repositoryId)
                        .orderByDesc(EntryScanTrialEntity::getStartedAt)
                        .last("LIMIT 1"));
        return trial == null ? null : toSummary(trial);
    }

    @Override
    public PageResult<EntryScanTrialSummary> listHistory(Long repositoryId, long current, long size) {
        long page = Math.max(1, current);
        long pageSize = Math.min(Math.max(1, size), 100);
        Page<EntryScanTrialEntity> pg = trialMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<EntryScanTrialEntity>()
                        .eq(EntryScanTrialEntity::getRepositoryId, repositoryId)
                        .orderByDesc(EntryScanTrialEntity::getStartedAt));
        List<EntryScanTrialSummary> records = pg.getRecords().stream().map(this::toSummary).toList();
        return new PageResult<>(pg.getTotal(), pg.getSize(), pg.getCurrent(), records);
    }

    @Override
    public boolean isLocked(Long repositoryId) {
        reconcileStaleForRepo(repositoryId);
        if (findActiveTrial(repositoryId) != null) return true;
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(LOCK_KEY_PREFIX + repositoryId));
    }

    @Override
    @Transactional
    public boolean cancel(Long trialId, String operator) {
        EntryScanTrialEntity trial = trialMapper.selectById(trialId);
        if (trial == null) return false;
        if (isTerminal(trial.getStatus())) return false;

        updateStatus(trialId, EntryScanTrialEntity.STATUS_CANCELLED, LocalDateTime.now(), null,
                "用户(" + (operator == null ? "?" : operator) + ")取消");
        safeUnlock(LOCK_KEY_PREFIX + trial.getRepositoryId(), null);
        cleanupWorkspace(trialId);
        return true;
    }

    @Override
    public EntryPointConfig parseConfigSnapshot(String configSnapshot) {
        return EntryPointConfigCodec.decode(configSnapshot);
    }

    @Override
    public List<DiscoveredEntrypoint> parseResultEntries(String resultJson) {
        if (resultJson == null || resultJson.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(resultJson, new TypeReference<List<DiscoveredEntrypoint>>() {});
        } catch (Exception e) {
            log.warn("parseResultEntries failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    @Transactional
    public void reconcileStaleTrials() {
        LocalDateTime cutoff = LocalDateTime.now().minus(STALE_THRESHOLD);
        List<EntryScanTrialEntity> stale = trialMapper.selectList(
                new LambdaQueryWrapper<EntryScanTrialEntity>()
                        .in(EntryScanTrialEntity::getStatus,
                                EntryScanTrialEntity.STATUS_PENDING,
                                EntryScanTrialEntity.STATUS_RUNNING)
                        .lt(EntryScanTrialEntity::getUpdatedAt, cutoff));
        for (EntryScanTrialEntity trial : stale) {
            markStaleFailed(trial);
        }
        if (!stale.isEmpty()) {
            log.info("trial reconcile: marked {} stale runs as FAILED", stale.size());
        }
    }

    private void reconcileStaleForRepo(Long repositoryId) {
        LocalDateTime cutoff = LocalDateTime.now().minus(STALE_THRESHOLD);
        List<EntryScanTrialEntity> stale = trialMapper.selectList(
                new LambdaQueryWrapper<EntryScanTrialEntity>()
                        .eq(EntryScanTrialEntity::getRepositoryId, repositoryId)
                        .in(EntryScanTrialEntity::getStatus,
                                EntryScanTrialEntity.STATUS_PENDING,
                                EntryScanTrialEntity.STATUS_RUNNING)
                        .lt(EntryScanTrialEntity::getUpdatedAt, cutoff));
        stale.forEach(this::markStaleFailed);
    }

    private void markStaleFailed(EntryScanTrialEntity trial) {
        updateStatus(trial.getId(), EntryScanTrialEntity.STATUS_FAILED, LocalDateTime.now(), null, STALE_ERROR);
        safeUnlock(LOCK_KEY_PREFIX + trial.getRepositoryId(), null);
        cleanupWorkspace(trial.getId());
        log.warn("trial stale reconciled: trialId={} repoId={}", trial.getId(), trial.getRepositoryId());
    }

    private void cleanupOldTrials() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(HISTORY_RETENTION_DAYS);
        int deleted = trialMapper.delete(
                new LambdaQueryWrapper<EntryScanTrialEntity>()
                        .lt(EntryScanTrialEntity::getStartedAt, cutoff)
                        .in(EntryScanTrialEntity::getStatus,
                                EntryScanTrialEntity.STATUS_SUCCESS,
                                EntryScanTrialEntity.STATUS_FAILED,
                                EntryScanTrialEntity.STATUS_CANCELLED));
        if (deleted > 0) {
            log.info("trial cleanup: deleted {} records older than {} days", deleted, HISTORY_RETENTION_DAYS);
        }
        trimPerRepoHistory();
    }

    private void trimPerRepoHistory() {
        List<Long> repoIds = trialMapper.listDistinctRepositoryIds();
        if (repoIds == null) return;
        for (Long repoId : repoIds) {
            if (repoId == null) continue;
            List<EntryScanTrialEntity> all = trialMapper.selectList(
                    new LambdaQueryWrapper<EntryScanTrialEntity>()
                            .eq(EntryScanTrialEntity::getRepositoryId, repoId)
                            .orderByDesc(EntryScanTrialEntity::getStartedAt)
                            .select(EntryScanTrialEntity::getId));
            if (all.size() <= MAX_HISTORY_PER_REPO) continue;
            List<Long> toDelete = all.subList(MAX_HISTORY_PER_REPO, all.size()).stream()
                    .map(EntryScanTrialEntity::getId).toList();
            trialMapper.delete(new LambdaQueryWrapper<EntryScanTrialEntity>()
                    .in(EntryScanTrialEntity::getId, toDelete));
            log.info("trial trim: repoId={} deleted {} excess history records", repoId, toDelete.size());
        }
    }

    private EntryScanTrialEntity findActiveTrial(Long repositoryId) {
        return trialMapper.selectOne(
                new LambdaQueryWrapper<EntryScanTrialEntity>()
                        .eq(EntryScanTrialEntity::getRepositoryId, repositoryId)
                        .in(EntryScanTrialEntity::getStatus,
                                EntryScanTrialEntity.STATUS_PENDING,
                                EntryScanTrialEntity.STATUS_RUNNING)
                        .orderByDesc(EntryScanTrialEntity::getStartedAt)
                        .last("LIMIT 1"));
    }

    private EntryScanTrialSummary toSummary(EntryScanTrialEntity trial) {
        EntryScanTrialSummary s = new EntryScanTrialSummary();
        s.setId(trial.getId());
        s.setRepositoryId(trial.getRepositoryId());
        s.setUserId(trial.getUserId());
        s.setStatus(trial.getStatus());
        s.setStartedAt(trial.getStartedAt());
        s.setFinishedAt(trial.getFinishedAt());
        s.setErrorMessage(trial.getErrorMessage());
        if (EntryScanTrialEntity.STATUS_SUCCESS.equals(trial.getStatus())) {
            s.setEntryCount(countEntries(trial.getResultJson()));
        }
        return s;
    }

    private int countEntries(String resultJson) {
        return parseResultEntries(resultJson).size();
    }

    private boolean canUpdateRunningTrial(Long trialId) {
        EntryScanTrialEntity trial = trialMapper.selectById(trialId);
        if (trial == null) return false;
        return EntryScanTrialEntity.STATUS_PENDING.equals(trial.getStatus())
                || EntryScanTrialEntity.STATUS_RUNNING.equals(trial.getStatus());
    }

    private boolean isTerminal(String status) {
        return EntryScanTrialEntity.STATUS_SUCCESS.equals(status)
                || EntryScanTrialEntity.STATUS_FAILED.equals(status)
                || EntryScanTrialEntity.STATUS_CANCELLED.equals(status);
    }

    private void updateStatus(Long trialId, String status, LocalDateTime finishedAt,
                              String resultJson, String errorMessage) {
        LambdaUpdateWrapper<EntryScanTrialEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(EntryScanTrialEntity::getId, trialId)
                .set(EntryScanTrialEntity::getStatus, status)
                .set(EntryScanTrialEntity::getUpdatedAt, LocalDateTime.now());
        if (finishedAt != null) {
            uw.set(EntryScanTrialEntity::getFinishedAt, finishedAt);
        } else if (isTerminal(status)) {
            uw.set(EntryScanTrialEntity::getFinishedAt, LocalDateTime.now());
        }
        if (resultJson != null) uw.set(EntryScanTrialEntity::getResultJson, resultJson);
        if (errorMessage != null) uw.set(EntryScanTrialEntity::getErrorMessage, errorMessage);
        trialMapper.update(null, uw);
    }

    private void renewLock(String lockKey, Long trialId) {
        try {
            stringRedisTemplate.opsForValue().set(lockKey, String.valueOf(trialId),
                    LOCK_TTL.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("trial renew lock failed key={} err={}", lockKey, e.getMessage());
        }
    }

    private void safeUnlock(String key, String expectedValue) {
        try {
            if (expectedValue == null) {
                stringRedisTemplate.delete(key);
            } else {
                String current = stringRedisTemplate.opsForValue().get(key);
                if (expectedValue.equals(current)) {
                    stringRedisTemplate.delete(key);
                }
            }
        } catch (Exception e) {
            log.warn("trial unlock failed key={} err={}", key, e.getMessage());
        }
    }

    private void cleanupWorkspace(Long trialId) {
        try {
            File ws = taskWorkspacePaths.taskProjectDir(trialId);
            if (ws.exists()) {
                File[] children = ws.listFiles();
                if (children != null) {
                    for (File f : children) f.delete();
                }
                ws.delete();
            }
        } catch (Exception e) {
            log.warn("trial workspace cleanup failed trialId={}: {}", trialId, e.getMessage());
        }
    }
}
