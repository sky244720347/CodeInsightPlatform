package com.company.codeinsight.modules.entrypoint.trial;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.modules.entrypoint.model.DiscoveredEntrypoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfigCodec;
import com.company.codeinsight.modules.entrypoint.service.EntryPointDiscoveryService;
import com.company.codeinsight.modules.scanner.model.ScanResult;
import com.company.codeinsight.modules.scanner.service.CodeScannerService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
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

    /** 试跑 Redis 锁 key 前缀 */
    private static final String LOCK_KEY_PREFIX = "trial-run:repo:";
    /** 试跑 Redis 锁默认 TTL：30 分钟（超时自动释放，避免死锁） */
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private final EntryScanTrialMapper trialMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Lazy：避免与 EntryPointDiscoveryServiceImpl 形成循环依赖 */
    @Autowired
    @Lazy
    private EntryPointDiscoveryService entryPointDiscoveryService;
    /** Lazy：避免 TrialRun 与 ScannerService 循环依赖 */
    @Autowired
    @Lazy
    private CodeScannerService codeScannerService;
    @Autowired
    private TaskWorkspacePaths taskWorkspacePaths;

    @Override
    @Transactional
    public EntryScanTrialEntity trigger(Long systemId, Long repositoryId, EntryPointConfig config, String operator) {
        // 1. Redis 锁
        String lockKey = LOCK_KEY_PREFIX + repositoryId;
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "pending", LOCK_TTL.toSeconds(), TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(acquired)) {
            throw new BusinessException("该仓库有试跑任务正在执行，请稍候完成后再试");
        }

        // 2. 创建试跑记录（PENDING）
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

            // 3. 异步执行
            final Long trialId = trial.getId();
            CompletableFuture.runAsync(() -> executeAsync(trialId));
            return trial;
        } catch (RuntimeException e) {
            // 失败时立即释放锁
            safeUnlock(lockKey, "pending");
            throw e;
        }
    }

    @Override
    public void executeAsync(Long trialId) {
        String lockKey = null;
        try {
            EntryScanTrialEntity trial = trialMapper.selectById(trialId);
            if (trial == null) return;
            lockKey = LOCK_KEY_PREFIX + trial.getRepositoryId();
            log.info("trial run start: trialId={} repoId={} sysId={}", trialId, trial.getRepositoryId(), trial.getSystemId());

            // RUNNING
            updateStatus(trialId, EntryScanTrialEntity.STATUS_RUNNING, null, null, null);

            // 1. 拉代码 → ScanResult（trialId 借用 taskId 槽位写目录）
            ScanResult scanResult = codeScannerService.pullAndScan(
                    trialId, trial.getRepositoryId(), "INITIAL");
            File projectDir = scanResult.getProjectDir();

            // 2. 解析 entry_scan_config
            EntryPointConfig config = EntryPointConfigCodec.decode(trial.getConfigSnapshot());

            // 3. AST + 入口识别（带方法列表）
            List<DiscoveredEntrypoint> discovered = entryPointDiscoveryService
                    .discoverEntriesWithMethods(trialId, projectDir, config);

            // 4. 写结果（序列化 DiscoveredEntrypoint，前端需要 methods 字段渲染树）
            String resultJson = objectMapper.writeValueAsString(discovered);
            updateStatus(trialId, EntryScanTrialEntity.STATUS_SUCCESS, null, resultJson, null);
            log.info("trial run success: trialId={} entryCount={}", trialId, discovered.size());
        } catch (Exception e) {
            log.error("trial run failed: trialId={}", trialId, e);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg != null && msg.length() > 1000) msg = msg.substring(0, 1000);
            updateStatus(trialId, EntryScanTrialEntity.STATUS_FAILED, null, null, msg);
        } finally {
            // 5. 释放锁
            if (lockKey != null) safeUnlock(lockKey, null);
        }
    }

    @Override
    public EntryScanTrialEntity get(Long trialId) {
        return trialMapper.selectById(trialId);
    }

    @Override
    public boolean isLocked(Long repositoryId) {
        return Boolean.TRUE.equals(
                stringRedisTemplate.hasKey(LOCK_KEY_PREFIX + repositoryId));
    }

    @Override
    @Transactional
    public boolean cancel(Long trialId, String operator) {
        EntryScanTrialEntity trial = trialMapper.selectById(trialId);
        if (trial == null) return false;
        if (EntryScanTrialEntity.STATUS_SUCCESS.equals(trial.getStatus())
                || EntryScanTrialEntity.STATUS_FAILED.equals(trial.getStatus())
                || EntryScanTrialEntity.STATUS_CANCELLED.equals(trial.getStatus())) {
            return false;
        }
        updateStatus(trialId, EntryScanTrialEntity.STATUS_CANCELLED, LocalDateTime.now(), null,
                "用户(" + (operator == null ? "?" : operator) + ")取消");
        safeUnlock(LOCK_KEY_PREFIX + trial.getRepositoryId(), null);
        // 清理 workspace
        try {
            File ws = taskWorkspacePaths.taskProjectDir(trialId);
            if (ws.exists()) {
                java.io.File[] children = ws.listFiles();
                if (children != null) for (java.io.File f : children) f.delete();
                ws.delete();
            }
        } catch (Exception e) {
            log.warn("trial cancel cleanup failed trialId={}: {}", trialId, e.getMessage());
        }
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

    private void updateStatus(Long trialId, String status, LocalDateTime finishedAt,
                                 String resultJson, String errorMessage) {
        LambdaUpdateWrapper<EntryScanTrialEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(EntryScanTrialEntity::getId, trialId)
                .set(EntryScanTrialEntity::getStatus, status)
                .set(EntryScanTrialEntity::getUpdatedAt, LocalDateTime.now());
        if (finishedAt != null) uw.set(EntryScanTrialEntity::getFinishedAt, finishedAt);
        if (resultJson != null) uw.set(EntryScanTrialEntity::getResultJson, resultJson);
        if (errorMessage != null) uw.set(EntryScanTrialEntity::getErrorMessage, errorMessage);
        trialMapper.update(null, uw);
    }

    /** 释放锁：仅当当前值匹配时才删（防止误删其他会话的锁） */
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
}
