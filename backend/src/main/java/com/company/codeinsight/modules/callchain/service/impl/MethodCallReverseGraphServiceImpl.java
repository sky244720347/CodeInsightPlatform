package com.company.codeinsight.modules.callchain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.callchain.model.EntryMethodHit;
import com.company.codeinsight.modules.callchain.service.MethodCallReverseGraphService;
import com.company.codeinsight.modules.callchain.support.IncrementalImpactSupport;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class MethodCallReverseGraphServiceImpl implements MethodCallReverseGraphService {

    @Autowired
    private MethodCallMapper methodCallMapper;

    @Autowired
    private JavaParserService javaParserService;

    @Autowired
    private TaskWorkspacePaths taskWorkspacePaths;

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
                                .and(w -> w.eq(MethodCall::getDependencyName, calleeSimple)
                                        .or().like(MethodCall::getDependencyCandidates, calleeSimple))
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
        List<MethodCall> directRows = methodCallMapper.selectList(
                new LambdaQueryWrapper<MethodCall>()
                        .eq(MethodCall::getTaskId, taskId)
                        .eq(MethodCall::getDependencyName, simpleCallee)
                        .isNotNull(MethodCall::getTargetSignature)
        );
        for (MethodCall mc : directRows) {
            if (StringUtils.hasText(mc.getTargetSignature())) {
                seeds.add(mc.getTargetSignature());
            }
        }
        List<MethodCall> polyRows = methodCallMapper.selectList(
                new LambdaQueryWrapper<MethodCall>()
                        .eq(MethodCall::getTaskId, taskId)
                        .like(MethodCall::getDependencyCandidates, simpleCallee)
                        .isNotNull(MethodCall::getTargetSignature)
        );
        for (MethodCall mc : polyRows) {
            if (StringUtils.hasText(mc.getTargetSignature())) {
                seeds.add(mc.getTargetSignature());
            }
        }
        if (!seeds.isEmpty()) {
            return seeds;
        }
        return collectMethodNamesFromSource(taskId, changedFqcn);
    }

    /**
     * fallback：ci_method_call 无 dep/candidates 命中时，从源码 AST 提取被改类的方法名作为 BFS 种子。
     */
    private Set<String> collectMethodNamesFromSource(Long taskId, String changedFqcn) {
        Set<String> seeds = new LinkedHashSet<>();
        String filePath = lookupClassFilePath(taskId, changedFqcn);
        if (!StringUtils.hasText(filePath)) {
            return seeds;
        }
        File classFile = taskWorkspacePaths.taskProjectPath(taskId).resolve(filePath).toFile();
        if (!classFile.isFile()) {
            return seeds;
        }
        try {
            ParsedClassInfo info = javaParserService.parseFile(classFile);
            if (info != null && info.getMethods() != null) {
                for (ParsedClassInfo.MethodInfo mi : info.getMethods()) {
                    if (StringUtils.hasText(mi.getName())) {
                        seeds.add(mi.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("collectMethodNamesFromSource failed taskId={} fqcn={}: {}", taskId, changedFqcn, e.getMessage());
        }
        return seeds;
    }

    private String lookupClassFilePath(Long taskId, String className) {
        try {
            List<MethodCall> calls = methodCallMapper.selectList(
                    new LambdaQueryWrapper<MethodCall>()
                            .eq(MethodCall::getTaskId, taskId)
                            .eq(MethodCall::getClassName, className)
                            .last("LIMIT 1")
            );
            if (!calls.isEmpty() && StringUtils.hasText(calls.get(0).getFilePath())) {
                return calls.get(0).getFilePath();
            }
        } catch (Exception e) {
            log.warn("lookupClassFilePath failed for {}: {}", className, e.getMessage());
        }
        if (className.contains(".")) {
            String pkgPath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
            String simple = className.substring(className.lastIndexOf('.') + 1);
            return "src/main/java/" + pkgPath + "/" + simple + ".java";
        }
        return "src/main/java/" + className + ".java";
    }

    private record CalleeNode(String fqcn, String methodName) {
    }
}
