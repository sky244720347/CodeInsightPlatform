package com.company.codeinsight.modules.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 知识构建任务「新建前置条件」就绪度拦截的集成测试。
 *
 * <p>验证 {@link com.company.codeinsight.modules.task.service.impl.DecompileTaskServiceImpl#validateNoPendingReviewTasks(Long, Long)}
 * 在 createInitialTask / createIncrementalTask 入口处生效：</p>
 * <ul>
 *   <li>当前系统+仓库下若存在「知识复核」任务（PENDING_REVIEW / REVIEWING），新任务创建应抛 BusinessException</li>
 *   <li>流水线中间断点（ENTRYPOINT_REVIEW / MODULE_HIERARCHY_REVIEW）属于流水线可跳过的中间环节，不阻塞新建任务</li>
 *   <li>当前系统+仓库下无待知识复核任务时（无论已完成、已推送、失败、取消、归档），新任务可正常创建</li>
 *   <li>其他系统+仓库的待知识复核任务不影响当前任务创建</li>
 * </ul>
 *
 * <p>判定源为 {@code ci_task.status} 而非 {@code ci_knowledge_draft.status}：
 * 草稿可在任务 CONFIRMED / PUSHED 后被复核人继续编辑并回流到 EDITING，
 * 但这并不意味着「存在待知识复核任务」——只有任务级状态机才是「能否开新流水线」的权威信号。</p>
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
@Transactional
public class DecompileTaskReadinessTest {

    @Autowired
    private DecompileTaskService decompileTaskService;

    @Autowired
    private DecompileTaskMapper taskMapper;

    private static final long SYSTEM_ID = 9999L;
    private static final long REPOSITORY_ID = 9999L;
    private static final long OTHER_SYSTEM_ID = 8888L;
    private static final long OTHER_REPOSITORY_ID = 8888L;

    @BeforeEach
    public void setUp() {
        // 每个用例前清空 pending review 任务，保证起始状态干净（@Transactional 之外的历史脏数据）
        clearPendingReviewTasks();
    }

    private void clearPendingReviewTasks() {
        taskMapper.delete(
                new LambdaQueryWrapper<DecompileTask>()
                        .in(DecompileTask::getStatus, java.util.List.of(
                                TaskStatus.ENTRYPOINT_REVIEW.name(),
                                TaskStatus.MODULE_HIERARCHY_REVIEW.name(),
                                TaskStatus.PENDING_REVIEW.name(),
                                TaskStatus.REVIEWING.name()
                        ))
        );
    }

    /**
     * 构造并落库一条指定状态的任务（仅设置就绪度校验所需的最小字段集）
     */
    private DecompileTask insertTask(long systemId, long repositoryId, String status) {
        DecompileTask task = new DecompileTask();
        task.setSystemId(systemId);
        task.setRepositoryId(repositoryId);
        task.setStatus(status);
        task.setType("INITIAL");
        task.setProgress(0);
        task.setDurationMs(0L);
        task.setPriority(50);
        task.setRequireHierarchyReview(Boolean.TRUE);
        task.setRequireEntrypointReview(Boolean.TRUE);
        task.setTriggerSource("MANUAL");
        taskMapper.insert(task);
        return task;
    }

    /**
     * 反射触发私有 validateNoPendingReviewTasks 并归一化 InvocationTargetException
     */
    private void invokeGate(long systemId, long repositoryId) throws Exception {
        Method m = com.company.codeinsight.modules.task.service.impl.DecompileTaskServiceImpl.class
                .getDeclaredMethod("validateNoPendingReviewTasks", Long.class, Long.class);
        m.setAccessible(true);
        try {
            m.invoke(decompileTaskService, systemId, repositoryId);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    /**
     * 存在 PENDING_REVIEW 任务时，当前系统+仓库的校验应被拦截。
     */
    @Test
    public void testReadinessGateBlocksWhenTaskPendingReview() throws Exception {
        insertTask(SYSTEM_ID, REPOSITORY_ID, TaskStatus.PENDING_REVIEW.name());

        BusinessException ex = Assertions.assertThrows(BusinessException.class, () -> {
            try {
                invokeGate(SYSTEM_ID, REPOSITORY_ID);
            } catch (Exception e) {
                if (e instanceof BusinessException) throw (BusinessException) e;
                throw new RuntimeException(e);
            }
        });
        Assertions.assertTrue(ex.getMessage().contains("任务待复核"),
                "异常文案应改为「任务待复核」以反映新语义，实际: " + ex.getMessage());
    }

    /**
     * 关键回归：流水线中间断点（ENTRYPOINT_REVIEW / MODULE_HIERARCHY_REVIEW）
     * 不应阻塞新建任务——它们是流水线可跳过的中间环节，新建任务会进入独立 workspace、不冲突。
     * 仅「知识复核」白名单（PENDING_REVIEW / REVIEWING）才阻塞。
     */
    @Test
    public void testReadinessGateDoesNotBlockForPipelineBreakpoints() throws Exception {
        for (String nonBlocking : new String[]{
                TaskStatus.ENTRYPOINT_REVIEW.name(),
                TaskStatus.MODULE_HIERARCHY_REVIEW.name()
        }) {
            clearPendingReviewTasks();
            insertTask(SYSTEM_ID, REPOSITORY_ID, nonBlocking);

            Assertions.assertDoesNotThrow(() -> invokeGate(SYSTEM_ID, REPOSITORY_ID),
                    "流水线中间断点 " + nonBlocking + " 不应阻塞新建任务");
        }
    }

    /**
     * 当前系统+仓库无 pending review 任务时，校验方法应直接通过不抛错。
     * 覆盖所有非「待复核」状态：进行中、已完成、终态。
     */
    @Test
    public void testReadinessGatePassesWhenNoTaskPendingReview() throws Exception {
        // 插一条已 CONFIRMED 任务（即便草稿被复核人改回 EDITING 也不应被误拦，由 service 层独立保证）
        insertTask(SYSTEM_ID, REPOSITORY_ID, TaskStatus.CONFIRMED.name());
        // 插一条已 PUSHED 任务
        insertTask(SYSTEM_ID, REPOSITORY_ID, TaskStatus.PUSHED.name());
        // 插一条失败任务
        insertTask(SYSTEM_ID, REPOSITORY_ID, TaskStatus.FAILED.name());
        // 插一条流水线进行中任务（PARSING_CODE）
        insertTask(SYSTEM_ID, REPOSITORY_ID, TaskStatus.PARSING_CODE.name());

        Assertions.assertDoesNotThrow(() -> invokeGate(SYSTEM_ID, REPOSITORY_ID));
    }

    /**
     * 其他系统+仓库的待复核任务不影响当前系统+仓库的任务创建。
     * 验证作用域收窄正确。
     */
    @Test
    public void testReadinessGateIgnoresOtherSystemsTasks() throws Exception {
        // 在其他系统(8888/8888)下创建一条 PENDING_REVIEW 任务
        insertTask(OTHER_SYSTEM_ID, OTHER_REPOSITORY_ID, TaskStatus.PENDING_REVIEW.name());

        // 当前系统(9999/9999)下无 pending review，校验应直接通过
        Assertions.assertDoesNotThrow(() -> invokeGate(SYSTEM_ID, REPOSITORY_ID));
    }
}