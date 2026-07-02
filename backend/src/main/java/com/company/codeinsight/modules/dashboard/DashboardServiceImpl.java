package com.company.codeinsight.modules.dashboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.modules.ai.entity.AiCallRecord;
import com.company.codeinsight.modules.ai.mapper.AiCallRecordMapper;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.mapper.SystemApplicationMapper;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DashboardServiceImpl implements DashboardService {

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired
    private AiCallRecordMapper aiCallRecordMapper;

    @Autowired
    private SystemApplicationMapper systemMapper;

    @Autowired
    private KnowledgeDraftMapper draftMapper;

    @Autowired
    private KnowledgeVersionMapper versionMapper;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String[] STATUS_ORDER = {
            "PENDING", "PULLING_CODE", "PARSING_CODE", "SPLITTING_TASK",
            "ENTRYPOINT_REVIEW", "AI_ANALYZING", "MODULE_HIERARCHY", "MODULE_HIERARCHY_REVIEW",
            "GENERATING_DOC", "PENDING_REVIEW", "REVIEWING", "CONFIRMED", "PUSHING", "PUSHED",
            "FAILED", "CANCELLED", "ARCHIVED", "DRAFT"
    };

    @Override
    public Map<String, Object> getTaskStats(int days) {
        List<DecompileTask> all = taskMapper.selectList(null);

        // 1. 按状态分布
        Map<String, Long> byStatus = all.stream()
                .collect(Collectors.groupingBy(t -> t.getStatus() != null ? t.getStatus() : "UNKNOWN", Collectors.counting()));

        // 2. 按类型
        Map<String, Long> byType = all.stream()
                .filter(t -> t.getType() != null)
                .collect(Collectors.groupingBy(DecompileTask::getType, Collectors.counting()));

        // 3. 按系统
        List<SystemApplication> systems = systemMapper.selectList(null);
        Map<Long, String> sysNameMap = systems.stream()
                .collect(Collectors.toMap(SystemApplication::getId, SystemApplication::getName, (a, b) -> a));
        Map<String, Long> bySystem = new LinkedHashMap<>();
        all.stream()
                .filter(t -> t.getSystemId() != null)
                .collect(Collectors.groupingBy(DecompileTask::getSystemId, Collectors.counting()))
                .forEach((sysId, count) -> bySystem.put(sysNameMap.getOrDefault(sysId, "系统#" + sysId), count));

        // 4. 最近 N 天趋势
        Map<String, Long> dailyCount = new LinkedHashMap<>();
        Map<String, Long> dailyDuration = new LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            String date = LocalDate.now().minusDays(i).format(DTF);
            dailyCount.put(date, 0L);
            dailyDuration.put(date, 0L);
        }
        all.stream()
                .filter(t -> t.getCreatedAt() != null)
                .forEach(t -> {
                    String date = t.getCreatedAt().format(DTF);
                    if (dailyCount.containsKey(date)) {
                        dailyCount.merge(date, 1L, Long::sum);
                        if (t.getDurationMs() != null) {
                            dailyDuration.merge(date, t.getDurationMs(), Long::sum);
                        }
                    }
                });

        // 5. 汇总指标
        long total = all.size();
        long successCount = all.stream().filter(t -> "PUSHED".equals(t.getStatus())).count();
        long failedCount = all.stream().filter(t -> "FAILED".equals(t.getStatus())).count();
        double avgDurationMs = all.stream()
                .filter(t -> t.getDurationMs() != null && t.getDurationMs() > 0)
                .mapToLong(DecompileTask::getDurationMs)
                .average().orElse(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);
        result.put("avgDurationMs", Math.round(avgDurationMs));
        result.put("byStatus", byStatus);
        result.put("byType", byType);
        result.put("bySystem", bySystem);
        result.put("dailyCount", dailyCount);
        result.put("dailyDuration", dailyDuration);
        return result;
    }

    @Override
    public Map<String, Object> getAiUsageStats(Long systemId) {
        LambdaQueryWrapper<AiCallRecord> qw = new LambdaQueryWrapper<>();
        if (systemId != null) {
            // AiCallRecord 没有直接 systemId 字段，需要通过 taskId 关联
            List<Long> taskIds = taskMapper.selectList(
                            new LambdaQueryWrapper<DecompileTask>()
                                    .eq(DecompileTask::getSystemId, systemId)
                                    .select(DecompileTask::getId))
                    .stream().map(DecompileTask::getId).collect(Collectors.toList());
            if (taskIds.isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("totalCalls", 0);
                empty.put("totalInputTokens", 0);
                empty.put("totalOutputTokens", 0);
                empty.put("totalCost", 0);
                return empty;
            }
            qw.in(AiCallRecord::getTaskId, taskIds);
        }
        List<AiCallRecord> all = aiCallRecordMapper.selectList(qw);
        if (all.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("totalCalls", 0);
            empty.put("totalInputTokens", 0);
            empty.put("totalOutputTokens", 0);
            empty.put("totalCost", 0);
            return empty;
        }

        int totalCalls = all.size();
        int totalInput = all.stream().mapToInt(AiCallRecord::getInputToken).sum();
        int totalOutput = all.stream().mapToInt(AiCallRecord::getOutputToken).sum();
        long totalDuration = all.stream().filter(a -> a.getDurationMs() != null).mapToLong(AiCallRecord::getDurationMs).sum();
        long successCount = all.stream().filter(a -> Integer.valueOf(1).equals(a.getIsSuccess())).count();
        double successRate = totalCalls > 0 ? BigDecimal.valueOf(successCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalCalls), 1, RoundingMode.HALF_UP).doubleValue() : 0;

        // 按 modelName 分组
        Map<String, List<AiCallRecord>> byModel = all.stream()
                .filter(a -> a.getModelName() != null)
                .collect(Collectors.groupingBy(AiCallRecord::getModelName));
        List<Map<String, Object>> modelStats = byModel.entrySet().stream().map(e -> {
            List<AiCallRecord> recs = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("calls", recs.size());
            m.put("inputTokens", recs.stream().mapToInt(AiCallRecord::getInputToken).sum());
            m.put("outputTokens", recs.stream().mapToInt(AiCallRecord::getOutputToken).sum());
            m.put("success", recs.stream().filter(a -> Integer.valueOf(1).equals(a.getIsSuccess())).count());
            m.put("failed", recs.stream().filter(a -> !Integer.valueOf(1).equals(a.getIsSuccess())).count());
            return m;
        }).sorted((a, b) -> Integer.compare((Integer) b.get("calls"), (Integer) a.get("calls")))
                .collect(Collectors.toList());

        // 按 callStage 分组
        Map<String, List<AiCallRecord>> byStage = all.stream()
                .filter(a -> a.getCallStage() != null)
                .collect(Collectors.groupingBy(AiCallRecord::getCallStage));
        List<Map<String, Object>> stageStats = byStage.entrySet().stream().map(e -> {
            List<AiCallRecord> recs = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", e.getKey());
            m.put("calls", recs.size());
            m.put("inputTokens", recs.stream().mapToInt(AiCallRecord::getInputToken).sum());
            m.put("outputTokens", recs.stream().mapToInt(AiCallRecord::getOutputToken).sum());
            m.put("success", recs.stream().filter(a -> Integer.valueOf(1).equals(a.getIsSuccess())).count());
            m.put("failed", recs.stream().filter(a -> !Integer.valueOf(1).equals(a.getIsSuccess())).count());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCalls", totalCalls);
        result.put("totalInputTokens", totalInput);
        result.put("totalOutputTokens", totalOutput);
        result.put("totalDurationMs", totalDuration);
        result.put("successCount", successCount);
        result.put("successRate", successRate);
        result.put("byModel", modelStats);
        result.put("byStage", stageStats);
        return result;
    }

    @Override
    public List<Map<String, Object>> getPipelineStageStats() {
        List<DecompileTask> all = taskMapper.selectList(
                new LambdaQueryWrapper<DecompileTask>()
                        .isNotNull(DecompileTask::getDurationMs)
                        .gt(DecompileTask::getDurationMs, 0)
        );
        if (all.isEmpty()) return Collections.emptyList();

        // 按 status 分组统计平均耗时与数量
        Map<String, List<DecompileTask>> byStatus = all.stream()
                .filter(t -> t.getStatus() != null)
                .collect(Collectors.groupingBy(DecompileTask::getStatus));

        List<Map<String, Object>> result = new ArrayList<>();
        // 只关注核心流水线状态
        Set<String> coreStatuses = Set.of("PULLING_CODE", "PARSING_CODE", "SPLITTING_TASK",
                "ENTRYPOINT_REVIEW", "AI_ANALYZING", "MODULE_HIERARCHY", "MODULE_HIERARCHY_REVIEW",
                "GENERATING_DOC", "PENDING_REVIEW", "CONFIRMED", "PUSHED", "FAILED");

        for (String s : STATUS_ORDER) {
            if (!coreStatuses.contains(s)) continue;
            List<DecompileTask> group = byStatus.get(s);
            if (group == null || group.isEmpty()) continue;
            long count = group.size();
            double avgMs = group.stream()
                    .filter(t -> t.getDurationMs() != null)
                    .mapToLong(DecompileTask::getDurationMs)
                    .average().orElse(0);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", s);
            entry.put("count", count);
            entry.put("avgDurationMs", Math.round(avgMs));
            result.add(entry);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getSystemCoverage() {
        List<SystemApplication> systems = systemMapper.selectList(
                new LambdaQueryWrapper<SystemApplication>()
                        .isNull(SystemApplication::getDeletedAt)
        );
        if (systems.isEmpty()) return Collections.emptyList();

        // 查系统下的所有任务
        List<Long> sysIds = systems.stream().map(SystemApplication::getId).collect(Collectors.toList());
        List<DecompileTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<DecompileTask>().in(DecompileTask::getSystemId, sysIds));

        // 按 systemId 聚合
        Map<Long, List<DecompileTask>> tasksBySys = tasks.stream()
                .collect(Collectors.groupingBy(DecompileTask::getSystemId));

        // 查所有版本
        List<KnowledgeVersion> versions = versionMapper.selectList(null);
        Map<Long, List<KnowledgeVersion>> versionsBySys = versions.stream()
                .filter(v -> v.getSystemId() != null)
                .collect(Collectors.groupingBy(KnowledgeVersion::getSystemId));

        List<Map<String, Object>> result = new ArrayList<>();
        for (SystemApplication sys : systems) {
            List<DecompileTask> sysTasks = tasksBySys.getOrDefault(sys.getId(), Collections.emptyList());
            List<KnowledgeVersion> sysVersions = versionsBySys.getOrDefault(sys.getId(), Collections.emptyList());

            // 草稿数：通过版本系统关联或直接统计任务相关工作区，简化处理取版本关联的任务草稿
            long taskCount = sysTasks.size();
            long versionCount = sysVersions.size();
            long draftCount = sysTasks.stream()
                    .filter(t -> "PUSHED".equals(t.getStatus()) || "CONFIRMED".equals(t.getStatus()))
                    .count();

            Optional<LocalDateTime> lastDecompile = sysTasks.stream()
                    .filter(t -> t.getEndedAt() != null)
                    .map(DecompileTask::getEndedAt)
                    .max(LocalDateTime::compareTo);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("systemId", sys.getId());
            entry.put("systemName", sys.getName());
            entry.put("taskCount", taskCount);
            entry.put("draftCount", draftCount);
            entry.put("versionCount", versionCount);
            entry.put("lastDecompileAt", lastDecompile.map(d -> d.format(DTF)).orElse(null));
            result.add(entry);
        }
        return result;
    }
}