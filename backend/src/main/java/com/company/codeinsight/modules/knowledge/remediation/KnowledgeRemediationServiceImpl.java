package com.company.codeinsight.modules.knowledge.remediation;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.entrypoint.model.ExcludeTarget;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.service.ModuleHierarchyService;
import com.company.codeinsight.modules.knowledge.browse.ActiveKnowledgeContext;
import com.company.codeinsight.modules.knowledge.browse.RepositoryActiveKnowledgeResolver;
import com.company.codeinsight.modules.knowledge.remediation.dto.DocumentRemediationRequest;
import com.company.codeinsight.modules.knowledge.remediation.dto.EntrypointRemediationRequest;
import com.company.codeinsight.modules.knowledge.remediation.dto.HierarchyRemediationRequest;
import com.company.codeinsight.modules.knowledge.remediation.dto.RemediationTaskResponse;
import com.company.codeinsight.modules.prompt.service.DecompilePromptService;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.repository.publish.service.RepositoryPublishedHierarchyLoader;
import com.company.codeinsight.modules.repository.service.RepoGitConnectivityService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRemediationServiceImpl implements KnowledgeRemediationService {

    private final RepositoryActiveKnowledgeResolver activeKnowledgeResolver;
    private final CodeRepositoryMapper repositoryMapper;
    private final DecompileTaskService decompileTaskService;
    private final TaskArtifactCloneService artifactCloneService;
    private final com.company.codeinsight.modules.entrypoint.service.EntrypointReviewService entrypointReviewService;
    private final ModuleHierarchyService moduleHierarchyService;
    private final RepositoryPublishedHierarchyLoader hierarchyLoader;
    private final DecompilePromptService decompilePromptService;
    private final RepoGitConnectivityService repoGitConnectivityService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RemediationTaskResponse remediateEntrypoints(EntrypointRemediationRequest request) {
        ActiveKnowledgeContext ctx = requireContext(request.getRepositoryId(), request.getSystemId());
        Long baseTaskId = requireBaseTask(ctx);

        DecompileTask task = createRemediationShell(ctx, baseTaskId, KnowledgeRemediationConstants.KIND_ENTRYPOINT,
                KnowledgeRemediationConstants.RESUME_AI_ANALYZING, null);
        artifactCloneService.cloneTaskArtifacts(baseTaskId, task.getId(), ctx.getRepositoryId());
        artifactCloneService.seedEntrypointsFromRepository(ctx.getRepositoryId(), task.getId(), ctx.getSystemId());

        List<ExcludeTarget> excludes = request.getExcludeTargets();
        if (excludes != null && !excludes.isEmpty()) {
            entrypointReviewService.applyReviewExcludes(task.getId(), excludes);
        }

        task.setRequireEntrypointReview(false);
        task.setRequireHierarchyReview(Boolean.TRUE);
        decompileTaskService.updateById(task);
        decompileTaskService.startTask(task.getId());

        return toResponse(task);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RemediationTaskResponse remediateHierarchy(HierarchyRemediationRequest request) {
        ActiveKnowledgeContext ctx = requireContext(request.getRepositoryId(), request.getSystemId());
        Long baseTaskId = requireBaseTask(ctx);
        if (request.getHierarchy() == null
                || request.getHierarchy().getModules() == null
                || request.getHierarchy().getModules().isEmpty()) {
            throw new BusinessException("请提交调整后的模块层级");
        }
        if (request.getModuleIds() == null || request.getModuleIds().isEmpty()) {
            throw new BusinessException("请指定需要重生成文档的模块范围 moduleIds");
        }

        String scopeJson = writeScope(request.getModuleIds());
        DecompileTask task = createRemediationShell(ctx, baseTaskId, KnowledgeRemediationConstants.KIND_HIERARCHY,
                KnowledgeRemediationConstants.RESUME_GENERATING_DOC, scopeJson);
        artifactCloneService.cloneTaskArtifacts(baseTaskId, task.getId(), ctx.getRepositoryId());
        artifactCloneService.seedEntrypointsFromRepository(ctx.getRepositoryId(), task.getId(), ctx.getSystemId());

        ModuleHierarchy hierarchy = request.getHierarchy();
        hierarchy.setTaskId(task.getId());
        hierarchy.setSystemId(ctx.getSystemId());
        moduleHierarchyService.replaceHierarchy(task.getId(), hierarchy);

        task.setRequireEntrypointReview(false);
        task.setRequireHierarchyReview(false);
        decompileTaskService.updateById(task);
        decompileTaskService.startTask(task.getId());
        return toResponse(task);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RemediationTaskResponse remediateDocuments(DocumentRemediationRequest request) {
        ActiveKnowledgeContext ctx = requireContext(request.getRepositoryId(), request.getSystemId());
        Long baseTaskId = requireBaseTask(ctx);
        if (request.getModuleIds() == null || request.getModuleIds().isEmpty()) {
            throw new BusinessException("请指定需要重跑的模块 moduleIds");
        }

        String scopeJson = writeScope(request.getModuleIds());
        DecompileTask task = createRemediationShell(ctx, baseTaskId, KnowledgeRemediationConstants.KIND_DOCUMENT,
                KnowledgeRemediationConstants.RESUME_GENERATING_DOC, scopeJson);
        artifactCloneService.cloneTaskArtifacts(baseTaskId, task.getId(), ctx.getRepositoryId());
        artifactCloneService.seedEntrypointsFromRepository(ctx.getRepositoryId(), task.getId(), ctx.getSystemId());

        ModuleHierarchy published = hierarchyLoader.loadByRepositoryId(ctx.getRepositoryId());
        published.setTaskId(task.getId());
        published.setSystemId(ctx.getSystemId());
        moduleHierarchyService.replaceHierarchy(task.getId(), published);

        task.setRequireEntrypointReview(false);
        task.setRequireHierarchyReview(false);
        decompileTaskService.updateById(task);
        decompileTaskService.startTask(task.getId());
        return toResponse(task);
    }

    private DecompileTask createRemediationShell(ActiveKnowledgeContext ctx, Long baseTaskId,
                                                   String kind, String resumeFrom, String scopeJson) {
        CodeRepository repo = repositoryMapper.selectById(ctx.getRepositoryId());
        if (repo == null) {
            throw new BusinessException("仓库不存在");
        }
        repoGitConnectivityService.assertReachableForTask(repo);
        DecompileTask baseTask = decompileTaskService.getById(baseTaskId);

        DecompileTask task = new DecompileTask();
        task.setSystemId(ctx.getSystemId());
        task.setRepositoryId(ctx.getRepositoryId());
        applyRemediationPromptIds(task, repo, baseTask);
        task.setModelName(baseTask != null ? baseTask.getModelName() : null);
        if (baseTask != null && StringUtils.hasText(baseTask.getSourceCommit())) {
            task.setSourceCommit(baseTask.getSourceCommit());
        } else if (StringUtils.hasText(repo.getLastCommitId())) {
            task.setSourceCommit(repo.getLastCommitId());
        }
        task.setStatus(TaskStatus.DRAFT.name());
        task.setType("INITIAL");
        task.setProgress(0);
        task.setTriggerSource(KnowledgeRemediationConstants.TRIGGER_SOURCE);
        task.setRemediationKind(kind);
        task.setBaseVersionId(ctx.getVersionId());
        task.setBaseTaskId(baseTaskId);
        task.setResumeFrom(resumeFrom);
        task.setRemediationScopeJson(scopeJson);
        task.setEntryScanConfig(repo.getEntryScanConfig());
        task.setPriority(70);
        decompileTaskService.save(task);
        return task;
    }

    /**
     * 纠错任务提示词继承顺序：来源任务（已跑通流水线）→ 仓库绑定。
     * 避免仓库未单独绑定时，无法基于已发布版本发起纠错。
     */
    private void applyRemediationPromptIds(DecompileTask task, CodeRepository repo, DecompileTask baseTask) {
        Long modularizeId = baseTask != null && baseTask.getModularizePromptId() != null
                ? baseTask.getModularizePromptId()
                : repo.getModularizePromptId();
        Long documentId = baseTask != null && baseTask.getDocumentPromptId() != null
                ? baseTask.getDocumentPromptId()
                : repo.getDocumentPromptId();
        task.setModularizePromptId(modularizeId);
        task.setDocumentPromptId(documentId);
        decompilePromptService.validateTaskPromptBinding(modularizeId, documentId);
    }

    private ActiveKnowledgeContext requireContext(Long repositoryId, Long systemId) {
        if (repositoryId == null) {
            throw new BusinessException("请选择仓库");
        }
        ActiveKnowledgeContext ctx = activeKnowledgeResolver.require(repositoryId);
        if (systemId != null && !systemId.equals(ctx.getSystemId())) {
            throw new BusinessException("所选系统与仓库不匹配");
        }
        return ctx;
    }

    private Long requireBaseTask(ActiveKnowledgeContext ctx) {
        if (ctx.getTaskId() == null) {
            throw new BusinessException("生效版本缺少来源任务，无法纠错");
        }
        return ctx.getTaskId();
    }

    private String writeScope(List<String> moduleIds) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            var arr = node.putArray("moduleIds");
            for (String id : moduleIds) {
                arr.add(id);
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new BusinessException("序列化纠错范围失败");
        }
    }

    private RemediationTaskResponse toResponse(DecompileTask task) {
        RemediationTaskResponse resp = new RemediationTaskResponse();
        resp.setTaskId(task.getId());
        resp.setRemediationKind(task.getRemediationKind());
        resp.setResumeFrom(task.getResumeFrom());
        return resp;
    }
}
