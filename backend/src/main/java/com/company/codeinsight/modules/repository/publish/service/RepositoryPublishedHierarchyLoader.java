package com.company.codeinsight.modules.repository.publish.service;

import com.company.codeinsight.modules.hierarchy.model.FunctionDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.model.SubModuleDto;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryModuleHierarchyNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 从 {@code ci_repository_module_hierarchy} 加载仓库维度的已发布模块层级。
 */
@Component
@RequiredArgsConstructor
public class RepositoryPublishedHierarchyLoader {

    private static final String LEVEL_MODULE = "MODULE";
    private static final String LEVEL_SUB_MODULE = "SUB_MODULE";
    private static final String LEVEL_FUNCTION = "FUNCTION";

    private final RepositoryArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public ModuleHierarchy loadByRepositoryId(Long repositoryId) {
        ModuleHierarchy hierarchy = new ModuleHierarchy();
        hierarchy.setTaskId(null);

        List<RepositoryModuleHierarchyNode> nodes = artifactService.listPublishedHierarchy(repositoryId);
        if (nodes.isEmpty()) {
            return hierarchy;
        }
        hierarchy.setSystemId(nodes.get(0).getSystemId());

        Map<Long, RepositoryModuleHierarchyNode> idToNode = new HashMap<>();
        for (RepositoryModuleHierarchyNode n : nodes) {
            idToNode.put(n.getId(), n);
        }

        for (RepositoryModuleHierarchyNode n : nodes) {
            if (LEVEL_MODULE.equals(n.getLevel())) {
                ModuleDto m = new ModuleDto();
                m.setId(n.getNodeId());
                m.setModuleName(n.getName());
                m.setKeywords(parseJsonArray(n.getKeywords()));
                m.setConfirmed(Boolean.TRUE.equals(n.getConfirmed()));
                hierarchy.getModules().put(m.getId(), m);
            } else if (LEVEL_SUB_MODULE.equals(n.getLevel()) && n.getParentId() != null) {
                RepositoryModuleHierarchyNode parent = idToNode.get(n.getParentId());
                if (parent != null && LEVEL_MODULE.equals(parent.getLevel())) {
                    ModuleDto m = hierarchy.getModules().get(parent.getNodeId());
                    if (m == null) {
                        continue;
                    }
                    SubModuleDto sm = new SubModuleDto();
                    sm.setId(n.getNodeId());
                    sm.setSubModuleName(n.getName());
                    sm.setKeywords(parseJsonArray(n.getKeywords()));
                    sm.setConfirmed(Boolean.TRUE.equals(n.getConfirmed()));
                    m.getSubModules().put(sm.getId(), sm);
                }
            } else if (LEVEL_FUNCTION.equals(n.getLevel()) && n.getParentId() != null) {
                RepositoryModuleHierarchyNode parent = idToNode.get(n.getParentId());
                if (parent != null && LEVEL_SUB_MODULE.equals(parent.getLevel())) {
                    RepositoryModuleHierarchyNode grand = idToNode.get(parent.getParentId());
                    if (grand == null || !LEVEL_MODULE.equals(grand.getLevel())) {
                        continue;
                    }
                    ModuleDto m = hierarchy.getModules().get(grand.getNodeId());
                    SubModuleDto sm = m == null ? null : m.getSubModules().get(parent.getNodeId());
                    if (m == null || sm == null) {
                        continue;
                    }
                    FunctionDto fn = new FunctionDto();
                    fn.setId(n.getNodeId());
                    fn.setFunctionName(n.getName());
                    fn.setClassPaths(new LinkedHashSet<>(parseJsonArray(n.getClassPaths())));
                    fn.setMethodSignatures(new LinkedHashSet<>(parseJsonArray(n.getMethodSignatures())));
                    fn.setConfirmed(Boolean.TRUE.equals(n.getConfirmed()));
                    sm.getFunctions().put(fn.getId(), fn);
                }
            }
        }
        return hierarchy;
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
