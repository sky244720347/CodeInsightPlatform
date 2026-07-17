package com.company.codeinsight.modules.draft.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.util.DraftFileUtil;
import com.company.codeinsight.modules.draft.dto.PreviewSystemDto;
import com.company.codeinsight.modules.draft.dto.RepositoryReadinessDto;
import com.company.codeinsight.modules.draft.entity.*;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.draft.mapper.*;
import com.company.codeinsight.modules.draft.service.DraftEditLockService;
import com.company.codeinsight.modules.draft.service.DraftService;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.mapper.SystemApplicationMapper;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.TaskStateMachineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 知识草稿及评审工作区服务实现类
 * 负责平台草稿库的修改保存、自动缓存（基于 Redis）、修订版本行级差异计算和确认归档。
 * 自 v0.3 起移除驳回相关流程；复核反馈通过直接编辑修改草稿实现。
 */
@Slf4j
@Service
public class DraftServiceImpl implements DraftService {

    @Autowired
    private DraftWorkspaceMapper workspaceMapper;

    @Autowired
    private KnowledgeDraftMapper draftMapper;

    @Autowired
    private DraftRevisionMapper revisionMapper;

    @Autowired
    private DraftReviewCommentMapper commentMapper;

    @Autowired
    private DraftSourceReferenceMapper referenceMapper;

    @Autowired
    private CodeRepositoryMapper repositoryMapper;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired
    private TaskStateMachineService stateMachineService;

    @Autowired
    private SystemApplicationMapper systemMapper;

    @Autowired
    private com.company.codeinsight.modules.prompt.service.DecompilePromptService decompilePromptService;

    @Autowired
    private ClusterProperties clusterProperties;

    @Autowired
    private DraftEditLockService draftEditLockService;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private com.company.codeinsight.common.storage.EnvStorageResolver storageResolver;

    @Autowired
    private com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper knowledgeVersionMapper;

