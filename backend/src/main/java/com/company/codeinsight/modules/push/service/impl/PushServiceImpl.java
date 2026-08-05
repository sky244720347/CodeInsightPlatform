package com.company.codeinsight.modules.push.service.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.auth.OperatorContext;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryPublishSnapshot;
import com.company.codeinsight.modules.repository.publish.service.RepositoryPublishService;
import com.company.codeinsight.common.util.DraftFileUtil;
import com.company.codeinsight.common.util.DraftPathValidator;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.draft.service.DraftService;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.push.entity.PushTask;
import com.company.codeinsight.modules.push.enums.PushMethod;
import com.company.codeinsight.modules.push.enums.PushTaskStatus;
import com.company.codeinsight.modules.push.mapper.PushTaskMapper;
import com.company.codeinsight.modules.push.service.PushService;
import com.company.codeinsight.modules.push.strategy.PushStrategy;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.TaskStateMachineService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * 知识推送服务实现
 *
 * 核心编排逻辑：
 * 1. enqueuePush：校验 + 入库 + 入 Redis 队列
 * 2. processNextTask：从 Redis 出队 → 分发策略 → 更新状态（含重试）
 * 3. listTasksByVersion：查询推送历史
 */
@Slf4j
@Service
public class PushServiceImpl implements PushService {

    /** Redis 队列 key */
    private static final String PUSH_QUEUE_KEY = "push:task:queue";

    /** Redis 分布式锁 key 前缀 */
    private static final String PUSH_LOCK_PREFIX = "push:task:lock:";

    /** 锁 TTL（秒） */
    private static final long LOCK_TTL_SECONDS = 30;

    @Autowired
    private KnowledgeVersionMapper versionMapper;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired
    private DraftWorkspaceMapper workspaceMapper;

    @Autowired
    private KnowledgeDraftMapper draftMapper;

    @Autowired
    private DraftService draftService;

    @Autowired
    private PushTaskMapper pushTaskMapper;

    @Autowired
    private TaskStateMachineService stateMachineService;

    @Autowired
    private List<PushStrategy> strategies;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private com.company.codeinsight.common.storage.EnvStorageResolver storageResolver;

    @Autowired
    private com.company.codeinsight.common.storage.TaskWorkspacePaths taskWorkspacePaths;

    @Autowired
    private com.company.codeinsight.modules.task.service.TaskDiskCleanupService taskDiskCleanupService;

    @Autowired
    private RepositoryPublishService repositoryPublishService;

    @Autowired
    private com.company.codeinsight.modules.push.retention.ReleaseRetentionService releaseRetentionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** pushMethod -> PushStrategy 的映射表 */
    private Map<PushMethod, PushStrategy> strategyMap;

    @PostConstruct
    private void initStrategyMap() {
        strategyMap = strategies.stream()
                .collect(Collectors.toMap(PushStrategy::getMethod, s -> s));
        log.info("已注册推送策略: {}", strategyMap.keySet());
    }

    // ==================== 入队 ====================

    @Override
    @Transactional
    public void enqueuePush(Long versionId, PushMethod method) {
        KnowledgeVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("未找到该版本记录");
        }

        if (!"DRAFT".equals(version.getStatus()) && !"FAILED".equals(version.getStatus())) {
            throw new BusinessException("版本状态为 " + version.getStatus() + "，不可推送。仅 DRAFT 或 FAILED 状态的版本可推送");
        }

        DecompileTask task = taskMapper.selectById(version.getTaskId());
        if (task == null) {
            throw new BusinessException("未找到关联的知识构建任务");
        }
        // 仅支持 NAS
        PushMethod effectiveMethod = PushMethod.NAS;

        draftService.assertTaskReadyForKnowledgePublish(task.getId());

