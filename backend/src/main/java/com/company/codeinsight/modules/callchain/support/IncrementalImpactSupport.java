package com.company.codeinsight.modules.callchain.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.callchain.model.EntryMethodHit;
import com.company.codeinsight.modules.hierarchy.model.FunctionDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.model.SubModuleDto;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 增量影响分析共享工具：模块命中判定、入口→模块映射、Phase 3 多态扩展。
 */
public final class IncrementalImpactSupport {

    private IncrementalImpactSupport() {
    }

    /**
     * Phase 3 多态扩展：把 changedFqSet 扩展为"包含所有 polymorphic ancestors 的集合"。
     *
     * <p>动机：当 fq 是接口/父类 {@code Notifier} 的具象实现 {@code EmailNotifierImpl} 改了，
     * {@code function.classPaths} 里只引用 {@code Notifier} 的函数也应该被视作受影响。
     * 直查 {@code classPaths.contains("EmailNotifierImpl")} 必然漏——本方法用
     * {@code ci_method_call.dependency_candidates} 反查所有"将 {@code EmailNotifierImpl} 列
     * 为多态候选"且依赖名是某个父类型的 ci_method_call 行，把那个父类型 FQ 加进扩展集。</p>
     *
     * <p>注意：LIKE '%fq%' 仍可能误撞同名长尾（如 {@code EmailNotifierImplHelper}），所以
     * 这里拿到候选后再用 {@link #candidatesContainExact} 做 token 级精确过滤。</p>
     */
    public static Set<String> expandChangedFqSetWithPolymorphicAncestors(Long taskId,
                                                                        Set<String> changedFqSet,
                                                                        MethodCallMapper mapper) {
        if (changedFqSet == null || changedFqSet.isEmpty()) {
            return changedFqSet == null ? Set.of() : new LinkedHashSet<>(changedFqSet);
        }
        if (taskId == null || mapper == null) {
            return new LinkedHashSet<>(changedFqSet);
        }
        Set<String> expanded = new LinkedHashSet<>(changedFqSet);
        for (String changedFq : changedFqSet) {
            if (!StringUtils.hasText(changedFq)) continue;
            List<MethodCall> rows = mapper.selectList(
                    new LambdaQueryWrapper<MethodCall>()
                            .eq(MethodCall::getTaskId, taskId)
                            .like(MethodCall::getDependencyCandidates, changedFq)
                            .select(MethodCall::getDependencyName, MethodCall::getDependencyCandidates)
            );
            for (MethodCall mc : rows) {
                String declared = mc.getDependencyName();
                if (!StringUtils.hasText(declared)) continue;
                if (candidatesContainExact(mc.getDependencyCandidates(), changedFq)) {
                    expanded.add(declared);
                }
            }
        }
        return expanded;
    }

    /**
     * 在逗号分隔的 FQ 字符串里检查是否存在某个 FQ（精确 token）。
     * 避免 LIKE '%fq%' 把 'EmailNotifierImplHelper' 当作 'EmailNotifierImpl' 的祖先。
     */
    private static boolean candidatesContainExact(String candidatesCsv, String fqcn) {
        if (!StringUtils.hasText(candidatesCsv)) return false;
        for (String token : candidatesCsv.split(",")) {
            if (fqcn.equals(token.trim())) return true;
        }
        return false;
    }

    public static boolean moduleTouchedByChange(ModuleDto moduleDto, Set<String> changedFqSet) {
        if (moduleDto == null || changedFqSet == null || changedFqSet.isEmpty()) {
            return false;
        }
        for (SubModuleDto sm : moduleDto.getSubModules().values()) {
            for (FunctionDto fn : sm.getFunctions().values()) {
                if (fn.getClassPaths() != null) {
                    for (String cp : fn.getClassPaths()) {
                        if (cp != null && changedFqSet.contains(cp)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public static Set<String> mapEntryClassToModuleIds(ModuleHierarchy hierarchy, String entryClassName) {
        Set<String> moduleIds = new LinkedHashSet<>();
        if (hierarchy == null || !StringUtils.hasText(entryClassName)) {
            return moduleIds;
        }
        for (ModuleDto module : hierarchy.getModules().values()) {
            if (moduleContainsClass(module, entryClassName)) {
                moduleIds.add(module.getId());
            }
        }
        return moduleIds;
    }

    public static Set<String> mapEntryMethodToModuleIds(ModuleHierarchy hierarchy, EntryMethodHit hit) {
        Set<String> moduleIds = new LinkedHashSet<>();
        if (hierarchy == null || hit == null || !StringUtils.hasText(hit.entryClassName())) {
            return moduleIds;
        }
        String entryMethodName = extractMethodName(hit.entryMethodSignature());
        for (ModuleDto module : hierarchy.getModules().values()) {
            for (SubModuleDto sm : module.getSubModules().values()) {
                for (FunctionDto fn : sm.getFunctions().values()) {
                    if (matchesEntryMethod(fn, hit.entryClassName(), entryMethodName, hit.entryMethodSignature())) {
                        moduleIds.add(module.getId());
                    }
                }
            }
        }
        if (moduleIds.isEmpty()) {
            moduleIds.addAll(mapEntryClassToModuleIds(hierarchy, hit.entryClassName()));
        }
        return moduleIds;
    }

    private static boolean matchesEntryMethod(FunctionDto fn, String entryClassName,
                                              String entryMethodName, String entryMethodSignature) {
        if (fn.getClassPaths() == null || !fn.getClassPaths().contains(entryClassName)) {
            return false;
        }
        if (fn.getMethodSignatures() == null || fn.getMethodSignatures().isEmpty()) {
            return true;
        }
        for (String sig : fn.getMethodSignatures()) {
            if (!StringUtils.hasText(sig)) {
                continue;
            }
            if (sig.equals(entryMethodSignature) || sig.startsWith(entryMethodName + "(")) {
                return true;
            }
            if (entryMethodSignature != null && entryMethodSignature.contains("#")
                    && entryMethodSignature.endsWith(sig)) {
                return true;
            }
        }
        return false;
    }

    private static boolean moduleContainsClass(ModuleDto module, String className) {
        for (SubModuleDto sm : module.getSubModules().values()) {
            for (FunctionDto fn : sm.getFunctions().values()) {
                if (fn.getClassPaths() != null && fn.getClassPaths().contains(className)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String extractMethodName(String signature) {
        if (!StringUtils.hasText(signature)) {
            return signature;
        }
        String methodPart = signature;
        int hash = signature.indexOf('#');
        if (hash >= 0 && hash < signature.length() - 1) {
            methodPart = signature.substring(hash + 1);
        }
        int paren = methodPart.indexOf('(');
        return paren >= 0 ? methodPart.substring(0, paren) : methodPart;
    }

    public static String simpleClassName(String fqcn) {
        if (!StringUtils.hasText(fqcn)) {
            return fqcn;
        }
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }
}
