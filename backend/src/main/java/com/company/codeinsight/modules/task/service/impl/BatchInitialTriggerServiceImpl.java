package com.company.codeinsight.modules.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.auth.ClientIpContext;
import com.company.codeinsight.common.auth.OperatorContext;
import com.company.codeinsight.common.config.AsyncExecutorConfig;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.service.SystemApplicationService;
import com.company.codeinsight.modules.task.dto.BatchInitialItemResult;
import com.company.codeinsight.modules.task.dto.BatchInitialTriggerResult;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.BatchInitialTriggerService;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 全平台一键全量（异步）：HTTP 立刻返回 jobId，后台 create+start→PENDING。
 * <p>作业状态落 Redis，集群下任意节点可轮询。</p>
 */
@Slf4j
@Service
public class BatchInitialTriggerServiceImpl implements BatchInitialTriggerService {

    private static final String JOB_KEY_PREFIX = "ci:batch-initial:job:";
    private static final String LOCK_KEY = "ci:batch-initial:lock";
    private static final Duration JOB_TTL = Duration.ofHours(2);
    private static final Duration LOCK_TTL = Duration.ofHours(2);

    private static final Set<String> TERMINAL_STATUSES = Set.of(
            TaskStatus.FAILED.name(),
            TaskStatus.CANCELLED.name(),
            TaskStatus.ARCHIVED.name(),
            TaskStatus.PUSHED.name()
    );

    private final CodeRepositoryService codeRepositoryService;
    private final SystemApplicationService systemApplicationService;
    private final DecompileTaskService decompileTaskService;
    private final DecompileTaskMapper decompileTaskMapper;
    private final OperationLogService operationLogService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Executor batchInitialExecutor;

    public BatchInitialTriggerServiceImpl(
            CodeRepositoryService codeRepositoryService,
            SystemApplicationService systemApplicationService,
            DecompileTaskService decompileTaskService,
            DecompileTaskMapper decompileTaskMapper,
            OperationLogService operationLogService,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            @Qualifier(AsyncExecutorConfig.BATCH_INITIAL_EXECUTOR) Executor batchInitialExecutor) {
        this.codeRepositoryService = codeRepositoryService;
        this.systemApplicationService = systemApplicationService;
        this.decompileTaskService = decompileTaskService;
        this.decompileTaskMapper = decompileTaskMapper;
        this.operationLogService = operationLogService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.batchInitialExecutor = batchInitialExecutor;
    }

    @Override
    public BatchInitialTriggerResult submitAsync(String modelName) {
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            throw new BusinessException("已有一键全量作业进行中，请稍后再试或查询进行中作业进度");
        }

