package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.storage.EnvStorageResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务执行日志写入器
 * 将 pipeline 各阶段的关键事件写入 {dataRoot}/task_{taskId}/pipeline.log，
 * 供前端实时查看执行过程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskExecutionLogger {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 新一轮流水线启动标记，用于从 log 文件中截取「最近一次运行」片段 */
    public static final String PIPELINE_START_MARKER = "══════ 流水线启动";

    private final EnvStorageResolver storageResolver;

    /** 按任务串行化 pipeline.log 追加，避免文档并行写交叉 */
    private final ConcurrentHashMap<Long, Object> taskLogLocks = new ConcurrentHashMap<>();

    /**
     * 清空 pipeline.log（重试 / 新一轮 runPipeline 开始前调用）。
     */
    public void truncate(Long taskId) {
        if (taskId == null) {
            return;
        }
        try {
            File dir = storageResolver.taskDataDir(taskId).toFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File logFile = new File(dir, "pipeline.log");
            java.nio.file.Files.writeString(
                    logFile.toPath(),
                    "",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("清空 pipeline.log 失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 读取 pipeline.log 中<strong>最近一次</strong>流水线运行的内容（按启动标记截取）。
     */
    public String readLastRunContent(Long taskId) {
        if (taskId == null) {
            return "";
        }
        File logFile = new File(storageResolver.taskDataDir(taskId).toFile(), "pipeline.log");
        if (!logFile.isFile()) {
            return "";
        }
        try {
            return extractLastRunSegment(java.nio.file.Files.readString(logFile.toPath()));
        } catch (IOException e) {
            log.warn("读取 pipeline.log 失败 taskId={}: {}", taskId, e.getMessage());
            return "";
        }
    }

    /**
     * 从完整 log 文本中截取最后一次 {@link #PIPELINE_START_MARKER} 之后的片段。
     */
    public static String extractLastRunSegment(String fullContent) {
        if (fullContent == null || fullContent.isBlank()) {
            return "";
        }
        int idx = fullContent.lastIndexOf(PIPELINE_START_MARKER);
        if (idx < 0) {
            return fullContent;
        }
        return fullContent.substring(idx);
    }

    /**
     * 追加一条带时间戳的日志（按 taskId 串行化，避免文档并行写乱行）。
     */
    public void log(Long taskId, String message) {
        if (taskId == null) return;
        String timestamp = LocalDateTime.now().format(TS_FMT);
        String line = String.format("[%s] %s%n", timestamp, message);
        Object lock = taskLogLocks.computeIfAbsent(taskId, id -> new Object());
        synchronized (lock) {
            try {
                File dir = storageResolver.taskDataDir(taskId).toFile();
                if (!dir.exists()) dir.mkdirs();
                try (PrintWriter pw = new PrintWriter(new FileWriter(new File(dir, "pipeline.log"), true))) {
                    pw.append(line);
                    pw.flush();
                }
            } catch (IOException e) {
                log.warn("写入执行日志失败 taskId={}: {}", taskId, e.getMessage());
            }
        }
    }

    /** 阶段开始 */
    public void logStage(Long taskId, String stage) {
        log(taskId, ">>> 进入阶段: " + stage);
    }

    /** 阶段产出 */
    public void logResult(Long taskId, String key, Object value) {
        log(taskId, "    " + key + ": " + value);
    }

    /** 异常 */
    public void logError(Long taskId, String message, Throwable e) {
        logException(taskId, message, e);
    }

    /**
     * 将异常写入 pipeline.log，供任务详情「查看完整日志」展示。
     */
    public void logException(Long taskId, String context, Throwable e) {
        if (taskId == null) {
            return;
        }
        if (e == null) {
            log(taskId, "!!! " + context);
            return;
        }
        log(taskId, "!!! " + context + ": " + e.getClass().getSimpleName() + " — " + e.getMessage());
        int lines = 0;
        final int maxLines = 15;
        for (StackTraceElement ste : e.getStackTrace()) {
            if (!ste.getClassName().contains("codeinsight")) {
                continue;
            }
            log(taskId, "    at " + ste.getClassName() + "." + ste.getMethodName()
                    + "(" + ste.getFileName() + ":" + ste.getLineNumber() + ")");
            if (++lines >= maxLines) {
                break;
            }
        }
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            log(taskId, "    caused by: " + cause.getClass().getSimpleName() + " — " + cause.getMessage());
        }
    }
}
