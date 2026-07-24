package com.company.codeinsight.modules.knowledge.service.impl;

import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.hierarchy.model.FunctionDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.model.SubModuleDto;
import com.company.codeinsight.modules.knowledge.service.KnowledgeIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 三级索引生成实现
 * <ul>
 *   <li>{@code module-index.md}：模块结构 + 入口类 + md 链接</li>
 *   <li>{@code meta/document-index.md}：模块→子模块→功能 → ZIP 相对路径（扁平下划线文件名）</li>
 * </ul>
 */
@Slf4j
@Service
public class KnowledgeIndexServiceImpl implements KnowledgeIndexService {

    /** 与 KnowledgeServiceImpl.createVersion / NasPushStrategy 落盘规则一致 */
    public static String flattenKnowledgeDocFileName(String breadcrumb) {
        if (breadcrumb == null) {
            return "_.md";
        }
        return breadcrumb.replaceAll("[\\s/\\(\\)]", "_") + ".md";
    }

    /** ZIP 内相对 code-insight/ 的路径：modules/{扁平文件名} */
    public static String toZipRelativeDocPath(String breadcrumb) {
        return "modules/" + flattenKnowledgeDocFileName(breadcrumb);
    }

    public static String functionBreadcrumb(String moduleName, String subName, String fnName) {
        return moduleName + " / " + subName + " / " + fnName;
    }

