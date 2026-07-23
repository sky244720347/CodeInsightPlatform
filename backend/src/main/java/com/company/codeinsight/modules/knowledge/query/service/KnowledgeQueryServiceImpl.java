package com.company.codeinsight.modules.knowledge.query.service;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.entrypoint.model.EntrypointMethodView;
import com.company.codeinsight.modules.entrypoint.model.EntrypointReviewView;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.knowledge.browse.ActiveKnowledgeContext;
import com.company.codeinsight.modules.knowledge.browse.RepositoryActiveKnowledgeResolver;
import com.company.codeinsight.modules.knowledge.query.KnowledgeQueryService;
import com.company.codeinsight.modules.knowledge.query.dto.KnowledgeContextView;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryEntrypointEntity;
import com.company.codeinsight.modules.repository.publish.service.RepositoryArtifactService;
import com.company.codeinsight.modules.repository.publish.service.RepositoryPublishedHierarchyLoader;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.mapper.SystemApplicationMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class KnowledgeQueryServiceImpl implements KnowledgeQueryService {

    private final RepositoryActiveKnowledgeResolver activeKnowledgeResolver;
    private final RepositoryArtifactService artifactService;
    private final RepositoryPublishedHierarchyLoader hierarchyLoader;
    private final CodeRepositoryMapper repositoryMapper;
    private final SystemApplicationMapper systemMapper;
    private final ObjectMapper objectMapper;

    @Override
    public KnowledgeContextView getContext(Long repositoryId) {
        if (repositoryId == null) {
            throw new BusinessException("请选择仓库");
        }
        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        if (repo == null) {
            throw new BusinessException("仓库不存在");
        }

        KnowledgeContextView view = new KnowledgeContextView();
        view.setRepositoryId(repositoryId);
        view.setRepositoryName(formatRepositoryName(repo));
        view.setSystemId(repo.getSystemId());

        SystemApplication system = systemMapper.selectById(repo.getSystemId());
        view.setSystemName(formatSystemName(system));
        view.setComponent(system != null ? system.getComponent() : null);

        Optional<ActiveKnowledgeContext> active = activeKnowledgeResolver.resolve(repositoryId);
        if (active.isPresent()) {
            ActiveKnowledgeContext ctx = active.get();
            view.setVersionId(ctx.getVersionId());
            view.setVersionNum(ctx.getVersionNum());
            view.setTaskId(ctx.getTaskId());
            view.setReleaseDirExists(ctx.isReleaseDirExists());
        } else {
            view.setReleaseDirExists(false);
        }

        List<RepositoryEntrypointEntity> entrypoints = artifactService.listPublishedEntrypoints(repositoryId);
        view.setHasPublishedEntrypoints(!entrypoints.isEmpty());
        view.setHasPublishedHierarchy(!artifactService.listPublishedHierarchy(repositoryId).isEmpty());
        return view;
    }

    @Override
    public List<EntrypointReviewView> listPublishedEntrypoints(Long systemId, Long repositoryId) {
        ActiveKnowledgeContext ctx = requireActive(repositoryId, systemId);
        List<RepositoryEntrypointEntity> rows = artifactService.listPublishedEntrypoints(repositoryId);
        List<EntrypointReviewView> views = new ArrayList<>(rows.size());
        for (RepositoryEntrypointEntity row : rows) {
            EntrypointReviewView view = new EntrypointReviewView();
            view.setId(row.getId());
            view.setTaskId(ctx.getTaskId());
            view.setSystemId(row.getSystemId());
            view.setClassName(row.getClassName());
            view.setFilePath(row.getFilePath());
            view.setEntryType(row.getEntryType());
            view.setAnnotation(row.getAnnotation());
            view.setRemark(row.getRemark());
            view.setEnabled(Boolean.TRUE);
            view.setSortOrder(row.getSortOrder());
            view.setMethods(deserializeMethods(row.getMethodsJson()));
            views.add(view);
        }
        return views;
    }

    @Override
    public ModuleHierarchy getPublishedHierarchy(Long systemId, Long repositoryId) {
        requireActive(repositoryId, systemId);
        ModuleHierarchy hierarchy = hierarchyLoader.loadByRepositoryId(repositoryId);
        Optional<ActiveKnowledgeContext> active = activeKnowledgeResolver.resolve(repositoryId);
        active.ifPresent(ctx -> hierarchy.setTaskId(ctx.getTaskId()));
        return hierarchy;
    }

    private ActiveKnowledgeContext requireActive(Long repositoryId, Long systemId) {
        ActiveKnowledgeContext ctx = activeKnowledgeResolver.require(repositoryId);
        if (systemId != null && !systemId.equals(ctx.getSystemId())) {
            throw new BusinessException("所选系统与仓库不匹配");
        }
        return ctx;
    }

    private List<EntrypointMethodView> deserializeMethods(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<EntrypointMethodView>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String formatSystemName(SystemApplication system) {
        if (system == null) {
            return null;
        }
        if (StringUtils.hasText(system.getNameCn())) {
            return system.getNameCn();
        }
        return system.getName();
    }

    private String formatRepositoryName(CodeRepository repo) {
        if (repo == null) {
            return null;
        }
        String gitUrl = repo.getGitUrl();
        if (StringUtils.hasText(gitUrl)) {
            String tail = gitUrl.replaceAll("/$", "");
            int slash = tail.lastIndexOf('/');
            String name = slash >= 0 ? tail.substring(slash + 1) : tail;
            return name.replaceAll("\\.git$", "") + " (" + repo.getBranch() + ")";
        }
        return "仓库 #" + repo.getId();
    }
}
