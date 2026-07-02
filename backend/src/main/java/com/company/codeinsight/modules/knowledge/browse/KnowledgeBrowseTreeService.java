package com.company.codeinsight.modules.knowledge.browse;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.hierarchy.model.FunctionDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.model.SubModuleDto;
import com.company.codeinsight.modules.knowledge.browse.dto.KnowledgeBrowseTreeNode;
import com.company.codeinsight.modules.knowledge.browse.dto.KnowledgeBrowseTreeQuery;
import com.company.codeinsight.modules.knowledge.browse.dto.KnowledgeBrowseTreeResult;
import com.company.codeinsight.modules.knowledge.browse.ReleaseKnowledgeBrowseHelper.ReleaseDocumentIndex;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.repository.publish.service.RepositoryPublishedHierarchyLoader;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.mapper.SystemApplicationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 知识查看树形模式：按仓库已发布模块层级展示功能叶子，并关联 NAS releases 下的知识文档。
 */
@Slf4j
@Service
public class KnowledgeBrowseTreeService {

    @Autowired
    private RepositoryActiveKnowledgeResolver activeKnowledgeResolver;

    @Autowired
    private RepositoryPublishedHierarchyLoader publishedHierarchyLoader;

    @Autowired
    private ReleaseKnowledgeBrowseHelper releaseBrowseHelper;

    @Autowired
    private SystemApplicationMapper systemMapper;

    @Autowired
    private CodeRepositoryMapper repositoryMapper;

    @Value("${code-insight.doc-generation.granularity:function}")
    private String docGenerationGranularity;

    public KnowledgeBrowseTreeResult buildTree(KnowledgeBrowseTreeQuery query) {
        if (query == null || query.getSystemId() == null || query.getRepositoryId() == null) {
            throw new BusinessException("树形模式需选择系统与仓库");
        }

        Long systemId = query.getSystemId();
        Long repositoryId = query.getRepositoryId();
        ActiveKnowledgeContext ctx = activeKnowledgeResolver.require(repositoryId);
        if (!systemId.equals(ctx.getSystemId())) {
            throw new BusinessException("所选系统与仓库不匹配");
        }

        KnowledgeBrowseTreeResult result = new KnowledgeBrowseTreeResult();
        result.setSystemId(systemId);
        result.setRepositoryId(repositoryId);
        result.setVersionId(ctx.getVersionId());
        result.setVersionNum(ctx.getVersionNum());
        result.setActiveVersion(true);
        result.setTaskId(ctx.getTaskId());
        result.setDocumentGranularity(docGenerationGranularity.toLowerCase(Locale.ROOT));

        SystemApplication system = systemMapper.selectById(systemId);
        result.setSystemName(formatSystemName(system));

        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        result.setRepositoryName(formatRepositoryName(repo));

        ModuleHierarchy hierarchy = publishedHierarchyLoader.loadByRepositoryId(repositoryId);
        if (hierarchy.getModules() == null || hierarchy.getModules().isEmpty()) {
            result.setNodes(new ArrayList<>());
            return result;
        }

        ReleaseDocumentIndex docIndex = releaseBrowseHelper.indexModuleDocuments(ctx);
        boolean functionGranularity = "function".equalsIgnoreCase(docGenerationGranularity);

        List<KnowledgeBrowseTreeNode> roots = new ArrayList<>();
        for (ModuleDto module : hierarchy.getModules().values()) {
            KnowledgeBrowseTreeNode moduleNode = new KnowledgeBrowseTreeNode();
            moduleNode.setKey("module:" + module.getId());
            moduleNode.setNodeType("MODULE");
            moduleNode.setTitle(module.getModuleName());

            String moduleDocPath = null;
            if (!functionGranularity) {
                moduleDocPath = resolvePublishedModuleDoc(module, docIndex);
            }

            List<KnowledgeBrowseTreeNode> subNodes = new ArrayList<>();
            for (SubModuleDto subModule : module.getSubModules().values()) {
                KnowledgeBrowseTreeNode subNode = new KnowledgeBrowseTreeNode();
                subNode.setKey("sub:" + subModule.getId());
                subNode.setNodeType("SUB_MODULE");
                subNode.setTitle(subModule.getSubModuleName());

                List<KnowledgeBrowseTreeNode> fnNodes = new ArrayList<>();
                for (FunctionDto fn : subModule.getFunctions().values()) {
                    fnNodes.add(buildFunctionNode(ctx, module, subModule, fn,
                            functionGranularity, moduleDocPath, docIndex));
                }
                subNode.setChildren(fnNodes);
                subNodes.add(subNode);
            }
            moduleNode.setChildren(subNodes);
            roots.add(moduleNode);
        }
        result.setNodes(roots);
        return result;
    }