    @Override
    public Path generateModuleIndex(Path docsPath, ModuleHierarchy hierarchy, List<KnowledgeDraft> drafts) throws IOException {
        // 建立 moduleName → md 文件相对路径索引（按 createVersion 扁平规则）
        Map<String, String> moduleNameToMdRelPath = new HashMap<>();
        for (KnowledgeDraft draft : drafts) {
            if (StringUtils.hasText(draft.getModuleName())) {
                moduleNameToMdRelPath.put(draft.getModuleName(), toZipRelativeDocPath(draft.getModuleName()));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 模块知识归纳索引\n\n");
        sb.append("本知识库由代码洞察平台基于大模型及静态解析自动归纳生成。\n\n");
        sb.append("## 系统模块列表\n\n");

        if (hierarchy == null || hierarchy.getModules() == null || hierarchy.getModules().isEmpty()) {
            appendFallbackList(sb, moduleNameToMdRelPath);
        } else {
            appendThreeLevelTable(sb, hierarchy, moduleNameToMdRelPath);
        }

        sb.append("\n## 文档导览\n");
        sb.append("- [架构概览](architecture-overview.md)\n");
        sb.append("- [知识文档索引](meta/document-index.md)\n");
        sb.append("- [接口索引](api-index.md)\n");
        sb.append("- [数据库索引](database-index.md)\n");
        sb.append("- [依赖与调用链路](dependency-index.md)\n");
        sb.append("- [待确认事项清单](pending-confirmation.md)\n");

        Path indexPath = docsPath.resolve("module-index.md");
        Files.writeString(indexPath, sb.toString());
        log.info("KnowledgeIndexService generated: {}", indexPath);
        return indexPath;
    }

    @Override
    public Path generateDocumentIndex(Path docsPath, ModuleHierarchy hierarchy, List<KnowledgeDraft> drafts) throws IOException {
        Path metaPath = docsPath.resolve("meta");
        Files.createDirectories(metaPath);

        StringBuilder sb = new StringBuilder();
        sb.append("# 知识文档路径索引\n\n");
        sb.append("本文件列出模块 → 子模块 → 功能对应的知识文档路径，");
        sb.append("路径相对 ZIP 包内 `code-insight/` 根目录，与导出文件名一致（扁平下划线命名）。\n\n");

        if (hierarchy == null || hierarchy.getModules() == null || hierarchy.getModules().isEmpty()) {
            appendDocumentIndexFallback(sb, drafts);
        } else {
            appendDocumentIndexTable(sb, hierarchy);
        }

        Path indexPath = metaPath.resolve("document-index.md");
        Files.writeString(indexPath, sb.toString());
        log.info("KnowledgeIndexService generated document-index: {}", indexPath);
        return indexPath;
    }

    /**
     * 三级表格：模块 → 子模块 → 功能 → md 链接
     */
    private void appendThreeLevelTable(StringBuilder sb,
                                       ModuleHierarchy hierarchy,
                                       Map<String, String> moduleNameToMdRelPath) {
        sb.append("| 模块 | 子模块 | 功能 | 入口类 | md 链接 |\n");
        sb.append("| --- | --- | --- | --- | --- |\n");

        for (ModuleDto moduleDto : hierarchy.getModules().values()) {
            String moduleName = moduleDto.getModuleName();
            String mdRelPath = moduleNameToMdRelPath.getOrDefault(moduleName, null);

            if (moduleDto.getSubModules() == null || moduleDto.getSubModules().isEmpty()) {
                sb.append(buildTableRow(moduleName, "—", "—", "—", mdRelPath));
                continue;
            }

            for (SubModuleDto subModuleDto : moduleDto.getSubModules().values()) {
                String subName = subModuleDto.getSubModuleName();
                int functionCount = subModuleDto.getFunctions() == null ? 0 : subModuleDto.getFunctions().size();

                if (functionCount == 0) {
                    String breadcrumb = moduleName + " / " + subName;
                    String path = moduleNameToMdRelPath.getOrDefault(breadcrumb, toZipRelativeDocPath(breadcrumb));
                    sb.append(buildTableRow(moduleName, subName, "—", "—", path));
                    continue;
                }

                for (FunctionDto functionDto : subModuleDto.getFunctions().values()) {
                    String fnName = functionDto.getFunctionName();
                    String entries = joinClassPaths(functionDto.getClassPaths());
                    String breadcrumb = functionBreadcrumb(moduleName, subName, fnName);
                    String path = moduleNameToMdRelPath.getOrDefault(breadcrumb, toZipRelativeDocPath(breadcrumb));
                    sb.append(buildTableRow(moduleName, subName, fnName, entries, path));
                }
            }
        }
    }

    private void appendDocumentIndexTable(StringBuilder sb, ModuleHierarchy hierarchy) {
        sb.append("| 模块 | 子模块 | 功能 | 文档路径 |\n");
        sb.append("| --- | --- | --- | --- |\n");

        for (ModuleDto moduleDto : hierarchy.getModules().values()) {
            String moduleName = moduleDto.getModuleName();

            if (moduleDto.getSubModules() == null || moduleDto.getSubModules().isEmpty()) {
                sb.append(buildDocumentIndexRow(moduleName, "—", "—", toZipRelativeDocPath(moduleName)));
                continue;
            }

            for (SubModuleDto subModuleDto : moduleDto.getSubModules().values()) {
                String subName = subModuleDto.getSubModuleName();
                if (subModuleDto.getFunctions() == null || subModuleDto.getFunctions().isEmpty()) {
                    String breadcrumb = moduleName + " / " + subName;
                    sb.append(buildDocumentIndexRow(moduleName, subName, "—", toZipRelativeDocPath(breadcrumb)));
                    continue;
                }
                for (FunctionDto functionDto : subModuleDto.getFunctions().values()) {
                    String fnName = functionDto.getFunctionName();
                    String breadcrumb = functionBreadcrumb(moduleName, subName, fnName);
                    sb.append(buildDocumentIndexRow(moduleName, subName, fnName, toZipRelativeDocPath(breadcrumb)));
                }
            }
        }
    }

    private void appendDocumentIndexFallback(StringBuilder sb, List<KnowledgeDraft> drafts) {
        sb.append("| 模块（面包屑） | 文档路径 |\n");
        sb.append("| --- | --- |\n");
        if (drafts == null || drafts.isEmpty()) {
            sb.append("| — | — |\n");
            return;
        }
        Set<String> sorted = new TreeSet<>();
        Map<String, String> nameToPath = new HashMap<>();
        for (KnowledgeDraft draft : drafts) {
            if (!StringUtils.hasText(draft.getModuleName())) {
                continue;
            }
            sorted.add(draft.getModuleName());
            nameToPath.put(draft.getModuleName(), toZipRelativeDocPath(draft.getModuleName()));
        }
        for (String name : sorted) {
            sb.append("| ").append(escapeMd(name)).append(" | `")
                    .append(nameToPath.get(name)).append("` |\n");
        }
    }

    /**
     * 降级模式：DTO 为空时只列 KnowledgeDraft 名称与 md 链接
     */
    private void appendFallbackList(StringBuilder sb, Map<String, String> moduleNameToMdRelPath) {
        sb.append("| 模块 | md 链接 |\n| --- | --- |\n");
        Set<String> sorted = new TreeSet<>(moduleNameToMdRelPath.keySet());
        for (String moduleName : sorted) {
            String rel = moduleNameToMdRelPath.get(moduleName);
            sb.append("| ").append(escapeMd(moduleName)).append(" | [").append(escapeMd(moduleName)).append("](")
                    .append(rel).append(") |\n");
        }
    }

    private String buildTableRow(String moduleName, String subName, String fnName,
                                 String entries, String mdRelPath) {
        String linkCell = mdRelPath == null
                ? "—"
                : "[" + escapeMd(moduleName) + "](" + mdRelPath + ")";
        return "| " + escapeMd(moduleName)
                + " | " + escapeMd(subName)
                + " | " + escapeMd(fnName)
                + " | " + escapeMd(entries)
                + " | " + linkCell + " |\n";
    }

    private String buildDocumentIndexRow(String moduleName, String subName, String fnName, String zipRelPath) {
        return "| " + escapeMd(moduleName)
                + " | " + escapeMd(subName)
                + " | " + escapeMd(fnName)
                + " | `" + zipRelPath + "` |\n";
    }

    private String joinClassPaths(Set<String> classPaths) {
        if (classPaths == null || classPaths.isEmpty()) {
            return "—";
        }
        return classPaths.stream().sorted().collect(Collectors.joining("<br>"));
    }

    /**
     * Markdown 表格内容最小转义：避免竖线 / 换行破坏表格结构
     */
    private String escapeMd(String value) {
        if (value == null) return "—";
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
