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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 方法调用链图 BFS 实现。
 * <p>流程：rootSignatures 入队 → 查出边 → 将 target 规范为 {@code 短类名#method(args)}
 * （旧数据裸方法名 + dependency_name / dependency_candidates 兜底）→ 去重入队。
 * 返回 {@link LinkedHashSet}，顺序为 BFS 发现序（入口 → 下游），供文档拼装保序。</p>
 */
@Slf4j
@Service
public class MethodCallGraphServiceImpl implements MethodCallGraphService {

    /** 正向 BFS 总深度上限（含根为 0）；防止超大图撑爆文档 prompt。 */
    static final int MAX_BFS_DEPTH = 10;

    /**
     * 连续「同类调用」额外深度上限（跨类边会清零计数）。
     * 用于收束 Service 内 private 助手链（requireProduct → findById → …）。
     */
    static final int MAX_SAME_CLASS_DEPTH = 3;

    @Autowired
    private MethodCallMapper methodCallMapper;

    private static final class BfsNode {
        final String signature;
        final int depth;
        final int sameClassDepth;

        BfsNode(String signature, int depth, int sameClassDepth) {
            this.signature = signature;
            this.depth = depth;
            this.sameClassDepth = sameClassDepth;
        }
    }

    @Override
    public Set<String> resolveReachableMethods(Long taskId, Set<String> rootSignatures) {
        if (taskId == null || rootSignatures == null || rootSignatures.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> visited = new LinkedHashSet<>();
        Deque<BfsNode> queue = new ArrayDeque<>();
        for (String root : rootSignatures) {
            if (!StringUtils.hasText(root)) {
                continue;
            }
            // 根签名也可能是 FQ#method，统一短类名后再入队，便于精确匹配 caller_signature
            String normalizedRoot = normalizeClassPrefix(root.trim());
            if (visited.add(normalizedRoot)) {
                queue.add(new BfsNode(normalizedRoot, 0, 0));
            }
            if (!normalizedRoot.equals(root.trim()) && visited.add(root.trim())) {
                queue.add(new BfsNode(root.trim(), 0, 0));
            }
        }

        int edgeHits = 0;
        int rootMissEdges = 0;
        int skippedBareTarget = 0;
        int skippedDepth = 0;

        while (!queue.isEmpty()) {
            BfsNode curNode = queue.poll();
            String cur = curNode.signature;
            List<MethodCall> outgoing = findOutgoing(taskId, cur);
            if (outgoing.isEmpty() && rootSignatures.contains(cur)) {
                rootMissEdges++;
            }
            if (curNode.depth >= MAX_BFS_DEPTH) {
                continue;
            }
            String curClass = classNameOf(cur);
            for (MethodCall mc : outgoing) {
                edgeHits++;
                List<String> nextKeys = resolveNextSignatures(taskId, mc);
                if (nextKeys.isEmpty()) {
                    skippedBareTarget++;
                    log.warn("MethodCallGraphService BFS 无法解析下一跳 taskId={} caller={} target={} dep={}",
                            taskId, mc.getCallerSignature(), mc.getTargetSignature(), mc.getDependencyName());
                    continue;
                }
                for (String next : nextKeys) {
                    String nextClass = classNameOf(next);
                    boolean sameClass = StringUtils.hasText(curClass)
                            && curClass.equals(nextClass);
                    int nextSameDepth = sameClass ? curNode.sameClassDepth + 1 : 0;
                    if (sameClass && nextSameDepth > MAX_SAME_CLASS_DEPTH) {
                        skippedDepth++;
                        continue;
                    }
                    int nextDepth = curNode.depth + 1;
                    if (nextDepth > MAX_BFS_DEPTH) {
                        skippedDepth++;
                        continue;
                    }
                    if (visited.add(next)) {
                        queue.add(new BfsNode(next, nextDepth, nextSameDepth));
                    }
                }
            }
        }

        // 仅保留含 # 的节点，供 groupByClass / 源码截取使用（保持 LinkedHashSet 发现序）
        Set<String> result = new LinkedHashSet<>();
        for (String sig : visited) {
            if (sig != null && sig.indexOf('#') > 0) {
                result.add(sig);
            }
        }

        if (rootMissEdges > 0 && edgeHits == 0) {
            log.warn("MethodCallGraphService BFS 根签名在 ci_method_call 无出边 taskId={} roots={} rootMissEdges={} sampleRoots={}",
                    taskId, rootSignatures.size(), rootMissEdges, sampleRoots(rootSignatures, 3));
        } else {
            log.info("MethodCallGraphService BFS taskId={} roots={} reachable={} edgeHits={} skippedBare={} skippedDepth={}",
                    taskId, rootSignatures.size(), result.size(), edgeHits, skippedBareTarget, skippedDepth);
        }
        return result;
    }

    private static String classNameOf(String signature) {
        if (!StringUtils.hasText(signature)) {
            return null;
        }
        int hash = signature.indexOf('#');
        if (hash <= 0) {
            return null;
        }
        return stripPackage(signature.substring(0, hash));
    }

    /**
     * 精确匹配 caller_signature；失败则按 {@code 短类名#methodName(} 前缀兜底（兼容参数名 / 装箱差异）。
     */
    protected List<MethodCall> findOutgoing(Long taskId, String callerSignature) {
        if (!StringUtils.hasText(callerSignature)) {
            return Collections.emptyList();
        }
        List<MethodCall> exact = methodCallMapper.selectList(
                new LambdaQueryWrapper<MethodCall>()
                        .eq(MethodCall::getTaskId, taskId)
                        .eq(MethodCall::getCallerSignature, callerSignature)
                        .isNotNull(MethodCall::getTargetSignature)
        );
        if (!exact.isEmpty()) {
            return exact;
        }
        String prefix = methodPrefix(callerSignature);
        if (!StringUtils.hasText(prefix)) {
            return Collections.emptyList();
        }
        return methodCallMapper.selectList(
                new LambdaQueryWrapper<MethodCall>()
                        .eq(MethodCall::getTaskId, taskId)
                        .and(w -> w.likeRight(MethodCall::getCallerSignature, prefix + "(")
                                .or()
                                .eq(MethodCall::getCallerSignature, prefix + "()"))
                        .isNotNull(MethodCall::getTargetSignature)
        );
    }

    /**
     * 将一条边的 target 解析为可入队的完整签名列表（声明类型 + 多态候选）。
     */
    private List<String> resolveNextSignatures(Long taskId, MethodCall mc) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String target = mc.getTargetSignature();
        String methodName = StringUtils.hasText(mc.getTargetMethod())
                ? mc.getTargetMethod().trim()
                : extractMethodName(target);
        if (!StringUtils.hasText(methodName)) {
            return Collections.emptyList();
        }

        String paramSuffix = extractParamSuffix(target, methodName);
        List<String> typeHints = collectTypeHints(mc);

        if (StringUtils.hasText(target) && target.contains("#")) {
            String normalized = normalizeClassPrefix(target.trim());
            addResolvedKeys(taskId, normalized, methodName, paramSuffix, out);
        }

        for (String typeHint : typeHints) {
            String shortClass = stripPackage(typeHint);
            if (!StringUtils.hasText(shortClass)) {
                continue;
            }
            String preferred = shortClass + "#" + methodName
                    + (paramSuffix != null ? paramSuffix : "");
            addResolvedKeys(taskId, preferred, methodName, paramSuffix, out);
        }

        // 旧数据：target 仅方法名且无 dependency → 无法解析，返回空
        return new ArrayList<>(out);
    }

    private void addResolvedKeys(Long taskId, String preferred, String methodName,
                                 String paramSuffix, Set<String> out) {
        if (!StringUtils.hasText(preferred) || preferred.indexOf('#') <= 0) {
            return;
        }
        // 叶子方法也必须进入 visited，即使没有作为 caller 的出边
        out.add(preferred);

        String shortClass = preferred.substring(0, preferred.indexOf('#'));
        String prefix = shortClass + "#" + methodName;
        for (String dbSig : findMatchingCallerSignatures(taskId, prefix, paramSuffix)) {
            out.add(dbSig);
        }
    }

    protected List<String> findMatchingCallerSignatures(Long taskId, String classMethodPrefix, String paramSuffix) {
        if (!StringUtils.hasText(classMethodPrefix)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        if (paramSuffix != null && paramSuffix.startsWith("(")) {
            String exact = classMethodPrefix + paramSuffix;
            List<MethodCall> exactRows = methodCallMapper.selectList(
                    new LambdaQueryWrapper<MethodCall>()
                            .eq(MethodCall::getTaskId, taskId)
                            .eq(MethodCall::getCallerSignature, exact)
                            .select(MethodCall::getCallerSignature)
                            .last("LIMIT 20")
            );
            for (MethodCall row : exactRows) {
                if (StringUtils.hasText(row.getCallerSignature())) {
                    matched.add(row.getCallerSignature());
                }
            }
            if (!matched.isEmpty()) {
                return new ArrayList<>(matched);
            }
        }
        List<MethodCall> prefixRows = methodCallMapper.selectList(
                new LambdaQueryWrapper<MethodCall>()
                        .eq(MethodCall::getTaskId, taskId)
                        .and(w -> w.likeRight(MethodCall::getCallerSignature, classMethodPrefix + "(")
                                .or()
                                .eq(MethodCall::getCallerSignature, classMethodPrefix + "()"))
                        .select(MethodCall::getCallerSignature)
                        .last("LIMIT 50")
        );
        for (MethodCall row : prefixRows) {
            if (StringUtils.hasText(row.getCallerSignature())) {
                matched.add(row.getCallerSignature());
            }
        }
        return new ArrayList<>(matched);
    }

    private static List<String> collectTypeHints(MethodCall mc) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (mc == null) {
            return Collections.emptyList();
        }
        if (StringUtils.hasText(mc.getDependencyName())) {
            hints.add(mc.getDependencyName().trim());
        }
        if (StringUtils.hasText(mc.getDependencyCandidates())) {
            for (String part : mc.getDependencyCandidates().split(",")) {
                if (StringUtils.hasText(part)) {
                    hints.add(part.trim());
                }
            }
        }
        return new ArrayList<>(hints);
    }

    /** {@code Class#method(args)} → {@code Class#method} */
    private static String methodPrefix(String fullSignature) {
        if (!StringUtils.hasText(fullSignature)) {
            return null;
        }
        String s = normalizeClassPrefix(fullSignature.trim());
        int hash = s.indexOf('#');
        if (hash <= 0 || hash >= s.length() - 1) {
            return null;
        }
        String methodPart = s.substring(hash + 1);
        int paren = methodPart.indexOf('(');
        String methodName = paren >= 0 ? methodPart.substring(0, paren) : methodPart;
        if (!StringUtils.hasText(methodName)) {
            return null;
        }
        return s.substring(0, hash + 1) + methodName;
    }

    /**
     * 从 target_signature 提取参数后缀：{@code (Long)} / {@code ()}；无括号返回 null（前缀匹配）。
     */
    private static String extractParamSuffix(String target, String methodName) {
        if (!StringUtils.hasText(target)) {
            return null;
        }
        String methodPart = target;
        int hash = target.indexOf('#');
        if (hash >= 0) {
            methodPart = target.substring(hash + 1);
        }
        int paren = methodPart.indexOf('(');
        if (paren < 0) {
            return null;
        }
        return methodPart.substring(paren);
    }

    private static String extractMethodName(String targetOrMethod) {
        if (!StringUtils.hasText(targetOrMethod)) {
            return null;
        }
        String s = targetOrMethod.trim();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(hash + 1);
        }
        int paren = s.indexOf('(');
        return paren >= 0 ? s.substring(0, paren) : s;
    }

    /** FQ#method → 短类名#method */
    private static String normalizeClassPrefix(String signature) {
        if (!StringUtils.hasText(signature)) {
            return signature;
        }
        int hash = signature.indexOf('#');
        if (hash <= 0) {
            return signature;
        }
        return stripPackage(signature.substring(0, hash)) + signature.substring(hash);
    }

    private static String stripPackage(String fqOrShort) {
        if (!StringUtils.hasText(fqOrShort)) {
            return fqOrShort;
        }
        String t = fqOrShort.trim();
        int colon = t.lastIndexOf(':');
        if (colon >= 0 && colon < t.length() - 1) {
            t = t.substring(colon + 1).trim();
        }
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
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
