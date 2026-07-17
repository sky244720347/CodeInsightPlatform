package com.company.codeinsight.modules.repository.publish.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.common.util.DataUriUtil;
import com.company.codeinsight.modules.knowledge.browse.RepositoryActiveKnowledgeResolver;
import com.company.codeinsight.modules.entrypoint.entity.EntrypointEntity;
import com.company.codeinsight.modules.entrypoint.mapper.EntrypointMapper;
import com.company.codeinsight.modules.hierarchy.entity.ModuleHierarchyNode;
import com.company.codeinsight.modules.hierarchy.mapper.ModuleHierarchyNodeMapper;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.prompt.entity.DecompilePrompt;
import com.company.codeinsight.modules.prompt.mapper.DecompilePromptMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryEntrypointEntity;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryModuleHierarchyNode;
import com.company.codeinsight.modules.repository.publish.dto.RepositoryPublishSnapshotView;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryPublishSnapshot;
import com.company.codeinsight.modules.repository.publish.mapper.RepositoryEntrypointMapper;
import com.company.codeinsight.modules.repository.publish.mapper.RepositoryModuleHierarchyMapper;
import com.company.codeinsight.modules.repository.publish.mapper.RepositoryPublishSnapshotMapper;
import com.company.codeinsight.modules.repository.publish.service.RepositoryArtifactService;
import com.company.codeinsight.modules.repository.publish.service.RepositoryPublishService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepositoryPublishServiceImpl implements RepositoryPublishService, RepositoryArtifactService {

    private static final List<String> HIERARCHY_LEVEL_ORDER = List.of("MODULE", "SUB_MODULE", "FUNCTION");

    private final DecompileTaskMapper taskMapper;
    private final KnowledgeVersionMapper versionMapper;
    private final CodeRepositoryMapper repositoryMapper;
    private final EntrypointMapper entrypointMapper;
    private final ModuleHierarchyNodeMapper moduleHierarchyNodeMapper;
    private final RepositoryEntrypointMapper repositoryEntrypointMapper;
    private final RepositoryModuleHierarchyMapper repositoryModuleHierarchyMapper;
    private final RepositoryPublishSnapshotMapper snapshotMapper;
    private final DecompilePromptMapper promptMapper;
    private final ObjectMapper objectMapper;
    private final RepositoryActiveKnowledgeResolver activeKnowledgeResolver;
    private final EnvStorageResolver storageResolver;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RepositoryPublishSnapshot applyFromTask(Long taskId, Long versionId, String operator) {
        DecompileTask task = requireTask(taskId);
        KnowledgeVersion version = requireVersion(versionId);
        if (!taskId.equals(version.getTaskId())) {
            throw new BusinessException("版本与任务不匹配");
        }
        CodeRepository repo = requireRepository(task.getRepositoryId());

        List<EntrypointEntity> entrypoints = entrypointMapper.selectByTaskId(taskId);
        List<ModuleHierarchyNode> hierarchy = moduleHierarchyNodeMapper.selectList(
                new LambdaQueryWrapper<ModuleHierarchyNode>().eq(ModuleHierarchyNode::getTaskId, taskId));

        RepositoryPublishSnapshot snapshot = buildSnapshot(task, version, entrypoints, hierarchy, operator);
        RepositoryPublishSnapshot existing = snapshotMapper.selectByVersionId(versionId);
        if (existing != null) {
            log.warn("applyFromTask: versionId={} 快照已存在，幂等跳过 insert", versionId);
            applySnapshotToRepository(repo, existing, entrypoints, hierarchy, operator, version.getSourceCommit());
            return existing;
        }
        snapshotMapper.insert(snapshot);
        applySnapshotToRepository(repo, snapshot, entrypoints, hierarchy, operator, version.getSourceCommit());
        log.info("applyFromTask ok repoId={} versionId={} entrypoints={} hierarchy={}",
                repo.getId(), versionId, entrypoints.size(), hierarchy.size());
        return snapshot;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RepositoryPublishSnapshot rollbackToVersionByVersionId(Long versionId, String operator) {
        RepositoryPublishSnapshot snapshot = snapshotMapper.selectByVersionId(versionId);
        if (snapshot == null) {
            throw new BusinessException("未找到该版本的发布快照，无法回滚");
        }
        return rollbackToVersion(snapshot.getRepositoryId(), versionId, operator);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RepositoryPublishSnapshot rollbackToVersion(Long repositoryId, Long versionId, String operator) {
        CodeRepository repo = requireRepository(repositoryId);
        KnowledgeVersion version = requireVersion(versionId);
        if (versionId.equals(repo.getLastPublishedVersionId())) {
            throw new BusinessException("该版本已是当前生效版本，无需回滚");
        }
        if (!repositoryId.equals(version.getRepositoryId())) {
            throw new BusinessException("版本不属于该仓库");
        }
        if (!"PUSHED".equals(version.getStatus())) {
            throw new BusinessException("仅已推送（PUSHED）的版本可回滚仓库配置，当前: " + version.getStatus());
        }
        RepositoryPublishSnapshot snapshot = snapshotMapper.selectByVersionId(versionId);
        if (snapshot == null) {
            throw new BusinessException("未找到该版本的发布快照，无法回滚");
        }
        Path releaseDir = activeKnowledgeResolver.releaseDirForVersion(version);
        if (!Files.isDirectory(releaseDir)) {
            throw new BusinessException("该版本在存储上无发布产物目录，无法回滚: " + releaseDir);
        }

        List<EntrypointEntity> entrypoints = deserializeEntrypoints(loadSnapshotJson(snapshot.getEntrypointsUri()));
        List<ModuleHierarchyNode> hierarchy = deserializeHierarchy(loadSnapshotJson(snapshot.getModuleHierarchyUri()));
        applySnapshotToRepository(repo, snapshot, entrypoints, hierarchy, operator, version.getSourceCommit());
        log.info("rollbackToVersion ok repoId={} versionId={} baselineCommit={} operator={}",
                repositoryId, versionId, version.getSourceCommit(), operator);
        return snapshot;
    }

    @Override
    public List<RepositoryPublishSnapshotView> listSnapshots(Long repositoryId) {
        if (repositoryId == null) {
            return List.of();
        }
        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        Long activeVersionId = repo != null ? repo.getLastPublishedVersionId() : null;
        return snapshotMapper.selectByRepositoryId(repositoryId).stream()
                .map(snapshot -> toSnapshotView(snapshot, activeVersionId))
                .collect(Collectors.toList());
    }

    private RepositoryPublishSnapshotView toSnapshotView(RepositoryPublishSnapshot snapshot, Long activeVersionId) {
        RepositoryPublishSnapshotView view = new RepositoryPublishSnapshotView();
        view.setId(snapshot.getId());
        view.setRepositoryId(snapshot.getRepositoryId());
        view.setSystemId(snapshot.getSystemId());
        view.setTaskId(snapshot.getTaskId());
        view.setVersionId(snapshot.getVersionId());
        view.setVersionNum(snapshot.getVersionNum());
        view.setModularizePromptId(snapshot.getModularizePromptId());
        view.setDocumentPromptId(snapshot.getDocumentPromptId());
        view.setModelName(snapshot.getModelName());
        view.setPublishedAt(snapshot.getPublishedAt());
        view.setPublishedBy(snapshot.getPublishedBy());
        view.setActivePublished(activeVersionId != null && activeVersionId.equals(snapshot.getVersionId()));

        KnowledgeVersion version = versionMapper.selectById(snapshot.getVersionId());
        if (version != null) {
            Path releaseDir = activeKnowledgeResolver.releaseDirForVersion(version);
            view.setReleaseDirExists(Files.isDirectory(releaseDir));
        } else {
            view.setReleaseDirExists(false);
        }
        return view;
    }

    @Override
    public void exportArtifactsToRelease(Path releaseDir, RepositoryPublishSnapshot snapshot) {
        if (releaseDir == null || snapshot == null) {
            return;
        }
        try {
            Path artifactsDir = releaseDir.resolve("artifacts");
            Files.createDirectories(artifactsDir);

            if (StringUtils.hasText(snapshot.getEntryScanConfig())) {
                Files.writeString(artifactsDir.resolve("entry-scan-config.json"), snapshot.getEntryScanConfig());
            }
            String epJson = StringUtils.hasText(snapshot.getEntrypointsJson())
                    ? snapshot.getEntrypointsJson() : loadSnapshotJson(snapshot.getEntrypointsUri());
            String hierJson = StringUtils.hasText(snapshot.getModuleHierarchyJson())
                    ? snapshot.getModuleHierarchyJson() : loadSnapshotJson(snapshot.getModuleHierarchyUri());
            Files.writeString(artifactsDir.resolve("entrypoints.json"), epJson);
            Files.writeString(artifactsDir.resolve("module-hierarchy.json"), hierJson);

            Map<String, Object> prompts = buildPromptsMeta(snapshot);
            Files.writeString(artifactsDir.resolve("prompts.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(prompts));

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("versionId", snapshot.getVersionId());
            manifest.put("versionNum", snapshot.getVersionNum());
            manifest.put("repositoryId", snapshot.getRepositoryId());
            manifest.put("taskId", snapshot.getTaskId());
            manifest.put("publishedAt", snapshot.getPublishedAt() != null ? snapshot.getPublishedAt().toString() : null);
            manifest.put("publishedBy", snapshot.getPublishedBy());
            manifest.put("syncScanConfig", true);
            manifest.put("syncPrompts", true);
            manifest.put("syncEntrypoints", true);
            manifest.put("syncHierarchy", true);
            Files.writeString(artifactsDir.resolve("publish-manifest.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        } catch (Exception e) {
            throw new BusinessException("导出发布 artifacts 失败: " + e.getMessage());
        }
    }

    @Override
    public Optional<String> getPublishedScanConfig(Long repositoryId) {
        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        if (repo == null || !StringUtils.hasText(repo.getEntryScanConfig())) {
            return Optional.empty();
        }
        return Optional.of(repo.getEntryScanConfig());
    }

    @Override
    public List<RepositoryEntrypointEntity> listPublishedEntrypoints(Long repositoryId) {
        if (repositoryId == null) {
            return List.of();
        }
        return repositoryEntrypointMapper.selectByRepositoryId(repositoryId);
    }

    @Override
    public List<RepositoryModuleHierarchyNode> listPublishedHierarchy(Long repositoryId) {
        if (repositoryId == null) {
            return List.of();
        }
        return repositoryModuleHierarchyMapper.selectByRepositoryId(repositoryId);
    }

    private void applySnapshotToRepository(CodeRepository repo,
                                           RepositoryPublishSnapshot snapshot,
                                           List<EntrypointEntity> entrypoints,
                                           List<ModuleHierarchyNode> hierarchy,
                                           String operator,
                                           String publishedSourceCommit) {
        repo.setEntryScanConfig(snapshot.getEntryScanConfig());
        repo.setModularizePromptId(snapshot.getModularizePromptId());
        repo.setDocumentPromptId(snapshot.getDocumentPromptId());
        repo.setLastPublishedTaskId(snapshot.getTaskId());
        repo.setLastPublishedVersionId(snapshot.getVersionId());
        if (StringUtils.hasText(publishedSourceCommit)) {
            repo.setLastCommitId(publishedSourceCommit);
        }
        repo.setPublishedAt(LocalDateTime.now());
        repo.setPublishedBy(operator);
        repositoryMapper.updateById(repo);

        replaceRepositoryEntrypoints(repo.getId(), repo.getSystemId(), entrypoints);
        replaceRepositoryHierarchy(repo.getId(), repo.getSystemId(), hierarchy);
    }

    private RepositoryPublishSnapshot buildSnapshot(DecompileTask task,
                                                    KnowledgeVersion version,
                                                    List<EntrypointEntity> entrypoints,
                                                    List<ModuleHierarchyNode> hierarchy,
                                                    String operator) {
        RepositoryPublishSnapshot snapshot = new RepositoryPublishSnapshot();
        snapshot.setRepositoryId(task.getRepositoryId());
        snapshot.setSystemId(task.getSystemId());
        snapshot.setTaskId(task.getId());
        snapshot.setVersionId(version.getId());
        snapshot.setVersionNum(version.getVersionNum());
        snapshot.setEntryScanConfig(task.getEntryScanConfig());
        snapshot.setModularizePromptId(task.getModularizePromptId());
        snapshot.setDocumentPromptId(task.getDocumentPromptId());
        snapshot.setModelName(task.getModelName());
        String epUri = DataUriUtil.buildSnapshotEntrypointsUri(task.getRepositoryId(), version.getId());
        String hierUri = DataUriUtil.buildSnapshotModuleHierarchyUri(task.getRepositoryId(), version.getId());
        String epJson = serializeEntrypoints(entrypoints);
        String hierJson = serializeHierarchy(hierarchy);
        DataUriUtil.writeUtf8(epUri, epJson, storageResolver);
        DataUriUtil.writeUtf8(hierUri, hierJson, storageResolver);
        snapshot.setEntrypointsUri(epUri);
        snapshot.setModuleHierarchyUri(hierUri);
        snapshot.setEntrypointsJson(epJson);
        snapshot.setModuleHierarchyJson(hierJson);
        snapshot.setPublishedAt(LocalDateTime.now());
        snapshot.setPublishedBy(operator);
        return snapshot;
    }

    private String loadSnapshotJson(String uri) {
        if (!StringUtils.hasText(uri)) {
            return "[]";
        }
        String body = DataUriUtil.readUtf8(uri, storageResolver);
        return StringUtils.hasText(body) ? body : "[]";
    }

    private void replaceRepositoryEntrypoints(Long repositoryId, Long systemId, List<EntrypointEntity> entrypoints) {
        repositoryEntrypointMapper.deleteByRepositoryId(repositoryId);
        if (entrypoints == null || entrypoints.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (EntrypointEntity src : entrypoints) {
            RepositoryEntrypointEntity row = new RepositoryEntrypointEntity();
            row.setRepositoryId(repositoryId);
            row.setSystemId(systemId);
            row.setClassName(src.getClassName());
            row.setFilePath(src.getFilePath());
            row.setEntryType(src.getEntryType());
            row.setAnnotation(src.getAnnotation());
            row.setRemark(src.getRemark());
            row.setMethodsJson(src.getMethodsJson());
            row.setSortOrder(src.getSortOrder() != null ? src.getSortOrder() : 0);
            row.setCreatedDate(now);
            row.setUpdatedDate(now);
            repositoryEntrypointMapper.insert(row);
        }
    }

    private void replaceRepositoryHierarchy(Long repositoryId, Long systemId, List<ModuleHierarchyNode> nodes) {
        repositoryModuleHierarchyMapper.deleteByRepositoryId(repositoryId);
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        List<ModuleHierarchyNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingInt(n -> levelOrder(n.getLevel())));

        Map<Long, Long> idMap = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (ModuleHierarchyNode src : sorted) {
            RepositoryModuleHierarchyNode row = new RepositoryModuleHierarchyNode();
            row.setRepositoryId(repositoryId);
            row.setSystemId(systemId);
            row.setLevel(src.getLevel());
            row.setNodeId(src.getNodeId());
            row.setName(src.getName());
            row.setKeywords(src.getKeywords());
            row.setClassPaths(src.getClassPaths());
            row.setMethodSignatures(src.getMethodSignatures());
            row.setConfirmed(Boolean.TRUE.equals(src.getConfirmed()));
            if (src.getParentId() != null) {
                Long mappedParent = idMap.get(src.getParentId());
                if (mappedParent == null) {
                    throw new BusinessException("模块层级 parent 映射失败: " + src.getNodeId());
                }
                row.setParentId(mappedParent);
            }
            row.setCreatedDate(now);
            row.setUpdatedDate(now);
            repositoryModuleHierarchyMapper.insert(row);
            Long newId = row.getId();
            if (newId == null) {
                throw new BusinessException("模块层级写入后无法解析 id: " + src.getNodeId());
            }
            idMap.put(src.getId(), newId);
        }
    }

    private Map<String, Object> buildPromptsMeta(RepositoryPublishSnapshot snapshot) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modularizePromptId", snapshot.getModularizePromptId());
        out.put("documentPromptId", snapshot.getDocumentPromptId());
        out.put("modelName", snapshot.getModelName());
        if (snapshot.getModularizePromptId() != null) {
            DecompilePrompt p = promptMapper.selectById(snapshot.getModularizePromptId());
            if (p != null) {
                out.put("modularizePromptName", p.getName());
                out.put("modularizePromptVersion", p.getVersion());
            }
        }
        if (snapshot.getDocumentPromptId() != null) {
            DecompilePrompt p = promptMapper.selectById(snapshot.getDocumentPromptId());
            if (p != null) {
                out.put("documentPromptName", p.getName());
                out.put("documentPromptVersion", p.getVersion());
            }
        }
        return out;
    }

    private String serializeEntrypoints(List<EntrypointEntity> entrypoints) {
        try {
            return objectMapper.writeValueAsString(entrypoints != null ? entrypoints : List.of());
        } catch (Exception e) {
            throw new BusinessException("序列化入口快照失败");
        }
    }

    private String serializeHierarchy(List<ModuleHierarchyNode> hierarchy) {
        try {
            return objectMapper.writeValueAsString(hierarchy != null ? hierarchy : List.of());
        } catch (Exception e) {
            throw new BusinessException("序列化模块层级快照失败");
        }
    }

    private List<EntrypointEntity> deserializeEntrypoints(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<EntrypointEntity>>() {});
        } catch (Exception e) {
            throw new BusinessException("反序列化入口快照失败");
        }
    }

    private List<ModuleHierarchyNode> deserializeHierarchy(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ModuleHierarchyNode>>() {});
        } catch (Exception e) {
            throw new BusinessException("反序列化模块层级快照失败");
        }
    }

    private int levelOrder(String level) {
        int idx = HIERARCHY_LEVEL_ORDER.indexOf(level);
        return idx >= 0 ? idx : HIERARCHY_LEVEL_ORDER.size();
    }

    private DecompileTask requireTask(Long taskId) {
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        return task;
    }

    private KnowledgeVersion requireVersion(Long versionId) {
        KnowledgeVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("知识版本不存在");
        }
        return version;
    }

    private CodeRepository requireRepository(Long repositoryId) {
        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        if (repo == null) {
            throw new BusinessException("仓库不存在");
        }
        return repo;
    }
}
