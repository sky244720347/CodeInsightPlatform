package com.company.codeinsight.modules.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.modules.ai.entity.AiCallRecord;
import com.company.codeinsight.modules.ai.mapper.AiCallRecordMapper;
import com.company.codeinsight.modules.hierarchy.entity.ModuleHierarchyNode;
import com.company.codeinsight.modules.hierarchy.mapper.ModuleHierarchyNodeMapper;
import com.company.codeinsight.modules.scanner.entity.CodeFileSnapshot;
import com.company.codeinsight.modules.scanner.mapper.CodeFileSnapshotMapper;
import com.company.codeinsight.modules.task.dto.PipelineStageStatDto;
import com.company.codeinsight.modules.task.dto.TaskLogSummaryDto;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.TaskExecutionLogger;
import com.company.codeinsight.modules.task.service.TaskLogSummaryService;
import com.company.codeinsight.modules.task.support.TaskExecutionDuration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务执行日志摘要服务实现。
 * 单次读快照聚合 ci_task / ci_ai_call_record / ci_file_snapshot / ci_module_hierarchy 与 pipeline.log 文本。
 */
@Slf4j
@Service
public class TaskLogSummaryServiceImpl implements TaskLogSummaryService {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Pattern STAGE_BEGIN = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\] >>> (PULLING_CODE|PARSING_CODE|ENTRYPOINT_DISCOVERY|ENTRYPOINT_REVIEW|AI_ANALYZING|MODULE_HIERARCHY|MODULE_HIERARCHY_REVIEW|GENERATING_DOC)\\b.*$"
    );

    private static final Pattern STAGE_DURATION = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\]\\s+耗时\\s+(\\d+)ms.*$"
    );

    /** 文档生成 AI 落库的 call_stage：默认 function 粒度写 FUNCTION_DOC，module 粒度写 MODULE_DOC */
    private static final List<String> DOC_AI_STAGES = List.of("FUNCTION_DOC", "MODULE_DOC");

    private static final Pattern STAGE_END_OK = Pattern.compile(
            "^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\] <<< ("
                    + "流水线完成 → PENDING_REVIEW"
                    + "|跳过知识复核"
                    + "|流水线文档阶段完成"
                    + "|纠错流水线文档阶段完成"
                    + "|暂停 — 等待人工复核模块层级"
                    + "|暂停 — 等待人工复核知识入口"
                    + ").*$"
    );
    private static final Pattern STAGE_END_ERROR = Pattern.compile(
            "^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\] !!! 流水线异常:\\s*(.+)$"
    );

    private static final Pattern MODULE_PROGRESS = Pattern.compile(
            "^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]\\s+\\[module\\s+(\\d+)/(\\d+)\\].*$"
    );
    private static final Pattern FN_PROGRESS = Pattern.compile(
            "^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]\\s+\\[fn\\s+(\\d+)/(\\d+)\\].*$"
    );

    private static final Map<String, String> STAGE_LABEL = new LinkedHashMap<>();
    static {
        STAGE_LABEL.put("PULLING_CODE", "拉取代码");
        STAGE_LABEL.put("PARSING_CODE", "静态解析");
        STAGE_LABEL.put("ENTRYPOINT_DISCOVERY", "入口识别");
        STAGE_LABEL.put("ENTRYPOINT_REVIEW", "入口复核");
        STAGE_LABEL.put("AI_ANALYZING", "AI 分析");
        STAGE_LABEL.put("MODULE_HIERARCHY", "模块层级提炼");
        STAGE_LABEL.put("MODULE_HIERARCHY_REVIEW", "模块层级复核");
        STAGE_LABEL.put("GENERATING_DOC", "生成文档");
    }

    @Autowired
    private DecompileTaskMapper decompileTaskMapper;

    @Autowired
    private AiCallRecordMapper aiCallRecordMapper;

    @Autowired
    private CodeFileSnapshotMapper codeFileSnapshotMapper;

    @Autowired
    private ModuleHierarchyNodeMapper moduleHierarchyNodeMapper;

    @Autowired
    private TaskExecutionLogger taskExecutionLogger;

    @Value("${code-insight.ai.mock:false}")
    private boolean aiMock;

    @Override
    public TaskLogSummaryDto summarize(Long taskId) {
        TaskLogSummaryDto dto = new TaskLogSummaryDto();
        dto.setTaskId(taskId);

        DecompileTask task = decompileTaskMapper.selectById(taskId);
        if (task == null) {
            dto.setStatus("UNKNOWN");
            dto.setProgress(0);
            dto.setDurationMs(0L);
            dto.setAiMock(aiMock);
            dto.setPipeline(emptyPipeline());
            dto.setCounters(emptyCounters());
            dto.setAiCalls(emptyAiCalls());
            dto.setCurrent(emptyCurrent());
            return dto;
        }
        dto.setStatus(task.getStatus());
        dto.setProgress(task.getProgress() == null ? 0 : task.getProgress());
        dto.setDurationMs(TaskExecutionDuration.resolveLiveDurationMs(task, LocalDateTime.now()));
        dto.setStartedAt(task.getStartedAt());
        dto.setEndedAt(task.getEndedAt());
        dto.setModelName(task.getModelName());
        dto.setAiMock(aiMock);

        TaskLogSummaryDto.Counters counters = new TaskLogSummaryDto.Counters();
        Long totalFiles = codeFileSnapshotMapper.selectCount(
                new LambdaQueryWrapper<CodeFileSnapshot>().eq(CodeFileSnapshot::getTaskId, taskId)
        );
        counters.setTotalFiles(totalFiles == null ? 0 : totalFiles.intValue());
        dto.setCounters(counters);

        TaskLogSummaryDto.AiCalls aiCalls = new TaskLogSummaryDto.AiCalls();
        Long aiTotal = aiCallRecordMapper.selectCount(
                new LambdaQueryWrapper<AiCallRecord>().eq(AiCallRecord::getTaskId, taskId)
        );
        Long aiOk = aiCallRecordMapper.selectCount(
                new LambdaQueryWrapper<AiCallRecord>()
                        .eq(AiCallRecord::getTaskId, taskId)
                        .eq(AiCallRecord::getIsSuccess, 1)
        );
        aiCalls.setTotal(aiTotal == null ? 0 : aiTotal.intValue());
        aiCalls.setSuccess(aiOk == null ? 0 : aiOk.intValue());
        aiCalls.setFailed(aiTotal == null ? 0 : (aiTotal.intValue() - (aiOk == null ? 0 : aiOk.intValue())));
        dto.setAiCalls(aiCalls);
        dto.setHierarchyAiCalls(countAiCallsByStages(taskId, List.of("MODULE_HIERARCHY")));
        // 默认 granularity=function 时落库为 FUNCTION_DOC，不能只查 MODULE_DOC
        dto.setDocAiCalls(countAiCallsByStages(taskId, DOC_AI_STAGES));

        TaskLogSummaryDto.Current current = new TaskLogSummaryDto.Current();
        current.setTotalFiles(counters.getTotalFiles());
        Long moduleTotal = moduleHierarchyNodeMapper.selectCount(
                new LambdaQueryWrapper<ModuleHierarchyNode>()
                        .eq(ModuleHierarchyNode::getTaskId, taskId)
                        .eq(ModuleHierarchyNode::getLevel, "MODULE")
        );
        current.setModuleTotal(moduleTotal == null ? 0 : moduleTotal.intValue());
        current.setModuleIndex(-1);
        dto.setCurrent(current);

        String logContent = taskExecutionLogger.readLastRunContent(taskId);
        if (logContent != null && !logContent.isBlank()) {
            try {
                PipelineParseResult parsed = parsePipelineLog(logContent, STAGE_LABEL);
                reconcilePipelineWithTaskStatus(parsed.stages, task.getStatus());
                dto.setPipeline(parsed.stages);
                if (parsed.lastError != null && TaskStatus.FAILED.name().equals(task.getStatus())) {
                    dto.setLastError(truncate(parsed.lastError, 200));
                }
                if (parsed.moduleIndex >= 0) {
                    current.setModuleIndex(parsed.moduleIndex);
                }
            } catch (Exception e) {
                log.warn("解析 pipeline.log 失败 taskId={}: {}", taskId, e.getMessage());
                dto.setPipeline(emptyPipeline());
            }
        } else {
            dto.setPipeline(emptyPipeline());
        }

        return dto;
    }

    private TaskLogSummaryDto.AiCalls countAiCallsByStages(Long taskId, List<String> stages) {
        TaskLogSummaryDto.AiCalls c = new TaskLogSummaryDto.AiCalls();
        Long total = aiCallRecordMapper.selectCount(
                new LambdaQueryWrapper<AiCallRecord>()
                        .eq(AiCallRecord::getTaskId, taskId)
                        .in(AiCallRecord::getCallStage, stages)
        );
        Long ok = aiCallRecordMapper.selectCount(
                new LambdaQueryWrapper<AiCallRecord>()
                        .eq(AiCallRecord::getTaskId, taskId)
                        .in(AiCallRecord::getCallStage, stages)
                        .eq(AiCallRecord::getIsSuccess, 1)
        );
        int totalInt = total == null ? 0 : total.intValue();
        int okInt = ok == null ? 0 : ok.intValue();
        c.setTotal(totalInt);
        c.setSuccess(okInt);
        c.setFailed(totalInt - okInt);
        return c;
    }

    /**
     * 任务已离开流水线执行态时，把日志解析残留的 running 收成终态，避免看板仍显示「当前：生成文档」。
     */
    public static void reconcilePipelineWithTaskStatus(List<PipelineStageStatDto> stages, String status) {
        if (stages == null || status == null) {
            return;
        }
        boolean failed = TaskStatus.FAILED.name().equals(status);
        boolean cancelled = TaskStatus.CANCELLED.name().equals(status);
        boolean finished = failed || cancelled
                || TaskStatus.PENDING_REVIEW.name().equals(status)
                || TaskStatus.REVIEWING.name().equals(status)
                || TaskStatus.CONFIRMED.name().equals(status)
                || TaskStatus.PUSHING.name().equals(status)
                || TaskStatus.PUSHED.name().equals(status)
                || TaskStatus.ARCHIVED.name().equals(status)
                || TaskStatus.MODULE_HIERARCHY_REVIEW.name().equals(status)
                || TaskStatus.ENTRYPOINT_REVIEW.name().equals(status);
        if (!finished) {
            return;
        }
        for (PipelineStageStatDto s : stages) {
            if (!"running".equals(s.getStatus())) {
                continue;
            }
            if (failed) {
                s.setStatus("error");
            } else if (cancelled) {
                s.setStatus("skipped");
            } else {
                s.setStatus("done");
            }
        }
    }

    private List<PipelineStageStatDto> emptyPipeline() {
        List<PipelineStageStatDto> list = new ArrayList<>();
        for (Map.Entry<String, String> e : STAGE_LABEL.entrySet()) {
            PipelineStageStatDto s = new PipelineStageStatDto();
            s.setKey(e.getKey());
            s.setLabel(e.getValue());
            s.setStatus("pending");
            s.setDurationMs(0L);
            list.add(s);
        }
        return list;
    }

    private TaskLogSummaryDto.Counters emptyCounters() {
        TaskLogSummaryDto.Counters c = new TaskLogSummaryDto.Counters();
        c.setTotalFiles(0);
        return c;
    }

    private TaskLogSummaryDto.AiCalls emptyAiCalls() {
        TaskLogSummaryDto.AiCalls a = new TaskLogSummaryDto.AiCalls();
        a.setTotal(0);
        a.setSuccess(0);
        a.setFailed(0);
        return a;
    }

    private TaskLogSummaryDto.Current emptyCurrent() {
        TaskLogSummaryDto.Current c = new TaskLogSummaryDto.Current();
        c.setModuleIndex(-1);
        c.setModuleTotal(0);
        c.setTotalFiles(0);
        return c;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static class PipelineParseResult {
        List<PipelineStageStatDto> stages = new ArrayList<>();
        String lastError;
        int moduleIndex = -1;
    }

    private PipelineParseResult parsePipelineLog(String content, Map<String, String> labelMap) {
        PipelineParseResult result = new PipelineParseResult();
        Map<String, PipelineStageStatDto> started = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : labelMap.entrySet()) {
            PipelineStageStatDto s = new PipelineStageStatDto();
            s.setKey(e.getKey());
            s.setLabel(e.getValue());
            s.setStatus("pending");
            s.setDurationMs(0L);
            started.put(e.getKey(), s);
            result.stages.add(s);
        }

        String[] lines = content.split("\\r?\\n");
        PipelineStageStatDto active = null;
        boolean terminated = false;

        for (String raw : lines) {
            Matcher begin = STAGE_BEGIN.matcher(raw);
            if (begin.find()) {
                if (terminated) {
                    continue;
                }
                String key = begin.group(2);
                if (active != null && "running".equals(active.getStatus())) {
                    active.setStatus("done");
                }
                PipelineStageStatDto s = started.get(key);
                if (s != null) {
                    s.setStartedAt(toLocalDateTime(begin.group(1)));
                    if (!"done".equals(s.getStatus()) && !"skipped".equals(s.getStatus())) {
                        s.setStatus("running");
                    }
                    active = s;
                }
                continue;
            }

            if (active != null) {
                Matcher dur = STAGE_DURATION.matcher(raw);
                if (dur.find()) {
                    long ms = Long.parseLong(dur.group(2));
                    active.setDurationMs(ms);
                    active.setEndedAt(toLocalDateTime(dur.group(1)));
                }
            }

            Matcher endOk = STAGE_END_OK.matcher(raw);
            if (endOk.find()) {
                if (active != null && "running".equals(active.getStatus())) {
                    active.setStatus("done");
                }
                // 不 break：GENERATING_DOC 的「耗时 Xms」常写在结束标记之后
                terminated = true;
                continue;
            }

            Matcher endErr = STAGE_END_ERROR.matcher(raw);
            if (endErr.find()) {
                if (active != null && "running".equals(active.getStatus())) {
                    active.setStatus("error");
                }
                result.lastError = endErr.group(1).trim();
                terminated = true;
                continue;
            }

            Matcher mod = MODULE_PROGRESS.matcher(raw);
            if (mod.find()) {
                int idx = Integer.parseInt(mod.group(1));
                if (idx > result.moduleIndex) {
                    result.moduleIndex = idx;
                }
                continue;
            }
            Matcher fn = FN_PROGRESS.matcher(raw);
            if (fn.find()) {
                int idx = Integer.parseInt(fn.group(1));
                if (idx > result.moduleIndex) {
                    result.moduleIndex = idx;
                }
            }
        }

        return result;
    }

    private LocalDateTime toLocalDateTime(String ts) {
        try {
            return LocalDateTime.parse(ts, TS_FMT);
        } catch (Exception e) {
            return null;
        }
    }
}
