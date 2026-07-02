package com.company.codeinsight.modules.task.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import com.company.codeinsight.modules.task.entity.DecompileTask;

import java.util.List;
import java.util.Map;

/**
 * 反编译及扫描分析任务管理服务接口
 */
public interface DecompileTaskService extends IService<DecompileTask> {

    Page<DecompileTask> listTasksPage(int current, int size, Long systemId, String status, String type,
                                      List<String> statuses, String triggerSource,
                                      String keyword, String modelName,
                                      String createdAtStart, String createdAtEnd);

    Map<String, Long> countByStatusGroup(Long systemId);

    DecompileTask createInitialTask(Long systemId, Long repositoryId,
                                    Long modularizePromptId, Long documentPromptId,
                                    String modelName,
                                    EntryPointConfig entryScanConfig, Boolean requireHierarchyReview);

    DecompileTask createInitialTask(Long systemId, Long repositoryId,
                                    Long modularizePromptId, Long documentPromptId,
                                    String modelName,
                                    EntryPointConfig entryScanConfig,
                                    Boolean requireHierarchyReview, Boolean requireEntrypointReview);

    DecompileTask createIncrementalTask(Long systemId, Long repositoryId,
                                        Long modularizePromptId, Long documentPromptId,
                                        String modelName,
                                        EntryPointConfig entryScanConfig, Boolean requireHierarchyReview);

    DecompileTask createIncrementalTask(Long systemId, Long repositoryId,
                                        Long modularizePromptId, Long documentPromptId,
                                        String modelName,
                                        EntryPointConfig entryScanConfig,
                                        Boolean requireHierarchyReview, Boolean requireEntrypointReview);

    /** 带触发来源标签 */
    DecompileTask createInitialTask(Long systemId, Long repositoryId,
                                    Long modularizePromptId, Long documentPromptId,
                                    String modelName,
                                    EntryPointConfig entryScanConfig, Boolean requireHierarchyReview,
                                    String triggerSource);

    /** 带触发来源标签 + entrypoint review */
    DecompileTask createInitialTask(Long systemId, Long repositoryId,
                                    Long modularizePromptId, Long documentPromptId,
                                    String modelName,
                                    EntryPointConfig entryScanConfig,
                                    Boolean requireHierarchyReview, Boolean requireEntrypointReview,
                                    String triggerSource);

    /** 带触发来源标签 */
    DecompileTask createIncrementalTask(Long systemId, Long repositoryId,
                                        Long modularizePromptId, Long documentPromptId,
                                        String modelName,
                                        EntryPointConfig entryScanConfig, Boolean requireHierarchyReview,
                                        String triggerSource);

    /** 带触发来源标签 + entrypoint review */
    DecompileTask createIncrementalTask(Long systemId, Long repositoryId,
                                        Long modularizePromptId, Long documentPromptId,
                                        String modelName,
                                        EntryPointConfig entryScanConfig,
                                        Boolean requireHierarchyReview, Boolean requireEntrypointReview,
                                        String triggerSource);

    void startTask(Long id);
    void terminateTask(Long id);
    void retryTask(Long id);
    void resumeAfterHierarchyReview(Long id);
    void resumeAfterEntrypointReview(Long id);

    void resumeAfterEntrypointReview(Long id, java.util.List<com.company.codeinsight.modules.entrypoint.model.ExcludeTarget> additionalExcludes);
    void rejectEntrypointReview(Long id, String reason);
    void cancelQueuedTask(Long id);
    void adjustPriority(Long id, Integer newPriority);
    Page<DecompileTask> listQueuedTasks(int current, int size, Long systemId);
    Map<String, Object> getQueueSummary();
    void runPipeline(Long taskId);
}
