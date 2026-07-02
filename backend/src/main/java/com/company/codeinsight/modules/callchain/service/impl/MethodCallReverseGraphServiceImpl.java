package com.company.codeinsight.modules.callchain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.callchain.model.EntryMethodHit;
import com.company.codeinsight.modules.callchain.service.MethodCallReverseGraphService;
import com.company.codeinsight.modules.callchain.support.IncrementalImpactSupport;
import com.company.codeinsight.modules.chunk.entity.CodeChunk;
import com.company.codeinsight.modules.chunk.mapper.CodeChunkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class MethodCallReverseGraphServiceImpl implements MethodCallReverseGraphService {

    private static final String METHOD_CHUNK = "METHOD";

    @Autowired
    private MethodCallMapper methodCallMapper;

    @Autowired
    private CodeChunkMapper codeChunkMapper;

    @Override
    public List<EntryMethodHit> resolveCallingEntries(Long taskId,
                                                      String changedFqcn,
                                                      Set<String> entryClassNames,
                                                      int maxDepth) {
        if (taskId == null || !StringUtils.hasText(changedFqcn) || entryClassNames == null || entryClassNames.isEmpty()) {
            return List.of();
        }
        String simpleCallee = IncrementalImpactSupport.simpleClassName(changedFqcn);
        Set<String> seeds = collectSeeds(taskId, changedFqcn, simpleCallee);
        if (seeds.isEmpty()) {
            return List.of();
        }

        Set<String> visitedCallers = new LinkedHashSet<>();
        Set<EntryMethodHit> hits = new LinkedHashSet<>();
        Deque<CalleeNode> queue = new ArrayDeque<>();
        for (String method : seeds) {
            queue.add(new CalleeNode(changedFqcn, method));
        }

        int depth = 0;
        while (!queue.isEmpty() && depth < maxDepth) {
            int levelSize = queue.size();
            depth++;
            for (int i = 0; i < levelSize; i++) {
                CalleeNode node = queue.poll();
                if (node == null) {
                    continue;
                }
                String calleeSimple = IncrementalImpactSupport.simpleClassName(node.fqcn());
                List<MethodCall> rows = methodCallMapper.selectList(
                        new LambdaQueryWrapper<MethodCall>()
                                .eq(MethodCall::getTaskId, taskId)
                                .eq(MethodCall::getTargetSignature, node.methodName())
                                .eq(MethodCall::getDependencyName, calleeSimple)
                );
                for (MethodCall row : rows) {
                    String callerSig = row.getCallerSignature();
                    if (!StringUtils.hasText(callerSig) || visitedCallers.contains(callerSig)) {
                        continue;
                    }
                    visitedCallers.add(callerSig);
                    String callerFqcn = row.getClassName();
                    if (entryClassNames.contains(callerFqcn)) {
                        hits.add(new EntryMethodHit(callerFqcn, callerSig));
                    } else if (StringUtils.hasText(callerFqcn)) {
                        String callerMethod = IncrementalImpactSupport.extractMethodName(callerSig);
                        queue.add(new CalleeNode(callerFqcn, callerMethod));
                    }
                }
            }
        }
        log.debug("Reverse BFS taskId={} changedFqcn={} seeds={} hits={} depth={}",
                taskId, changedFqcn, seeds.size(), hits.size(), depth);
        return new ArrayList<>(hits);
    }

    private Set<String> collectSeeds(Long taskId, String changedFqcn, String simpleCallee) {
        Set<String> seeds = new LinkedHashSet<>();
        List<MethodCall> asCallee = methodCallMapper.selectList(
                new LambdaQueryWrapper<MethodCall>()
                        .eq(MethodCall::getTaskId, taskId)
                        .eq(MethodCall::getDependencyName, simpleCallee)
                        .isNotNull(MethodCall::getTargetSignature)
        );
        for (MethodCall mc : asCallee) {
            if (StringUtils.hasText(mc.getTargetSignature())) {
                seeds.add(mc.getTargetSignature());
            }
        }
        if (!seeds.isEmpty()) {
            return seeds;
        }
        List<CodeChunk> chunks = codeChunkMapper.selectList(
                new LambdaQueryWrapper<CodeChunk>()
                        .eq(CodeChunk::getTaskId, taskId)
                        .eq(CodeChunk::getChunkType, METHOD_CHUNK)
                        .eq(CodeChunk::getClassName, changedFqcn)
        );
        for (CodeChunk chunk : chunks) {
            if (StringUtils.hasText(chunk.getMethodName())) {
                seeds.add(chunk.getMethodName());
            }
        }
        return seeds;
    }

    private record CalleeNode(String fqcn, String methodName) {
    }
}