    private KnowledgeBrowseTreeNode buildFunctionNode(ActiveKnowledgeContext ctx,
                                                      ModuleDto module, SubModuleDto subModule,
                                                      FunctionDto fn, boolean functionGranularity,
                                                      String moduleDocPath,
                                                      ReleaseDocumentIndex docIndex) {
        KnowledgeBrowseTreeNode fnNode = new KnowledgeBrowseTreeNode();
        fnNode.setKey("fn:" + fn.getId());
        fnNode.setNodeType("FUNCTION");
        fnNode.setTitle(fn.getFunctionName());
        fnNode.setChildren(new ArrayList<>());

        String docPath;
        if (functionGranularity) {
            fnNode.setDocumentGranularity("function");
            docPath = resolvePublishedFunctionDoc(module, subModule, fn, docIndex);
        } else {
            fnNode.setDocumentGranularity("module");
            docPath = moduleDocPath;
        }

        if (StringUtils.hasText(docPath)) {
            fnNode.setHasDocument(true);
            fnNode.setDocumentPath(docPath);
            fnNode.setContentUri(releaseBrowseHelper.buildContentUri(ctx, docPath));
            fnNode.setDraftStatus("PUSHED");
        } else {
            fnNode.setHasDocument(false);
        }
        return fnNode;
    }

    private String resolvePublishedModuleDoc(ModuleDto module, ReleaseDocumentIndex docIndex) {
        String nested = "modules/" + safeSegment(module.getModuleName()) + ".md";
        if (docIndex.byRelativePath.containsKey(nested)) {
            return nested;
        }
        String flat = docIndex.byBasename.get(ReleaseKnowledgeBrowseHelper.normalize(safeSegment(module.getModuleName())));
        if (flat != null) {
            return flat;
        }
        return docIndex.byBasename.get(ReleaseKnowledgeBrowseHelper.normalize(module.getModuleName()));
    }

    private String resolvePublishedFunctionDoc(ModuleDto module, SubModuleDto subModule, FunctionDto fn,
                                               ReleaseDocumentIndex docIndex) {
        String nested = "modules/"
                + safeSegment(module.getModuleName()) + "/"
                + safeSegment(subModule.getSubModuleName()) + "/"
                + safeSegment(fn.getFunctionName()) + ".md";
        if (docIndex.byRelativePath.containsKey(nested)) {
            return nested;
        }
        return resolvePublishedModuleDoc(module, docIndex);
    }

    static String safeSegment(String raw) {
        if (raw == null) {
            return "_";
        }
        return raw.replaceAll("[\\s/\\(\\)]", "_");
    }

    static String formatSystemName(SystemApplication system) {
        if (system == null) {
            return null;
        }
        if (StringUtils.hasText(system.getNameCn())) {
            return system.getNameCn();
        }
        return system.getName();
    }

    static String formatRepositoryName(CodeRepository repo) {
        if (repo == null) {
            return null;
        }
        String url = repo.getGitUrl();
        String base = "仓库 #" + repo.getId();
        if (StringUtils.hasText(url)) {
            int idx = Math.max(url.lastIndexOf('/'), url.lastIndexOf('\\'));
            base = idx >= 0 ? url.substring(idx + 1) : url;
            if (base.endsWith(".git")) {
                base = base.substring(0, base.length() - 4);
            }
        }
        if (StringUtils.hasText(repo.getBranch())) {
            return base + " (" + repo.getBranch() + ")";
        }
        return base;
    }
}
