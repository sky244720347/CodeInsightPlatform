package com.company.codeinsight.modules.token.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.mapper.SystemApplicationMapper;
import com.company.codeinsight.modules.token.entity.TokenUsageAudit;
import com.company.codeinsight.modules.token.mapper.TokenUsageAuditMapper;
import com.company.codeinsight.modules.token.service.TokenAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Token 用量审计管理服务实现类
 * 负责 Token 快捷账目写入、费用换算、图表大盘聚合（近 7 日耗量趋势、各模型占比、系统排行）和防超限超售熔断所需要的累计用量获取。
 */
@Service
public class TokenAuditServiceImpl implements TokenAuditService {

    @Autowired
    private TokenUsageAuditMapper auditMapper;

    @Autowired
    private SystemApplicationMapper systemMapper;

    /**
     * 实时记账审计记录一次 Token 消耗并折算费用
     * 计费公式：输入每百万 Token 约 2.0 美元，输出每百万 Token 约 6.0 美元。
     */
    @Override
    public void logTokenUsage(Long systemId, Long taskId, String modelName, int inputTokens, int outputTokens, String type, boolean isSuccess) {
        logTokenUsage(systemId, taskId, null, modelName, inputTokens, outputTokens, type, isSuccess);
    }

    @Override
    public void logTokenUsage(Long systemId, Long taskId, Long userId, String modelName, int inputTokens, int outputTokens, String type, boolean isSuccess) {
        int total = inputTokens + outputTokens;

        // 计算计费费用：输入每百万 Token 约 2 美元，输出每百万 Token 约 6 美元
        double costVal = (inputTokens * 0.000002) + (outputTokens * 0.000006);
        BigDecimal cost = BigDecimal.valueOf(costVal).setScale(6, RoundingMode.HALF_UP);

        TokenUsageAudit audit = new TokenUsageAudit();
        audit.setSystemId(systemId != null ? systemId : 0L);
        audit.setTaskId(taskId != null ? taskId : 0L);
        audit.setUserId(userId);
        audit.setPromptVersion(1); // 默认提示词版本
        audit.setModelName(modelName != null ? modelName : "MiniMax-M3");
        audit.setInputTokens(inputTokens);
        audit.setOutputTokens(outputTokens);
        audit.setTotalTokens(total);
        audit.setCost(cost);
        audit.setType(type);
        audit.setStatus(isSuccess ? 1 : 0);
        audit.setCreatedDate(LocalDateTime.now());

        auditMapper.insert(audit);
    }

    /**
     * 条件分页查询 Token 审计日志，按时间倒序排列
     */
    @Override
    public Page<TokenUsageAudit> listAuditPage(int current, int size, Long systemId, String modelName, String type) {
        Page<TokenUsageAudit> page = new Page<>(current, size);
        LambdaQueryWrapper<TokenUsageAudit> qw = new LambdaQueryWrapper<>();
        qw.eq(systemId != null, TokenUsageAudit::getSystemId, systemId)
          .eq(StringUtils.hasText(modelName), TokenUsageAudit::getModelName, modelName)
          .eq(StringUtils.hasText(type), TokenUsageAudit::getType, type)
          .orderByDesc(TokenUsageAudit::getCreatedDate);
        return auditMapper.selectPage(page, qw);
    }

    /**
     * 汇总 Token 用量统计指标以供前端大屏/工作台展示
     */
    @Override
    public Map<String, Object> getAuditStats(Long systemId) {
        // 1. 获取指定系统的所有用量记录
        List<TokenUsageAudit> all = auditMapper.selectList(
                new LambdaQueryWrapper<TokenUsageAudit>()
                        .eq(systemId != null, TokenUsageAudit::getSystemId, systemId)
        );

        int totalInput = all.stream().mapToInt(TokenUsageAudit::getInputTokens).sum();
        int totalOutput = all.stream().mapToInt(TokenUsageAudit::getOutputTokens).sum();
        int totalTokens = all.stream().mapToInt(TokenUsageAudit::getTotalTokens).sum();
        BigDecimal totalCost = all.stream()
                .map(TokenUsageAudit::getCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 2. 统计各系统消耗排行
        List<SystemApplication> systems = systemMapper.selectList(null);
        Map<Long, String> sysNameMap = systems.stream()
                .collect(Collectors.toMap(SystemApplication::getId, SystemApplication::getName, (a, b) -> a));

        Map<Long, Integer> sysGroup = all.stream()
                .collect(Collectors.groupingBy(TokenUsageAudit::getSystemId, Collectors.summingInt(TokenUsageAudit::getTotalTokens)));
        
        List<Map<String, Object>> systemRanking = sysGroup.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("systemId", entry.getKey());
                    item.put("name", sysNameMap.getOrDefault(entry.getKey(), "系统ID: " + entry.getKey()));
                    item.put("tokens", entry.getValue());
                    return item;
                })
                .sorted((a, b) -> Integer.compare((Integer) b.get("tokens"), (Integer) a.get("tokens")))
                .limit(10)
                .collect(Collectors.toList());

