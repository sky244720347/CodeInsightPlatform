package com.company.codeinsight.modules.callchain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.callchain.service.MethodCallGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 方法调用链图 BFS 实现
 * 流程：rootSignatures 入队 → 查 ci_method_call.caller_signature = cur
 *       收集 target_signature → 去重入队 → 直到队列空
 *
 * 注意：MVP 阶段 caller_signature 精确（className#method(args)），target_signature 简化为方法名（无 args），
 * 阶段 3 升级 target 端参数解析后可精确按 (className#method(args)) BFS。
 */
@Slf4j
@Service
public class MethodCallGraphServiceImpl implements MethodCallGraphService {

    @Autowired
    private MethodCallMapper methodCallMapper;

    @Override
    public Set<String> resolveReachableMethods(Long taskId, Set<String> rootSignatures) {
        if (taskId == null || rootSignatures == null || rootSignatures.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> visited = new LinkedHashSet<>(rootSignatures);
        Deque<String> queue = new ArrayDeque<>(rootSignatures);
        int edgeHits = 0;
        int rootMissEdges = 0;

        while (!queue.isEmpty()) {
            String cur = queue.poll();
            // 查 ci_method_call：caller_signature = cur，取 target_signature
            List<MethodCall> outgoing = methodCallMapper.selectList(
                    new LambdaQueryWrapper<MethodCall>()
                            .eq(MethodCall::getTaskId, taskId)
                            .eq(MethodCall::getCallerSignature, cur)
                            .isNotNull(MethodCall::getTargetSignature)
            );
            if (outgoing.isEmpty() && rootSignatures.contains(cur)) {
                rootMissEdges++;
            }
            for (MethodCall mc : outgoing) {
                edgeHits++;
                String target = mc.getTargetSignature();
                if (StringUtils.hasText(target) && !visited.contains(target)) {
                    visited.add(target);
                    queue.add(target);
                }
            }
        }
        // roots 本身始终在 visited；若 rootMissEdges≈roots 且 edgeHits=0，多半是签名格式不匹配（FQ vs 短类名）
        if (rootMissEdges > 0 && edgeHits == 0) {
            log.warn("MethodCallGraphService BFS 根签名在 ci_method_call 无出边 taskId={} roots={} rootMissEdges={} sampleRoots={}",
                    taskId, rootSignatures.size(), rootMissEdges, sampleRoots(rootSignatures, 3));
        } else {
            log.info("MethodCallGraphService BFS taskId={} roots={} reachable={} edgeHits={}",
                    taskId, rootSignatures.size(), visited.size(), edgeHits);
        }
        return visited;
    }

    private static String sampleRoots(Set<String> roots, int limit) {
        if (roots == null || roots.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (String r : roots) {
            if (i > 0) sb.append(", ");
            sb.append(r);
            if (++i >= limit) {
                sb.append(", ...");
                break;
            }
        }
        return sb.append("]").toString();
    }
}