        DraftWorkspace ws = workspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, version.getTaskId()));
        if (ws == null) {
            throw new BusinessException("草稿工作区不存在，无法推送");
        }

        validateDraftsReady(ws.getId(), version.getTaskId());

        PushTask pushTask = new PushTask();
        pushTask.setVersionId(versionId);
        pushTask.setPushMethod(effectiveMethod.name());
        pushTask.setStatus(PushTaskStatus.PENDING.name());
        pushTask.setRetryCount(0);
        pushTask.setMaxRetries(3);
        pushTask.setEnqueuedAt(LocalDateTime.now());
        pushTask.setCreatedDate(LocalDateTime.now());
        pushTaskMapper.insert(pushTask);

        version.setStatus("PUSHING");
        version.setPushMethod(effectiveMethod.name());
        versionMapper.updateById(version);

        if (TaskStatus.CONFIRMED.name().equals(task.getStatus())) {
            stateMachineService.transitTo(task.getId(), TaskStatus.PUSHING, null);
        } else {
            throw new BusinessException("任务状态异常（当前: " + task.getStatus()
                    + "），仅 CONFIRMED 状态可入队推送");
        }

        enqueueToRedis(pushTask.getId(), versionId, effectiveMethod);

        log.info("推送任务已入队: pushTaskId={}, versionId={}, method={}", pushTask.getId(), versionId, effectiveMethod);
    }

    /**
     * 强校验：草稿 DB 状态为 CONFIRMED/PUSHED；待确认项从 docs/modules（或残留 drafts）读取。
     */
    private void validateDraftsReady(Long workspaceId, Long taskId) {
        List<KnowledgeDraft> drafts = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .eq(KnowledgeDraft::getWorkspaceId, workspaceId));

        if (drafts.isEmpty()) {
            throw new BusinessException("没有草稿需要推送");
        }

        for (KnowledgeDraft draft : drafts) {
            String status = draft.getStatus();
            if (!DraftStatus.CONFIRMED.name().equals(status)
                    && !DraftStatus.PUSHED.name().equals(status)) {
                throw new BusinessException("模块 " + draft.getModuleName()
                        + " 的状态是 " + status + "，必须确认为已确认(CONFIRMED)或已推送(PUSHED)状态才能推送！");
            }
        }

        String pathError = null;
        KnowledgeDraft invalidDraft = null;
        for (KnowledgeDraft draft : drafts) {
            pathError = DraftPathValidator.validatePushFilePath(draft.getFilePath());
            if (pathError != null) {
                invalidDraft = draft;
                break;
            }
        }
        if (pathError != null) {
            throw new BusinessException("模块 " + invalidDraft.getModuleName() + " 的" + pathError + "，无法推送！");
        }

        Path modulesDir = taskWorkspacePaths.taskDocsCodeInsight(taskId).resolve("modules");
        for (KnowledgeDraft draft : drafts) {
            Path staged = modulesDir.resolve(
                    com.company.codeinsight.modules.knowledge.service.impl.KnowledgeIndexServiceImpl
                            .flattenKnowledgeDocFileName(draft.getModuleName()));
            File draftFile = DraftFileUtil.resolve(draft.getContentUri(), storageResolver).toFile();
            Path contentPath = Files.exists(staged) ? staged
                    : (draftFile.exists() ? draftFile.toPath() : null);
            if (contentPath == null) {
                continue;
            }
            try {
                String content = Files.readString(contentPath);
                if (content.contains("- [ ]")) {
                    throw new BusinessException("模块 " + draft.getModuleName()
                            + " 中包含待确认项 '- [ ]'，无法推送！请先复核并解决这些待确认项。");
                }
            } catch (IOException e) {
                log.error("读取发布正文校验失败: {}", contentPath, e);
            }
        }
    }

    /**
     * 将任务 JSON 写入 Redis 队列
     */
    private void enqueueToRedis(Long pushTaskId, Long versionId, PushMethod method) {
        if (redisTemplate == null) {
            throw new BusinessException("Redis 未连接，推送队列不可用。请确认 Redis 服务已启动");
        }

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("pushTaskId", pushTaskId);
            payload.put("versionId", versionId);
            payload.put("pushMethod", method.name());

            String json = objectMapper.writeValueAsString(payload);
            redisTemplate.opsForList().leftPush(PUSH_QUEUE_KEY, json);
            log.debug("任务已入队 Redis: {}", json);
        } catch (JsonProcessingException e) {
            log.error("序列化推送任务失败", e);
            throw new BusinessException("推送任务序列化失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("Redis 入队失败", e);
            throw new BusinessException("Redis 入队失败: " + e.getMessage());
        }
    }

    // ==================== 出队 & 执行 ====================

    @Override
    public void processNextTask() {
        if (redisTemplate == null) {
            return; // Redis 不可用时静默跳过，不阻塞调度器
        }

        try {
            // BRPOP 阻塞弹出，1 秒超时避免长时间阻塞
            String taskJson = redisTemplate.opsForList().rightPop(PUSH_QUEUE_KEY, 1, TimeUnit.SECONDS);
            if (taskJson == null) {
                return; // 队列为空
            }

            ObjectNode payload = (ObjectNode) objectMapper.readTree(taskJson);
            Long pushTaskId = payload.get("pushTaskId").asLong();
            Long versionId = payload.get("versionId").asLong();
            String methodName = payload.get("pushMethod").asText();

            executePushTask(pushTaskId, versionId, PushMethod.valueOf(methodName));

        } catch (Exception e) {
            log.error("处理推送任务异常", e);
        }
    }

    /**
     * 执行单个推送任务：加锁 → 分发策略 → 更新状态（含重试）
     */
    private void executePushTask(Long pushTaskId, Long versionId, PushMethod method) {
        String lockKey = PUSH_LOCK_PREFIX + versionId;

        // 尝试获取分布式锁
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (locked == null || !locked) {
            log.debug("版本 {} 的推送任务正在被其他调度器执行，跳过", versionId);
            return; // 获取锁失败，可能其他实例正在处理
        }

        try {
            PushTask pushTask = pushTaskMapper.selectById(pushTaskId);
            if (pushTask == null) {
                log.error("推送任务 {} 不存在", pushTaskId);
                return;
            }

            // 幂等检查：如果已经是终态（SUCCESS / FAILED），跳过
            if (PushTaskStatus.SUCCESS.name().equals(pushTask.getStatus())
                    || PushTaskStatus.FAILED.name().equals(pushTask.getStatus())) {
                log.debug("推送任务 {} 已是终态 {}，跳过", pushTaskId, pushTask.getStatus());
                return;
            }

            KnowledgeVersion version = versionMapper.selectById(versionId);
            if (version == null) {
                log.error("知识版本 {} 不存在", versionId);
                markTaskFailed(pushTask, "知识版本不存在");
                return;
            }

            // 更新任务状态为 PROCESSING
            pushTask.setStatus(PushTaskStatus.PROCESSING.name());
            pushTask.setStartedAt(LocalDateTime.now());
            pushTaskMapper.updateById(pushTask);

            // 获取策略并执行
            PushStrategy strategy = strategyMap.get(method);
            if (strategy == null) {
                log.error("未找到推送策略: {}", method);
                markTaskFailed(pushTask, "未找到推送策略: " + method);
                updateVersionStatus(version, "FAILED");
                return;
            }

            try {
                String result = strategy.execute(version, pushTask);
                // 重新加载版本（Git 策略可能已更新 status/targetCommit）
                version = versionMapper.selectById(versionId);
                applyRepositoryPublish(version);

                pushTask.setStatus(PushTaskStatus.SUCCESS.name());
                pushTask.setCompletedAt(LocalDateTime.now());
                pushTaskMapper.updateById(pushTask);

                DecompileTask taskAfterPush = taskMapper.selectById(version.getTaskId());
                if (taskAfterPush != null && TaskStatus.PUSHING.name().equals(taskAfterPush.getStatus())) {
                    stateMachineService.transitTo(version.getTaskId(), TaskStatus.PUSHED, null);
                }
                taskDiskCleanupService.cleanupAfterPush(version.getTaskId());
                try {
                    releaseRetentionService.submitAfterPushSuccess(version.getRepositoryId());
                } catch (Exception pruneEx) {
                    log.warn("推送成功后 release 保留清理提交失败 versionId={} repoId={}: {}",
                            versionId, version.getRepositoryId(), pruneEx.getMessage());
                }

                log.info("推送任务执行成功: pushTaskId={}, result={}", pushTaskId, result);
            } catch (Exception e) {
                log.error("推送任务执行失败: pushTaskId={}, error={}", pushTaskId, e.getMessage());
                handlePushFailure(pushTask, version, e);
            }

        } finally {
            // 释放锁
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 处理推送失败：递增重试次数，判断是否需要重新入队
     */
    private void handlePushFailure(PushTask pushTask, KnowledgeVersion version, Exception e) {
        int retryCount = pushTask.getRetryCount() != null ? pushTask.getRetryCount() + 1 : 1;
        pushTask.setRetryCount(retryCount);
        pushTask.setErrorMessage(com.company.codeinsight.common.util.DbStringLimits.truncate(
                e.getMessage(), com.company.codeinsight.common.util.DbStringLimits.ERROR_REASON));

        if (retryCount < pushTask.getMaxRetries()) {
            // 未达最大重试次数，重新入队
            pushTask.setStatus(PushTaskStatus.PENDING.name());
            pushTaskMapper.updateById(pushTask);

            try {
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("pushTaskId", pushTask.getId());
                payload.put("versionId", version.getId());
                payload.put("pushMethod", pushTask.getPushMethod());
                String json = objectMapper.writeValueAsString(payload);
                redisTemplate.opsForList().leftPush(PUSH_QUEUE_KEY, json);
                log.info("推送任务重新入队: pushTaskId={}, retry={}/{}",
                        pushTask.getId(), retryCount, pushTask.getMaxRetries());
            } catch (Exception enqueueErr) {
                log.error("重新入队失败: pushTaskId={}", pushTask.getId(), enqueueErr);
                markTaskFailed(pushTask, "重试入队失败: " + enqueueErr.getMessage());
                updateVersionStatus(version, "FAILED");
            }
        } else {
            // 达到最大重试次数，标记失败
            pushTask.setStatus(PushTaskStatus.FAILED.name());
            pushTask.setCompletedAt(LocalDateTime.now());
            markTaskFailed(pushTask, "已达最大重试次数(" + pushTask.getMaxRetries() + "): " + e.getMessage());
            updateVersionStatus(version, "FAILED");
        }
    }

    private void markTaskFailed(PushTask pushTask, String errorMessage) {
        pushTask.setStatus(PushTaskStatus.FAILED.name());
        pushTask.setErrorMessage(com.company.codeinsight.common.util.DbStringLimits.truncate(
                errorMessage, com.company.codeinsight.common.util.DbStringLimits.ERROR_REASON));
        pushTask.setCompletedAt(LocalDateTime.now());
        pushTaskMapper.updateById(pushTask);
    }

    private void applyRepositoryPublish(KnowledgeVersion version) {
        String operator = OperatorContext.get();
        RepositoryPublishSnapshot snapshot = repositoryPublishService.applyFromTask(
                version.getTaskId(), version.getId(), operator);
        DecompileTask task = taskMapper.selectById(version.getTaskId());
        if (task != null) {
            java.nio.file.Path releaseDir = storageResolver.releaseDir(
                    task.getSystemId(), task.getRepositoryId(), version.getVersionNum());
            repositoryPublishService.exportArtifactsToRelease(releaseDir, snapshot);
        }
        if (!"PUSHED".equals(version.getStatus())) {
            updateVersionStatus(version, "PUSHED");
        }
    }

    private void updateVersionStatus(KnowledgeVersion version, String status) {
        version.setStatus(status);
        version.setPushedAt(LocalDateTime.now());
        versionMapper.updateById(version);
    }

    // ==================== 查询 ====================

    @Override
    public List<PushTask> listTasksByVersion(Long versionId) {
        return pushTaskMapper.selectList(
                new LambdaQueryWrapper<PushTask>()
                        .eq(PushTask::getVersionId, versionId)
                        .orderByDesc(PushTask::getCreatedDate));
    }
}
