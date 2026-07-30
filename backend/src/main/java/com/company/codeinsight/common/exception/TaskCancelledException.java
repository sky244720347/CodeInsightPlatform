package com.company.codeinsight.common.exception;

/**
 * 任务被用户终止（或协作取消）时抛出。
 * <p>流水线 catch 必须识别此类异常，禁止再 {@code transitTo(FAILED)} 覆盖 {@code CANCELLED}。</p>
 */
public class TaskCancelledException extends BusinessException {

    private final Long taskId;

    public TaskCancelledException(Long taskId) {
        super("任务已终止" + (taskId != null ? " taskId=" + taskId : ""));
        this.taskId = taskId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public static boolean isCancellation(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof TaskCancelledException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && msg.contains("任务已终止")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