        // 3. 统计各模型消耗占比占比
        Map<String, Integer> modelGroup = all.stream()
                .collect(Collectors.groupingBy(TokenUsageAudit::getModelName, Collectors.summingInt(TokenUsageAudit::getTotalTokens)));
        List<Map<String, Object>> modelRatio = modelGroup.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("name", entry.getKey());
                    item.put("value", entry.getValue());
                    return item;
                })
                .collect(Collectors.toList());

        // 4. 构建近 7 天每日用量趋势
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        Map<String, Integer> trendMap = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            String dateStr = LocalDateTime.now().minusDays(i).format(dtf);
            trendMap.put(dateStr, 0);
        }

        all.forEach(a -> {
            String dateStr = a.getCreatedDate().format(dtf);
            if (trendMap.containsKey(dateStr)) {
                trendMap.put(dateStr, trendMap.get(dateStr) + a.getTotalTokens());
            }
        });

        List<Map<String, Object>> dailyTrends = trendMap.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("date", entry.getKey());
                    item.put("tokens", entry.getValue());
                    return item;
                })
                .collect(Collectors.toList());

        // 5. 组合并返回指标
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalInputTokens", totalInput);
        stats.put("totalOutputTokens", totalOutput);
        stats.put("totalTokens", totalTokens);
        stats.put("totalCost", totalCost.setScale(4, RoundingMode.HALF_UP));
        stats.put("systemRanking", systemRanking);
        stats.put("modelRatio", modelRatio);
        stats.put("dailyTrends", dailyTrends);

        return stats;
    }

    /**
     * 计算特定扫描任务目前为止累计已消耗的 Token 数
     */
    @Override
    public int getTaskCumulativeTokens(Long taskId) {
        if (taskId == null) return 0;
        List<TokenUsageAudit> list = auditMapper.selectList(
                new LambdaQueryWrapper<TokenUsageAudit>()
                        .eq(TokenUsageAudit::getTaskId, taskId)
        );
        return list.stream().mapToInt(TokenUsageAudit::getTotalTokens).sum();
    }

    /**
     * 计算特定系统在当前自然月内已消耗的 Token 数，用于月度配额控制
     */
    @Override
    public int getSystemMonthlyTokens(Long systemId) {
        if (systemId == null) return 0;
        // 定位本月 1 号 00:00:00.000 作为起算时间
        LocalDateTime startOfMonth = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<TokenUsageAudit> list = auditMapper.selectList(
                new LambdaQueryWrapper<TokenUsageAudit>()
                        .eq(TokenUsageAudit::getSystemId, systemId)
                        .ge(TokenUsageAudit::getCreatedDate, startOfMonth)
        );
        return list.stream().mapToInt(TokenUsageAudit::getTotalTokens).sum();
    }

    @Override
    public int getUserDailyTokens(Long userId) {
        if (userId == null) return 0;
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<TokenUsageAudit> list = auditMapper.selectList(
                new LambdaQueryWrapper<TokenUsageAudit>()
                        .eq(TokenUsageAudit::getUserId, userId)
                        .ge(TokenUsageAudit::getCreatedDate, startOfDay)
        );
        return list.stream().mapToInt(TokenUsageAudit::getTotalTokens).sum();
    }

    @Override
    public int getUserMonthlyTokens(Long userId) {
        if (userId == null) return 0;
        LocalDateTime startOfMonth = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<TokenUsageAudit> list = auditMapper.selectList(
                new LambdaQueryWrapper<TokenUsageAudit>()
                        .eq(TokenUsageAudit::getUserId, userId)
                        .ge(TokenUsageAudit::getCreatedDate, startOfMonth)
        );
        return list.stream().mapToInt(TokenUsageAudit::getTotalTokens).sum();
    }
}
