package com.company.codeinsight.modules.callchain.support;

import com.company.codeinsight.modules.callchain.model.EntryMethodHit;
import com.company.codeinsight.modules.hierarchy.model.FunctionDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.model.SubModuleDto;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 增量影响分析共享工具：模块命中判定、入口→模块映射。
 */
public final class IncrementalImpactSupport {

    private IncrementalImpactSupport() {
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