    /**
     * 查询指定评审工作区下的所有草稿
     */
    @Override
    public List<KnowledgeDraft> listDraftsByWorkspace(Long workspaceId) {
        return draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>().eq(KnowledgeDraft::getWorkspaceId, workspaceId)
        );
    }

    /**
     * 查询指定工作区下的草稿目录树。基于 parent_id 自引用递归构建：
     * 1. 一次性拉取该工作区下所有草稿（按 sort_order, id 升序）
     * 2. 用 Map<id, node> 索引
     * 3. 遍历挂父子关系；最后只返回顶级节点
     */
    @Override
    public List<com.company.codeinsight.modules.draft.dto.DraftTreeNode> getWorkspaceTree(Long workspaceId) {
        // v1: INCREMENTAL 任务的 workspace 引用基线 workspace
        // 查询时合并基线草稿 + 本任务草稿（按 module_name 去重，本任务的优先）
        DraftWorkspace ws = workspaceMapper.selectById(workspaceId);
        List<Long> workspaceIds = new ArrayList<>();
        workspaceIds.add(workspaceId);
        if (ws != null && ws.getBaselineWorkspaceId() != null) {
            workspaceIds.add(ws.getBaselineWorkspaceId());
        }

        List<KnowledgeDraft> all = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .in(KnowledgeDraft::getWorkspaceId, workspaceIds)
                        .orderByAsc(KnowledgeDraft::getSortOrder)
                        .orderByAsc(KnowledgeDraft::getId)
        );
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        // v1: 按 module_name 去重（本任务草稿优先；同 module_name 的基线草稿被覆盖）
        Map<String, KnowledgeDraft> uniqueByModule = new java.util.LinkedHashMap<>();
        for (KnowledgeDraft d : all) {
            if (!StringUtils.hasText(d.getModuleName())) {
                continue;
            }
            // 优先本任务（id 大的优先）
            KnowledgeDraft existing = uniqueByModule.get(d.getModuleName());
            if (existing == null || d.getWorkspaceId().equals(workspaceId)) {
                uniqueByModule.put(d.getModuleName(), d);
            }
        }
        List<KnowledgeDraft> deduped = new ArrayList<>(uniqueByModule.values());
        if (deduped.isEmpty()) {
            return Collections.emptyList();
        }
        // v1: 把来自基线 workspace 的草稿的 baseline_task_id 标记为基线任务的 ID
        //    （否则 them 都是 NULL——因为基线任务是 INITIAL，没有 baseline_task_id 字段）
        if (ws != null && ws.getBaselineWorkspaceId() != null) {
            DraftWorkspace baselineWs = workspaceMapper.selectById(ws.getBaselineWorkspaceId());
            if (baselineWs != null && baselineWs.getTaskId() != null) {
                for (KnowledgeDraft d : deduped) {
                    if (d.getBaselineTaskId() == null
                            && d.getWorkspaceId().equals(ws.getBaselineWorkspaceId())) {
                        d.setBaselineTaskId(baselineWs.getTaskId());
                    }
                }
            }
        }
        // 1. 转成 DTO 并用 map 索引
        Map<Long, com.company.codeinsight.modules.draft.dto.DraftTreeNode> nodeMap = new java.util.HashMap<>(deduped.size());
        for (KnowledgeDraft d : deduped) {
            nodeMap.put(d.getId(), com.company.codeinsight.modules.draft.dto.DraftTreeNode.fromDraft(d));
        }
        // 2. 挂父子关系；被挂上子节点的父节点标记为 isFolder=true（前端用此判断"是否目录节点"）
        List<com.company.codeinsight.modules.draft.dto.DraftTreeNode> roots = new java.util.ArrayList<>();
        for (KnowledgeDraft d : deduped) {
            com.company.codeinsight.modules.draft.dto.DraftTreeNode node = nodeMap.get(d.getId());
            Long pid = d.getParentId();
            if (pid == null) {
                roots.add(node);
            } else {
                com.company.codeinsight.modules.draft.dto.DraftTreeNode parent = nodeMap.get(pid);
                if (parent != null) {
                    parent.setIsFolder(Boolean.TRUE);
                    parent.getChildren().add(node);
                } else {
                    // 父节点缺失（孤儿数据），降级为顶级
                    roots.add(node);
                }
            }
        }
        return roots;
    }

    @Override
    public com.company.codeinsight.modules.draft.dto.DraftTreeDiffDto getWorkspaceTreeDiff(Long workspaceId) {
        com.company.codeinsight.modules.draft.dto.DraftTreeDiffDto result =
                new com.company.codeinsight.modules.draft.dto.DraftTreeDiffDto();
        DraftWorkspace ws = workspaceMapper.selectById(workspaceId);
        if (ws == null) {
            return result;
        }

        // 本次 workspace 草稿（v2: BASELINE_DOC_INHERIT 后本次 workspace 已自包含）
        List<KnowledgeDraft> currentDrafts = draftMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeDraft>()
                        .eq(KnowledgeDraft::getWorkspaceId, workspaceId));
        java.util.Set<String> currentModuleNames = new java.util.HashSet<>();
        for (KnowledgeDraft d : currentDrafts) {
            if (d.getModuleName() != null) currentModuleNames.add(d.getModuleName());
        }

        // 基线模块名从 releases 目录的 module-map.yaml 读取（纯 release 方案）
        java.util.Set<String> baselineModuleNames = new java.util.HashSet<>();
        Long baselineTaskId = null;
        if (ws.getRepositoryId() != null) {
            com.company.codeinsight.modules.repository.entity.CodeRepository repo =
                    repositoryMapper.selectById(ws.getRepositoryId());
            if (repo != null && repo.getLastPublishedVersionId() != null) {
                com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion version =
                        knowledgeVersionMapper.selectById(repo.getLastPublishedVersionId());
                if (version != null && "PUSHED".equals(version.getStatus())) {
                    baselineTaskId = version.getTaskId();
                    java.nio.file.Path releaseDir = storageResolver.releaseDir(
                            version.getSystemId(), version.getRepositoryId(), version.getVersionNum());
                    java.nio.file.Path mapFile = releaseDir.resolve("meta").resolve("module-map.yaml");
                    if (java.nio.file.Files.exists(mapFile)) {
                        baselineModuleNames.addAll(parseModuleMapNames(mapFile));
                    }
                }
            }
        }

        // v2 分类（按 baselineTaskId 字段区分）：
        //   new       = baselineTaskId == null 且 module_name 不在基线中
        //   modified  = baselineTaskId == null 且 module_name 在基线中（AI 重生成覆盖）
        //   inherited = baselineTaskId != null（直接从基线 release 复制，未重跑）
        List<KnowledgeDraft> newDrafts = new java.util.ArrayList<>();
        List<KnowledgeDraft> modifiedDrafts = new java.util.ArrayList<>();
        List<KnowledgeDraft> inheritedDrafts = new java.util.ArrayList<>();
        for (KnowledgeDraft d : currentDrafts) {
            if (d.getModuleName() == null) continue;
            if (d.getBaselineTaskId() != null) {
                inheritedDrafts.add(d);
            } else if (baselineModuleNames.contains(d.getModuleName())) {
                modifiedDrafts.add(d);
            } else {
                newDrafts.add(d);
            }
        }

        //   deleted = 基线有 + 本次无（真正删除）— 从 release module-map.yaml 构造虚拟节点
        List<com.company.codeinsight.modules.draft.dto.DraftTreeNode> deletedNodes = new java.util.ArrayList<>();
        long syntheticId = -1;
        for (String name : baselineModuleNames) {
            if (!currentModuleNames.contains(name)) {
                com.company.codeinsight.modules.draft.dto.DraftTreeNode node =
                        new com.company.codeinsight.modules.draft.dto.DraftTreeNode();
                node.setId(syntheticId--);
                node.setModuleName(name);
                node.setBaselineTaskId(baselineTaskId);
                node.setIsFolder(false);
                node.setSortOrder(0);
                deletedNodes.add(node);
            }
        }

        result.setNewRows(buildTree(newDrafts));
        result.setModifiedRows(buildTree(modifiedDrafts));
        result.setInheritedRows(buildTree(inheritedDrafts));
        result.setDeletedRows(deletedNodes);
        log.info("DraftTreeDiff v2 — workspaceId={} repoId={} new={} modified={} inherited={} deleted={}",
                workspaceId, ws.getRepositoryId(),
                newDrafts.size(), modifiedDrafts.size(), inheritedDrafts.size(), deletedNodes.size());
        return result;
    }

    /** 解析 module-map.yaml，返回所有模块名 */
    private java.util.Set<String> parseModuleMapNames(java.nio.file.Path yamlFile) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        try {
            List<String> lines = java.nio.file.Files.readAllLines(yamlFile);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- name:")) {
                    names.add(parseYamlQuotedValue(trimmed.substring("- name:".length())));
                }
            }
        } catch (java.io.IOException e) {
            log.warn("解析 module-map.yaml 失败: {}", yamlFile, e);
        }
        return names;
    }

    /** 内部：从 KnowledgeDraft 列表构建树（按 parent_id 递归） */
    private List<com.company.codeinsight.modules.draft.dto.DraftTreeNode> buildTree(List<KnowledgeDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) return Collections.emptyList();
        java.util.Map<Long, com.company.codeinsight.modules.draft.dto.DraftTreeNode> nodeMap = new java.util.HashMap<>(drafts.size());
        for (KnowledgeDraft d : drafts) {
            nodeMap.put(d.getId(), com.company.codeinsight.modules.draft.dto.DraftTreeNode.fromDraft(d));
        }
        List<com.company.codeinsight.modules.draft.dto.DraftTreeNode> roots = new java.util.ArrayList<>();
        for (KnowledgeDraft d : drafts) {
            com.company.codeinsight.modules.draft.dto.DraftTreeNode node = nodeMap.get(d.getId());
            Long pid = d.getParentId();
            if (pid == null) {
                roots.add(node);
            } else {
                com.company.codeinsight.modules.draft.dto.DraftTreeNode parent = nodeMap.get(pid);
                if (parent != null) {
                    parent.setIsFolder(Boolean.TRUE);
                    parent.getChildren().add(node);
                } else {
                    roots.add(node);
                }
            }
        }
        return roots;
    }

    /**
     * 获取指定任务的评审工作区实体
     */
    @Override
    public DraftWorkspace getWorkspaceByTaskId(Long taskId) {
        return workspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId)
        );
    }

    /**
     * v2: 单篇草稿的正文 DIFF — 返回基线 + 本次两份正文的 contentUri。
     * <p>通过 draftId 找到本次草稿 → 查 workspace 的 baselineWorkspaceId →
     * 在基线 workspace 中按 moduleName 匹配基线草稿。</p>
     */
    @Override
    public com.company.codeinsight.modules.draft.dto.DocumentDiffDto getDocumentDiff(Long draftId) {
        KnowledgeDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new com.company.codeinsight.common.exception.BusinessException("草稿不存在: " + draftId);
        }
        DraftWorkspace ws = workspaceMapper.selectById(draft.getWorkspaceId());
        if (ws == null) {
            throw new com.company.codeinsight.common.exception.BusinessException("工作区不存在");
        }

        com.company.codeinsight.modules.draft.dto.DocumentDiffDto dto =
                new com.company.codeinsight.modules.draft.dto.DocumentDiffDto();
        dto.setCurrentDraftId(draft.getId());
        dto.setCurrentContentUri(draft.getContentUri());
        dto.setCurrentModuleName(draft.getModuleName());

        // 基线正文从 releases 目录读取（基线 draft 物理文件在推送后已被删除）
        if (draft.getModuleName() != null && ws.getRepositoryId() != null) {
            com.company.codeinsight.modules.repository.entity.CodeRepository repo =
                    repositoryMapper.selectById(ws.getRepositoryId());
            if (repo != null && repo.getLastPublishedVersionId() != null) {
                com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion version =
                        knowledgeVersionMapper.selectById(repo.getLastPublishedVersionId());
                if (version != null && "PUSHED".equals(version.getStatus())) {
                    java.nio.file.Path releaseDir = storageResolver.releaseDir(
                            version.getSystemId(), version.getRepositoryId(), version.getVersionNum());
                    java.nio.file.Path mapFile = releaseDir.resolve("meta").resolve("module-map.yaml");
                    if (java.nio.file.Files.exists(mapFile)) {
                        String baselineFileName = findModuleFileNameInYaml(mapFile, draft.getModuleName());
                        if (baselineFileName != null) {
                            java.nio.file.Path baselineFile = releaseDir.resolve("modules").resolve(baselineFileName);
                            if (java.nio.file.Files.exists(baselineFile)) {
                                try {
                                    dto.setBaselineContent(java.nio.file.Files.readString(baselineFile));
                                    dto.setBaselineModuleName(draft.getModuleName());
                                } catch (java.io.IOException e) {
                                    log.warn("读取基线 release 文件失败: {}", baselineFile, e);
                                }
                            }
                        }
                    }
                }
            }
        }
        return dto;
    }

    /** 在 module-map.yaml 中按 moduleName 查找对应的文件名 */
    private String findModuleFileNameInYaml(java.nio.file.Path yamlFile, String moduleName) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(yamlFile);
            String pendingName = null;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- name:")) {
                    pendingName = parseYamlQuotedValue(trimmed.substring("- name:".length()));
                } else if (trimmed.startsWith("path:") && pendingName != null) {
                    if (moduleName.equals(pendingName)) {
                        String path = parseYamlQuotedValue(trimmed.substring("path:".length()));
                        int lastSlash = path.lastIndexOf('/');
                        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
                    }
                    pendingName = null;
                }
            }
        } catch (java.io.IOException e) {
            log.warn("解析 module-map.yaml 失败: {}", yamlFile, e);
        }
        return null;
    }

    /** 提取 YAML 引号内的值 */
    private String parseYamlQuotedValue(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"")
                || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * 获取指定 ID 的知识草稿元数据
     */
    @Override
    public KnowledgeDraft getDraftById(Long draftId) {
        return draftMapper.selectById(draftId);
    }

    /**
     * 获取草稿的最新的正文内容
     * 优先从自动保存的临时缓存（Redis 或 JVM 内存）中提取尚未手动提交的修改，无临时缓存则读取物理存储正文。
     *
     * @param draftId 草稿 ID
     * @return Markdown 格式的文档内容
     */
    @Override
    public String getDraftContent(Long draftId) {
        KnowledgeDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new BusinessException("草稿记录不存在");
        }

        // 1. 尝试从 Redis 自动保存区拿
        String autoSavedContent = null;
        if (redisTemplate != null) {
            try {
                autoSavedContent = redisTemplate.opsForValue().get("draft:autosave:" + draftId);
            } catch (Exception e) {
                log.warn("从 Redis 读取自动保存失败", e);
            }
        }

        // 2. 如果找到了有效的自动保存缓存，直接返回该临时内容
        if (StringUtils.hasText(autoSavedContent)) {
            return autoSavedContent;
        }

        // 3. 若无自动保存痕迹，从原始 URI 地址读取磁盘上的正式草稿文件
        try {
            Path path = DraftFileUtil.resolve(draft.getContentUri(), storageResolver);
            File file = path.toFile();
            if (file.exists()) {
                return Files.readString(path);
            }
        } catch (Exception e) {
            log.error("从 URI 读取草稿正文失败: {}", draft.getContentUri(), e);
        }
        return "# 空文档";
    }

    /**
     * 手动保存草稿修改并进行物理文件落盘
     * 清空相关的临时自动保存缓存，计算并持久化修订记录（包含新增/删除行数统计）。
     *
     * @param draftId 草稿 ID
     * @param content 修改后的新文档内容
     * @param author  保存操作的负责人用户名
     * @param remark  用户填写的保存备注（修改日志）
     */
    @Override
    @Transactional
    public void saveDraft(Long draftId, String content, String author, String remark) {
        KnowledgeDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new BusinessException("草稿不存在");
        }

        // 推送锁定：任务进入 PUSHING / PUSHED 后草稿只读，service 层兜底拦截
        // 防止前端绕 UI 直接调接口绕过锁定。前端也对应 disabled + tooltip 提示。
        assertNotPushed(draft);
        renewEditLockIfCluster(draftId, author);

        try {
            // 读取原有的物理正文以对比差异
            Path draftPath = DraftFileUtil.resolve(draft.getContentUri(), storageResolver);
            File file = draftPath.toFile();
            String originalContent = "";
            if (file.exists()) {
                originalContent = Files.readString(draftPath);
            }

            // 确保父目录存在（CI/CD 初始化时若目录缺失也不会让写文件失败）
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // 物理覆盖写入新正文
            Files.writeString(draftPath, content);

            // 成功保存后，立即清理所有自动保存的临时缓存
            if (redisTemplate != null) {
                try {
                    redisTemplate.delete("draft:autosave:" + draftId);
                } catch (Exception ignored) {
                }
            }
            if (clusterProperties.isEnabled()) {
                draftEditLockService.release(draftId, author);
            }

            // 计算 MD5 哈希校验和，状态流转为 EDITING (复核人已编辑)
            String hash = DigestUtils.md5DigestAsHex(content.getBytes());
            draft.setHash(hash);
            draft.setStatus(DraftStatus.EDITING.name());
            draft.setUpdatedDate(LocalDateTime.now());
            draftMapper.updateById(draft);

            // 差分对比原文本与新文本，生成行级增改统计信息
            String diffSummary = computeDiffSummary(originalContent, content);
            String finalRemark = (remark != null ? remark : "保存修改") + diffSummary;

            // 自动将本次修改记录归档到 ci_draft_revision 修订表中
            DraftRevision revision = new DraftRevision();
            revision.setDraftId(draftId);
            revision.setContentUri(draft.getContentUri());
            revision.setAuthor(author);
            revision.setRemark(finalRemark);
            revision.setCreatedDate(LocalDateTime.now());
            revisionMapper.insert(revision);

            // 若代码库是本地路径，同时在本地代码库指定目录下更新保存一份备份
            try {
                DraftWorkspace ws = workspaceMapper.selectById(draft.getWorkspaceId());
                if (ws != null) {
                    CodeRepository repo = repositoryMapper.selectById(ws.getRepositoryId());
                    if (repo != null && StringUtils.hasText(repo.getGitUrl())) {
                        File localRepoDir = new File(repo.getGitUrl());
                        if (localRepoDir.exists() && localRepoDir.isDirectory()) {
                            File targetDraftDir = new File(localRepoDir, "docs/code-insight/drafts");
                            if (!targetDraftDir.exists()) {
                                targetDraftDir.mkdirs();
                            }
                            File targetDraftFile = new File(targetDraftDir, draft.getModuleName().replaceAll("[\\s/\\(\\)]", "_") + ".md");
                            Files.writeString(targetDraftFile.toPath(), content);
                            log.info("本地模式：用户保存修改，同步更新至本地代码库指定目录：{}", targetDraftFile.getAbsolutePath());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("同步更新草稿文档至本地代码库指定目录失败", e);
            }

        } catch (IOException e) {
            log.error("保存草稿内容失败", e);
            throw new BusinessException("保存草稿正文失败");
        }
    }

    /**
     * 临时自动保存草稿
     * 避免网页崩溃导致数据丢失，缓存有效期设定为 7 天。
     */
    @Override
    public void autoSaveDraft(Long draftId, String content, String author) {
        requireRedisForDraftCache();
        renewEditLockIfCluster(draftId, author);
        redisTemplate.opsForValue().set("draft:autosave:" + draftId, content, 7, TimeUnit.DAYS);
    }

    private void requireRedisForDraftCache() {
        if (redisTemplate == null) {
            throw new BusinessException(clusterProperties.isEnabled()
                    ? "集群模式需要 Redis 支持草稿自动保存"
                    : "草稿自动保存需要 Redis，请检查 Redis 连接");
        }
    }

    private void renewEditLockIfCluster(Long draftId, String author) {
        if (clusterProperties.isEnabled()) {
            draftEditLockService.renew(draftId, author);
        }
    }

    /**
     * 单文件级「确认通过」：仅标记该草稿为 CONFIRMED，不再触发任何级联状态推进。
     *
     * <p>复核工作区「确认通过」按钮的真正语义入口是 {@link #confirmTask} —
     * 本方法保留是为了支持精细化场景（如逐模块审完一篇确认一篇），但不再自动升级工作区和任务，
     * 避免「点了一篇却把整组锁死」的歧义。</p>
     *
     * <p>推送锁定：任务处于 PUSHING / PUSHED 时不再允许 CONFIRMED 操作。</p>
     */
    @Override
    @Transactional
    public void confirmDraft(Long draftId, String author, String comment) {
        KnowledgeDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new BusinessException("草稿不存在");
        }
        assertNotPushed(draft);

        // 幂等保护：草稿已确认则直接返回
        if (DraftStatus.CONFIRMED.name().equals(draft.getStatus())) {
            return;
        }

        // 修改状态为 CONFIRMED
        draft.setStatus(DraftStatus.CONFIRMED.name());
        draft.setUpdatedDate(LocalDateTime.now());
        draftMapper.updateById(draft);

        // 若填写了通过意见，写入 ci_review_comment 表（type=PASS），与驳回意见统一管理
        if (StringUtils.hasText(comment)) {
            DraftReviewComment passComment = new DraftReviewComment();
            passComment.setDraftId(draftId);
            passComment.setAuthor(author);
            passComment.setComment(com.company.codeinsight.common.util.DbStringLimits.truncate(
                    comment, com.company.codeinsight.common.util.DbStringLimits.COMMENT));
            passComment.setType("PASS");
            passComment.setCreatedDate(LocalDateTime.now());
            commentMapper.insert(passComment);
        }
    }

    /**
     * 任务级「确认通过」：把 task 下整组草稿一次性置为 CONFIRMED，
     * workspace → COMPLETED，任务状态机推进到 CONFIRMED。
     *
     * <p>这是复核工作区工具栏「确认通过」按钮的真实语义入口 —
     * 操作粒度是任务，而非单文件。详见 {@link DraftService#confirmTask}。</p>
     */
    @Override
    @Transactional
    public void confirmTask(Long taskId, String author, String comment) {
        DraftWorkspace ws = workspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId)
        );
        if (ws == null) {
            throw new BusinessException("任务 #" + taskId + " 没有关联的草稿工作区");
        }

        // 推送锁定守卫：PUSHING / PUSHED / CONFIRMED 不允许再次 CONFIRMED
        DecompileTask task = taskMapper.selectById(taskId);
        if (task != null) {
            String st = task.getStatus();
            if (TaskStatus.PUSHING.name().equals(st) || TaskStatus.PUSHED.name().equals(st)) {
                throw new BusinessException("任务 #" + taskId + " 已" +
                        (TaskStatus.PUSHED.name().equals(st) ? "推送" : "在推送") +
                        "，无法再次确认");
            }
            if (TaskStatus.CONFIRMED.name().equals(st)) {
                throw new BusinessException("任务 #" + taskId + " 已确认，无需重复确认");
            }
        }

        List<KnowledgeDraft> drafts = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>().eq(KnowledgeDraft::getWorkspaceId, ws.getId())
        );
        if (drafts.isEmpty()) {
            throw new BusinessException("任务 #" + taskId + " 工作区为空，无需确认");
        }
        for (KnowledgeDraft d : drafts) {
            String st = d.getStatus();
            if (!DraftStatus.CONFIRMED.name().equals(st) && !DraftStatus.PUSHED.name().equals(st)) {
                throw new BusinessException("尚有未逐篇确认的草稿: " + d.getModuleName()
                        + "（当前: " + st + "），请先对每篇文档点击「通过」");
            }
        }
        LocalDateTime now = LocalDateTime.now();

        // 2. 工作区晋升 COMPLETED
        ws.setStatus("COMPLETED");
        ws.setUpdatedDate(now);
        workspaceMapper.updateById(ws);

        // 3. 任务级通过意见留痕（挂到工作区下第一篇草稿，避免新建「工作区级评论」表）
        if (StringUtils.hasText(comment) && !drafts.isEmpty()) {
            DraftReviewComment passComment = new DraftReviewComment();
            passComment.setDraftId(drafts.get(0).getId());
            passComment.setAuthor(author);
            passComment.setComment(com.company.codeinsight.common.util.DbStringLimits.truncate(
                    "[任务级通过] " + comment, com.company.codeinsight.common.util.DbStringLimits.COMMENT));
            passComment.setType("PASS");
            passComment.setCreatedDate(now);
            commentMapper.insert(passComment);
        }

        // 4. 联动：把任务从 PENDING_REVIEW / REVIEWING 推到 CONFIRMED
        // 状态机会校验合法性，FAILED / CANCELLED / 已 PUSHED 等状态不会强行改动
        if (task != null) {
            String current = task.getStatus();
            if (TaskStatus.PENDING_REVIEW.name().equals(current)
                    || TaskStatus.REVIEWING.name().equals(current)) {
                try {
                    stateMachineService.transitTo(task, TaskStatus.CONFIRMED, "任务整体确认通过");
                } catch (Exception e) {
                    log.warn("联动任务状态推进失败（任务={}，当前={}）：{}",
                            task.getId(), current, e.getMessage());
                }
            }
        }
    }

    @Override
    public void assertTaskReadyForKnowledgePublish(Long taskId) {
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("未找到知识构建任务");
        }
        if (!TaskStatus.CONFIRMED.name().equals(task.getStatus())) {
            throw new BusinessException("任务尚未整体确认（当前状态: " + task.getStatus()
                    + "），请先在复核页逐篇通过后点击「任务整体通过」");
        }
        DraftWorkspace ws = workspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId));
        if (ws == null) {
            throw new BusinessException("草稿工作区不存在");
        }
        List<KnowledgeDraft> drafts = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>().eq(KnowledgeDraft::getWorkspaceId, ws.getId()));
        if (drafts.isEmpty()) {
            throw new BusinessException("工作区内没有草稿，无法发布");
        }
        for (KnowledgeDraft d : drafts) {
            String st = d.getStatus();
            if (!DraftStatus.CONFIRMED.name().equals(st) && !DraftStatus.PUSHED.name().equals(st)) {
                throw new BusinessException("模块 " + d.getModuleName()
                        + " 尚未确认（当前: " + st + "），无法创建版本或推送");
            }
        }
    }

    /**
     * 获取指定草稿的所有修订历史
     */
    @Override
    public List<DraftRevision> getRevisions(Long draftId) {
        return revisionMapper.selectList(
                new LambdaQueryWrapper<DraftRevision>().eq(DraftRevision::getDraftId, draftId).orderByDesc(DraftRevision::getCreatedDate)
        );
    }

    /**
     * 获取指定草稿的所有评审反馈与驳回意见
     */
    @Override
    public List<DraftReviewComment> getComments(Long draftId) {
        return commentMapper.selectList(
                new LambdaQueryWrapper<DraftReviewComment>().eq(DraftReviewComment::getDraftId, draftId).orderByDesc(DraftReviewComment::getCreatedDate)
        );
    }

    /**
     * 任务级复核意见聚合：一次拉取 task 下整组草稿的复核意见，附 JOIN 来源草稿的 moduleName / filePath。
     *
     * <p>实现要点：</p>
     * <ol>
     *   <li>按 taskId 反查 ci_draft_workspace 拿到 workspaceId</li>
     *   <li>一次性 selectBatchIds 拉取工作区下所有草稿（O(1) IO），构造 draftId → KnowledgeDraft 索引</li>
     *   <li>用 IN(draftIds) 一次查所有复核意见（避免 N+1）</li>
     *   <li>内存 join 出 moduleName / filePath，按 createdDate desc 排序</li>
     * </ol>
     *
     * <p>与 {@link #getComments(Long)} 的差异：本方法面向整组任务，输出补齐来源草稿元信息；
     * 单文件级仍可使用 {@code getComments(draftId)}（细粒度查阅某篇的全部意见）。</p>
     */
    @Override
    public List<com.company.codeinsight.modules.draft.dto.TaskCommentDto> listAllCommentsByTask(Long taskId) {
        DraftWorkspace ws = workspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId)
        );
        if (ws == null) {
            return Collections.emptyList();
        }

        // 1. 一次性拉工作区下所有草稿（O(1) IO）
        List<KnowledgeDraft> drafts = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>().eq(KnowledgeDraft::getWorkspaceId, ws.getId())
        );
        if (drafts.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 构造 draftId → KnowledgeDraft 索引
        Map<Long, KnowledgeDraft> draftIndex = drafts.stream()
                .collect(Collectors.toMap(KnowledgeDraft::getId, d -> d, (a, b) -> a));

        // 3. 一次 IN 查询所有复核意见（避免 N+1）
        List<Long> draftIds = new ArrayList<>(draftIndex.keySet());
        List<DraftReviewComment> comments = commentMapper.selectList(
                new LambdaQueryWrapper<DraftReviewComment>()
                        .in(DraftReviewComment::getDraftId, draftIds)
                        .orderByDesc(DraftReviewComment::getCreatedDate)
        );

        // 4. 内存 join 出 moduleName / filePath
        List<com.company.codeinsight.modules.draft.dto.TaskCommentDto> result = new ArrayList<>(comments.size());
        for (DraftReviewComment c : comments) {
            com.company.codeinsight.modules.draft.dto.TaskCommentDto dto =
                    new com.company.codeinsight.modules.draft.dto.TaskCommentDto();
            dto.setId(c.getId());
            dto.setDraftId(c.getDraftId());
            dto.setAuthor(c.getAuthor());
            dto.setComment(c.getComment());
            dto.setType(c.getType());
            dto.setCreatedDate(c.getCreatedDate());
            KnowledgeDraft src = draftIndex.get(c.getDraftId());
            if (src != null) {
                dto.setModuleName(src.getModuleName());
                dto.setFilePath(src.getFilePath());
            }
            result.add(dto);
        }
        return result;
    }

    /**
     * 获取指定草稿的所有关联代码来源引用（文件名及行范围）
     */
    @Override
    public List<DraftSourceReference> getSourceReferences(Long draftId) {
        return referenceMapper.selectList(
                new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draftId)
        );
    }

    /**
     * 轻量级差分差异计算函数
     * 通过集合取差集，快速计算保存内容相比于历史物理内容的行级修改差异。
     */
    private String computeDiffSummary(String oldContent, String newContent) {
        if (oldContent == null) oldContent = "";
        if (newContent == null) newContent = "";
        // 按平台自适应换行符分割文件行
        String[] oldLines = oldContent.split("\\R");
        String[] newLines = newContent.split("\\R");

        java.util.Set<String> oldSet = new java.util.HashSet<>(java.util.Arrays.asList(oldLines));
        java.util.Set<String> newSet = new java.util.HashSet<>(java.util.Arrays.asList(newLines));

        // 统计新增行：在新内容中但不在老内容中出现的行
        int added = 0;
        for (String line : newLines) {
            if (!oldSet.contains(line)) {
                added++;
            }
        }
        // 统计删除行：在老内容中但未在新内容中出现的行
        int deleted = 0;
        for (String line : oldLines) {
            if (!newSet.contains(line)) {
                deleted++;
            }
        }
        return String.format(" [新增 %d 行, 删除 %d 行]", added, deleted);
    }

    // ========================================================================
    // 复核工作区「可预览系统」相关接口
    // ========================================================================

    /**
     * 推送锁定守卫：任务处于 PUSHING / PUSHED 时不允许草稿写入。
     * 前端对应 disabled + tooltip，后端这里兜底防止绕过 UI 的请求。
     */
    private void assertNotPushed(KnowledgeDraft draft) {
        if (draft == null || draft.getWorkspaceId() == null) return;
        DraftWorkspace ws = workspaceMapper.selectById(draft.getWorkspaceId());
        if (ws == null) return;
        DecompileTask task = taskMapper.selectById(ws.getTaskId());
        if (task == null) return;
        String st = task.getStatus();
        if (TaskStatus.PUSHING.name().equals(st) || TaskStatus.PUSHED.name().equals(st)) {
            throw new BusinessException("任务 #" + task.getId() + " 已" +
                    (TaskStatus.PUSHED.name().equals(st) ? "推送" : "在推送") +
                    "，草稿已锁定，无法继续修改");
        }
    }

    /** 可复核任务的状态白名单 */
    private static final List<String> REVIEWABLE_STATUSES = List.of("PENDING_REVIEW", "REVIEWING", "CONFIRMED");

    /**
     * 列出所有「可预览」系统：至少有一条可复核任务，并按状态汇总计数。
     * 复用 ci_system + ci_task 两表数据，Java 侧 group-by，避免改 mapper XML。
     */
    @Override
    public List<PreviewSystemDto> listPreviewSystems() {
        // 1. 拉取所有可复核任务（限定状态白名单）
        List<DecompileTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<DecompileTask>().in(DecompileTask::getStatus, REVIEWABLE_STATUSES)
        );
        if (tasks.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 按 (systemId, status) 聚合
        Map<Long, Map<String, Long>> grouped = tasks.stream().collect(
                Collectors.groupingBy(
                        DecompileTask::getSystemId,
                        Collectors.groupingBy(DecompileTask::getStatus, Collectors.counting())
                )
        );

        // 3. 收集涉及到的 systemId 一次拉取 ci_system
        List<Long> systemIds = new ArrayList<>(grouped.keySet());
        Map<Long, SystemApplication> systemMap = systemMapper.selectBatchIds(systemIds).stream()
                .collect(Collectors.toMap(SystemApplication::getId, s -> s));

        // 4. 组装 DTO，按 totalReviewableCount 倒序
        List<PreviewSystemDto> result = new ArrayList<>(systemMap.size());
        for (Map.Entry<Long, SystemApplication> entry : systemMap.entrySet()) {
            Long sysId = entry.getKey();
            SystemApplication sys = entry.getValue();
            Map<String, Long> statusCount = grouped.getOrDefault(sysId, Collections.emptyMap());

            PreviewSystemDto dto = new PreviewSystemDto();
            dto.setSystemId(sysId);
            dto.setSystemName(sys.getName());
            dto.setOwner(sys.getOwner());
            dto.setPendingReviewCount(statusCount.getOrDefault("PENDING_REVIEW", 0L));
            dto.setReviewingCount(statusCount.getOrDefault("REVIEWING", 0L));
            dto.setConfirmedCount(statusCount.getOrDefault("CONFIRMED", 0L));
            dto.setTotalReviewableCount(
                    dto.getPendingReviewCount() + dto.getReviewingCount() + dto.getConfirmedCount()
            );
            result.add(dto);
        }
        result.sort(Comparator.comparingLong(PreviewSystemDto::getTotalReviewableCount).reversed());
        return result;
    }

    /**
     * 列出某系统下的可复核任务。statuses 为空时使用白名单。
     */
    @Override
    public List<DecompileTask> listReviewableTasks(Long systemId, List<String> statuses) {
        List<String> effectiveStatuses = (statuses == null || statuses.isEmpty()) ? REVIEWABLE_STATUSES : statuses;
        LambdaQueryWrapper<DecompileTask> wrapper = new LambdaQueryWrapper<DecompileTask>()
                .in(DecompileTask::getStatus, effectiveStatuses)
                .orderByDesc(DecompileTask::getUpdatedDate);
        if (systemId != null) {
            wrapper.eq(DecompileTask::getSystemId, systemId);
        }
        return taskMapper.selectList(wrapper);
    }

    /**
     * 全局新建任务前置条件查询：以「待知识复核任务」为唯一权威源。
     * 识别仍处于 {@link TaskStatus#PENDING_REVIEW} / {@link TaskStatus#REVIEWING} 的任务，
     * 然后把这些任务关联 workspace 下仍处于非终态（DRAFT / EDITING）的草稿作为阻塞明细。
     *
     * <p>判定基准从「草稿非终态」改为「任务待知识复核」：
     * 任务已 CANCELLED / FAILED / PUSHED 后草稿仍残留 DRAFT / EDITING 不再视为阻塞，
     * 与 {@link com.company.codeinsight.modules.task.service.impl.DecompileTaskServiceImpl#validateNoPendingReviewTasks}
     * 的语义保持一致。</p>
     *
     * <p>流水线中间断点（{@link TaskStatus#ENTRYPOINT_REVIEW} /
     * {@link TaskStatus#MODULE_HIERARCHY_REVIEW}）不属于「知识复核」，
     * 不阻塞新建任务。</p>
     *
     * <p>设计要点：</p>
     * <ul>
     *   <li>三表关联通过 MyBatis-Plus 三次 selectList 内存中拼接，避免自定义 XML。</li>
     *   <li>blockingDrafts 按 updated_date desc 排序，让复核人优先处理最近变更的草稿。</li>
     *   <li>无阻塞时 ready=true，blockingDrafts 为空列表，前端可直接放行向导。</li>
     * </ul>
     */
    @Override
    public RepositoryReadinessDto findGlobalReadiness() {
        // 1. 拉取所有待知识复核任务的 taskId
        List<Long> pendingTaskIds = taskMapper.selectList(
                new LambdaQueryWrapper<DecompileTask>()
                        .in(DecompileTask::getStatus, KNOWLEDGE_REVIEW_STATUSES)
        ).stream().map(DecompileTask::getId).collect(Collectors.toList());

        RepositoryReadinessDto dto = new RepositoryReadinessDto();
        applyPromptReadiness(dto, null, null);

        if (pendingTaskIds.isEmpty()) {
            // 无待复核任务 → 直接放行（无需再查草稿）
            dto.setUnconfirmedCount(0);
            dto.setReady(dto.isPromptsConfigured());
            return dto;
        }

        // 2. 拉取这些任务对应的工作区
        List<DraftWorkspace> workspaces = workspaceMapper.selectList(
                new LambdaQueryWrapper<DraftWorkspace>()
                        .in(DraftWorkspace::getTaskId, pendingTaskIds)
        );
        if (workspaces.isEmpty()) {
            // 有待复核任务但尚未生成 workspace（如 ENTRYPOINT_REVIEW 早期）
            dto.setUnconfirmedCount(0);
            dto.setReady(dto.isPromptsConfigured());
            return dto;
        }
        Map<Long, DraftWorkspace> wsMap = workspaces.stream()
                .collect(Collectors.toMap(DraftWorkspace::getId, w -> w));
        List<Long> workspaceIds = new ArrayList<>(wsMap.keySet());

        // 3. 在这些工作区中查找非终态草稿（DRAFT / EDITING）
        List<KnowledgeDraft> blocking = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .in(KnowledgeDraft::getWorkspaceId, workspaceIds)
                        .in(KnowledgeDraft::getStatus, NON_TERMINAL_DRAFT_STATUSES)
                        .orderByDesc(KnowledgeDraft::getUpdatedDate)
        );

        dto.setUnconfirmedCount(blocking.size());
        dto.setReady(blocking.isEmpty() && dto.isPromptsConfigured());
        if (blocking.isEmpty()) {
            return dto;
        }

        // 4. 组装明细
        List<RepositoryReadinessDto.BlockingDraft> items = new ArrayList<>(blocking.size());
        for (KnowledgeDraft d : blocking) {
            RepositoryReadinessDto.BlockingDraft item = new RepositoryReadinessDto.BlockingDraft();
            item.setDraftId(d.getId());
            item.setModuleName(d.getModuleName());
            item.setStatus(d.getStatus());
            item.setWorkspaceId(d.getWorkspaceId());
            item.setUpdatedDate(d.getUpdatedDate());

            DraftWorkspace ws = wsMap.get(d.getWorkspaceId());
            if (ws != null) {
                item.setTaskId(ws.getTaskId());
                item.setSystemId(ws.getSystemId());
                item.setRepositoryId(ws.getRepositoryId());
            }
            items.add(item);
        }
        dto.setBlockingDrafts(items);
        return dto;
    }

    /**
     * 草稿非终态白名单：DRAFT（AI 已生成）/ EDITING（复核人编辑中）。
     * CONFIRMED / PUSHED / ARCHIVED 视为已消化。
     */
    private static final List<String> NON_TERMINAL_DRAFT_STATUSES = List.of(
            DraftStatus.DRAFT.name(),
            DraftStatus.EDITING.name()
    );

    /**
     * 任务级「知识复核」白名单：仅文档级人工复核状态。
     * 与 {@link com.company.codeinsight.modules.task.service.impl.DecompileTaskServiceImpl#validateNoPendingReviewTasks}
     * 中的语义保持一致。
     *
     * <p>明确排除 {@link TaskStatus#ENTRYPOINT_REVIEW} /
     * {@link TaskStatus#MODULE_HIERARCHY_REVIEW} —— 这两个是流水线中间断点
     * （默认开启，可关闭），新建任务会进入独立 workspace，不应阻塞并行发起。
     * 「知识复核」指文档已生成、等待/正在人工确认的最终环节。</p>
     */
    private static final List<String> KNOWLEDGE_REVIEW_STATUSES = List.of(
            TaskStatus.PENDING_REVIEW.name(),
            TaskStatus.REVIEWING.name()
    );

    /**
     * 基于系统+仓库的新建任务前置条件查询，作用域收窄到指定组合。
     *
     * <p>判定基准与 {@link #findGlobalReadiness()} 对齐：以「待知识复核任务」为唯一权威源，
     * 只把仍处于 PENDING_REVIEW / REVIEWING 的任务所关联 workspace 下的非终态草稿视为阻塞。
     * 任一参数为空时退化到 {@link #findGlobalReadiness()}。</p>
     */
    @Override
    public RepositoryReadinessDto findReadiness(Long systemId, Long repositoryId) {
        if (systemId == null && repositoryId == null) {
            return findGlobalReadiness();
        }

        // 1. 查询该系统+仓库下的待知识复核任务
        LambdaQueryWrapper<DecompileTask> taskQw = new LambdaQueryWrapper<DecompileTask>()
                .in(DecompileTask::getStatus, KNOWLEDGE_REVIEW_STATUSES);
        if (systemId != null) {
            taskQw.eq(DecompileTask::getSystemId, systemId);
        }
        if (repositoryId != null) {
            taskQw.eq(DecompileTask::getRepositoryId, repositoryId);
        }
        List<Long> pendingTaskIds = taskMapper.selectList(taskQw).stream()
                .map(DecompileTask::getId)
                .collect(Collectors.toList());

        RepositoryReadinessDto dto = new RepositoryReadinessDto();
        applyPromptReadiness(dto, systemId, repositoryId);

        if (pendingTaskIds.isEmpty()) {
            dto.setUnconfirmedCount(0);
            dto.setReady(dto.isPromptsConfigured());
            return dto;
        }

        // 2. 这些任务对应的工作区
        List<DraftWorkspace> workspaces = workspaceMapper.selectList(
                new LambdaQueryWrapper<DraftWorkspace>()
                        .in(DraftWorkspace::getTaskId, pendingTaskIds)
                        .eq(systemId != null, DraftWorkspace::getSystemId, systemId)
                        .eq(repositoryId != null, DraftWorkspace::getRepositoryId, repositoryId)
        );
        if (workspaces.isEmpty()) {
            dto.setUnconfirmedCount(0);
            dto.setReady(dto.isPromptsConfigured());
            return dto;
        }
        Map<Long, DraftWorkspace> wsMap = workspaces.stream()
                .collect(Collectors.toMap(DraftWorkspace::getId, w -> w));
        List<Long> workspaceIds = new ArrayList<>(wsMap.keySet());

        // 3. 在这些工作区中查找非终态草稿
        List<KnowledgeDraft> blocking = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .in(KnowledgeDraft::getWorkspaceId, workspaceIds)
                        .in(KnowledgeDraft::getStatus, NON_TERMINAL_DRAFT_STATUSES)
                        .orderByDesc(KnowledgeDraft::getUpdatedDate)
        );

        dto.setUnconfirmedCount(blocking.size());
        dto.setReady(blocking.isEmpty() && dto.isPromptsConfigured());
        if (blocking.isEmpty()) {
            return dto;
        }

        // 4. 组装明细
        List<RepositoryReadinessDto.BlockingDraft> items = new ArrayList<>(blocking.size());
        for (KnowledgeDraft d : blocking) {
            RepositoryReadinessDto.BlockingDraft item = new RepositoryReadinessDto.BlockingDraft();
            item.setDraftId(d.getId());
            item.setModuleName(d.getModuleName());
            item.setStatus(d.getStatus());
            item.setWorkspaceId(d.getWorkspaceId());
            item.setUpdatedDate(d.getUpdatedDate());

            DraftWorkspace ws = wsMap.get(d.getWorkspaceId());
            if (ws != null) {
                item.setTaskId(ws.getTaskId());
                item.setSystemId(ws.getSystemId());
                item.setRepositoryId(ws.getRepositoryId());
            }
            items.add(item);
        }
        dto.setBlockingDrafts(items);
        return dto;
    }

    private void applyPromptReadiness(RepositoryReadinessDto dto, Long systemId, Long repositoryId) {
        if (repositoryId == null) {
            // 没有仓库上下文时跳过提示词校验（与 readiness 检查的语义对齐：只按仓库判断）
            dto.setPromptsConfigured(true);
            return;
        }
        boolean configured = decompilePromptService.isRepositoryPromptsConfigured(repositoryId);
        dto.setPromptsConfigured(configured);
        if (!configured) {
            dto.setReady(false);
            dto.setPromptsMessage(decompilePromptService.getRepositoryPromptsConfigurationMessage(repositoryId));
        }
    }
}