        String jobId = UUID.randomUUID().toString().replace("-", "");
        try {
            List<CodeRepository> repos = codeRepositoryService.list(
                    new LambdaQueryWrapper<CodeRepository>()
                            .orderByAsc(CodeRepository::getSystemId)
                            .orderByAsc(CodeRepository::getId));

            String resolvedModel = StringUtils.hasText(modelName) ? modelName.trim() : null;
            BatchInitialTriggerResult accepted = BatchInitialTriggerResult.builder()
                    .jobId(jobId)
                    .status(BatchInitialTriggerResult.STATUS_ACCEPTED)
                    .totalRepos(repos.size())
                    .processedRepos(0)
                    .triggered(0)
                    .skipped(0)
                    .failed(0)
                    .modelName(resolvedModel)
                    .message("已提交后台执行")
                    .items(new ArrayList<>())
                    .build();
            saveJob(accepted);
            // lock 值改为 jobId，便于排查
            stringRedisTemplate.opsForValue().set(LOCK_KEY, jobId, LOCK_TTL);

            OperatorContext.Snapshot snapshot = new OperatorContext.Snapshot(
                    OperatorContext.get(), OperatorContext.getUserId(), OperatorContext.getRole());
            String clientIp = ClientIpContext.isPresent() ? ClientIpContext.get() : null;
            List<CodeRepository> repoSnapshot = List.copyOf(repos);
            batchInitialExecutor.execute(() -> runJob(jobId, repoSnapshot, snapshot, clientIp, resolvedModel));
            return accepted;
        } catch (RuntimeException e) {
            releaseLockQuietly();
            deleteJobQuietly(jobId);
            throw e;
        }
    }

    @Override
    public BatchInitialTriggerResult getJob(String jobId) {
        if (!StringUtils.hasText(jobId)) {
            throw new BusinessException("jobId 不能为空");
        }
        BatchInitialTriggerResult job = loadJob(jobId);
        if (job == null) {
            throw new BusinessException("作业不存在或已过期: " + jobId);
        }
        return job;
    }

    private void runJob(String jobId, List<CodeRepository> repos, OperatorContext.Snapshot snapshot,
                        String clientIp, String modelName) {
        OperatorContext.set(snapshot.username(), snapshot.userId(), snapshot.role());
        if (clientIp != null) {
            ClientIpContext.set(clientIp);
        }
        try {
            BatchInitialTriggerResult progress = BatchInitialTriggerResult.builder()
                    .jobId(jobId)
                    .status(BatchInitialTriggerResult.STATUS_RUNNING)
                    .totalRepos(repos.size())
                    .processedRepos(0)
                    .triggered(0)
                    .skipped(0)
                    .failed(0)
                    .modelName(modelName)
                    .message("后台执行中")
                    .items(new ArrayList<>())
                    .build();
            saveJob(progress);

            Map<Long, String> systemNameById = loadSystemNames(repos);
            for (CodeRepository repo : repos) {
                BatchInitialItemResult item = triggerOne(repo, systemNameById.get(repo.getSystemId()), modelName);
                progress.getItems().add(item);
                progress.setProcessedRepos(progress.getProcessedRepos() + 1);
                switch (item.getStatus()) {
                    case BatchInitialItemResult.STATUS_TRIGGERED ->
                            progress.setTriggered(progress.getTriggered() + 1);
                    case BatchInitialItemResult.STATUS_SKIPPED ->
                            progress.setSkipped(progress.getSkipped() + 1);
                    default -> progress.setFailed(progress.getFailed() + 1);
                }
                saveJob(progress);
            }

            progress.setStatus(BatchInitialTriggerResult.STATUS_COMPLETED);
            progress.setMessage(String.format("完成：触发=%d 跳过=%d 失败=%d%s",
                    progress.getTriggered(), progress.getSkipped(), progress.getFailed(),
                    StringUtils.hasText(modelName) ? " model=" + modelName : ""));
            saveJob(progress);

            operationLogService.logOperation(
                    null,
                    null,
                    "BATCH_TRIGGER_INITIAL",
                    String.format("一键全量(异步)：job=%s model=%s 仓库=%d 触发=%d 跳过=%d 失败=%d",
                            jobId, modelName != null ? modelName : "(default)",
                            progress.getTotalRepos(), progress.getTriggered(),
                            progress.getSkipped(), progress.getFailed()),
                    null,
                    progress.getFailed() == 0);
        } catch (Exception e) {
            log.error("batch initial job {} failed: {}", jobId, e.toString(), e);
            BatchInitialTriggerResult failed = loadJob(jobId);
            if (failed == null) {
                failed = BatchInitialTriggerResult.builder()
                        .jobId(jobId)
                        .totalRepos(repos.size())
                        .modelName(modelName)
                        .items(new ArrayList<>())
                        .build();
            }
            failed.setStatus(BatchInitialTriggerResult.STATUS_FAILED);
            failed.setMessage("后台执行失败: " + e.getMessage());
            saveJob(failed);
        } finally {
            releaseLockQuietly();
            ClientIpContext.clear();
            OperatorContext.clear();
        }
    }

    private BatchInitialItemResult triggerOne(CodeRepository repo, String systemName, String modelName) {
        Long systemId = repo.getSystemId();
        Long repositoryId = repo.getId();
        String gitUrl = repo.getGitUrl();

        try {
            if (hasUnfinishedTask(systemId, repositoryId)) {
                return skip(systemId, systemName, repositoryId, gitUrl, "同仓已有未完成任务，跳过");
            }

            DecompileTask task = decompileTaskService.createInitialTask(
                    systemId, repositoryId,
                    null, null, modelName, null,
                    Boolean.FALSE, Boolean.FALSE);
            task.setRequireKnowledgeReview(Boolean.FALSE);
            decompileTaskService.updateById(task);
            decompileTaskService.startTask(task.getId());

            return BatchInitialItemResult.builder()
                    .systemId(systemId)
                    .systemName(systemName)
                    .repositoryId(repositoryId)
                    .gitUrl(gitUrl)
                    .status(BatchInitialItemResult.STATUS_TRIGGERED)
                    .taskId(task.getId())
                    .message("已入队 PENDING")
                    .build();
        } catch (BusinessException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "业务校验失败";
            if (isSkipMessage(msg)) {
                return skip(systemId, systemName, repositoryId, gitUrl, msg);
            }
            return fail(systemId, systemName, repositoryId, gitUrl, msg);
        } catch (Exception e) {
            log.warn("batch initial trigger failed for repo {}: {}", repositoryId, e.toString());
            return fail(systemId, systemName, repositoryId, gitUrl, "触发失败: " + e.getMessage());
        }
    }

    private boolean hasUnfinishedTask(Long systemId, Long repositoryId) {
        Long count = decompileTaskMapper.selectCount(
                new LambdaQueryWrapper<DecompileTask>()
                        .eq(DecompileTask::getSystemId, systemId)
                        .eq(DecompileTask::getRepositoryId, repositoryId)
                        .notIn(DecompileTask::getStatus, TERMINAL_STATUSES));
        return count != null && count > 0;
    }

    private static boolean isSkipMessage(String msg) {
        return msg.contains("提示词")
                || msg.contains("待复核")
                || msg.contains("未绑定");
    }

    private Map<Long, String> loadSystemNames(List<CodeRepository> repos) {
        Map<Long, String> map = new HashMap<>();
        for (CodeRepository repo : repos) {
            Long sid = repo.getSystemId();
            if (sid == null || map.containsKey(sid)) {
                continue;
            }
            SystemApplication sys = systemApplicationService.getById(sid);
            map.put(sid, sys != null ? sys.getName() : null);
        }
        return map;
    }

    private void saveJob(BatchInitialTriggerResult job) {
        try {
            stringRedisTemplate.opsForValue().set(
                    JOB_KEY_PREFIX + job.getJobId(),
                    objectMapper.writeValueAsString(job),
                    JOB_TTL);
        } catch (Exception e) {
            throw new BusinessException("写入作业状态失败: " + e.getMessage());
        }
    }

    private BatchInitialTriggerResult loadJob(String jobId) {
        try {
            String json = stringRedisTemplate.opsForValue().get(JOB_KEY_PREFIX + jobId);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, BatchInitialTriggerResult.class);
        } catch (Exception e) {
            throw new BusinessException("读取作业状态失败: " + e.getMessage());
        }
    }

    private void releaseLockQuietly() {
        try {
            stringRedisTemplate.delete(LOCK_KEY);
        } catch (Exception e) {
            log.warn("release batch-initial lock failed: {}", e.toString());
        }
    }

    private void deleteJobQuietly(String jobId) {
        try {
            stringRedisTemplate.delete(JOB_KEY_PREFIX + jobId);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static BatchInitialItemResult skip(Long systemId, String systemName,
                                               Long repositoryId, String gitUrl, String message) {
        return BatchInitialItemResult.builder()
                .systemId(systemId)
                .systemName(systemName)
                .repositoryId(repositoryId)
                .gitUrl(gitUrl)
                .status(BatchInitialItemResult.STATUS_SKIPPED)
                .message(message)
                .build();
    }

    private static BatchInitialItemResult fail(Long systemId, String systemName,
                                               Long repositoryId, String gitUrl, String message) {
        return BatchInitialItemResult.builder()
                .systemId(systemId)
                .systemName(systemName)
                .repositoryId(repositoryId)
                .gitUrl(gitUrl)
                .status(BatchInitialItemResult.STATUS_FAILED)
                .message(message)
                .build();
    }
}
