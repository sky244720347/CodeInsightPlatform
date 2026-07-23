package com.company.codeinsight.modules.draft.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.common.util.DraftFileUtil;
import com.company.codeinsight.modules.draft.entity.DraftReviewComment;
import com.company.codeinsight.modules.draft.entity.DraftRevision;
import com.company.codeinsight.modules.draft.entity.DraftSourceReference;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.mapper.DraftReviewCommentMapper;
import com.company.codeinsight.modules.draft.mapper.DraftRevisionMapper;
import com.company.codeinsight.modules.draft.mapper.DraftSourceReferenceMapper;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.hierarchy.model.FunctionDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.model.SubModuleDto;
import com.company.codeinsight.modules.hierarchy.service.ModuleHierarchyService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 将草稿工作区收敛为与当前模块层级一致的最终文档集。
 * <p>INCREMENTAL 在 {@code GENERATING_DOC} 成功后调用 {@link #pruneStaleDrafts}，
 * 删除 hierarchy 中已不存在的功能/模块对应草稿（含失效继承文档）。
 * 方案见 {@code docs/incremental-knowledge-prune-and-push-design.md}。</p>
 */
@Slf4j
@Service
public class DraftWorkspacePruneService {

    @Autowired
    private ModuleHierarchyService moduleHierarchyService;

    @Autowired
    private KnowledgeDraftMapper draftMapper;

    @Autowired
    private DraftWorkspaceMapper workspaceMapper;

    @Autowired
    private DraftSourceReferenceMapper sourceReferenceMapper;

    @Autowired
    private DraftRevisionMapper revisionMapper;

    @Autowired
    private DraftReviewCommentMapper commentMapper;

    @Autowired
    private EnvStorageResolver storageResolver;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Value("${code-insight.doc-generation.granularity:function}")
    private String docGenerationGranularity;

    /**
     * 删除 workspace 中 {@code moduleName} 不在当前 hierarchy 期望集合内的草稿（DB + 物理文件）。
     *
     * @return 删除篇数
     */
    @Transactional(rollbackFor = Exception.class)
    public int pruneStaleDrafts(Long taskId, Long workspaceId) {
        if (taskId == null || workspaceId == null) {
            return 0;
        }
        ModuleHierarchy hierarchy = moduleHierarchyService.loadByTaskId(taskId);
        Set<String> expected = collectExpectedModuleNames(hierarchy, docGenerationGranularity);
        List<KnowledgeDraft> drafts = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .eq(KnowledgeDraft::getWorkspaceId, workspaceId));
        if (drafts.isEmpty()) {
            log.info("PRUNE_STALE_DRAFTS taskId={} workspaceId={} removed=0 (empty workspace)", taskId, workspaceId);
            return 0;
        }

        int removed = 0;
        for (KnowledgeDraft d : drafts) {
            if (!StringUtils.hasText(d.getModuleName()) || expected.contains(d.getModuleName())) {
                continue;
            }
            deleteDraftCompletely(d);
            removed++;
            log.info("PRUNE_STALE_DRAFT taskId={} draftId={} moduleName={}", taskId, d.getId(), d.getModuleName());
        }
        log.info("PRUNE_STALE_DRAFTS taskId={} workspaceId={} removed={} kept={}",
                taskId, workspaceId, removed, drafts.size() - removed);
        return removed;
    }

    /**
     * INCREMENTAL 发布门禁：workspace 草稿 moduleName 集合须与 hierarchy 期望集合完全一致。
     */
    public void assertWorkspaceMatchesHierarchy(Long taskId) {
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("未找到知识构建任务");
        }
        if (!"INCREMENTAL".equals(task.getType())) {
            return;
        }
        DraftWorkspace ws = workspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId));
        if (ws == null) {
            throw new BusinessException("草稿工作区不存在");
        }
        ModuleHierarchy hierarchy = moduleHierarchyService.loadByTaskId(taskId);
        Set<String> expected = collectExpectedModuleNames(hierarchy, docGenerationGranularity);
        List<KnowledgeDraft> drafts = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .eq(KnowledgeDraft::getWorkspaceId, ws.getId()));
        Set<String> actual = new HashSet<>();
        for (KnowledgeDraft d : drafts) {
            if (StringUtils.hasText(d.getModuleName())) {
                actual.add(d.getModuleName());
            }
        }
        Set<String> extra = new LinkedHashSet<>(actual);
        extra.removeAll(expected);
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        if (!extra.isEmpty() || !missing.isEmpty()) {
            StringBuilder msg = new StringBuilder("增量任务工作区与模块层级不一致，无法创建版本或推送");
            if (!extra.isEmpty()) {
                msg.append("；多余草稿: ").append(String.join(", ", extra));
            }
            if (!missing.isEmpty()) {
                msg.append("；缺失文档: ").append(String.join(", ", missing));
            }
            throw new BusinessException(msg.toString());
        }
    }

    /**
     * 按文档生成粒度从 hierarchy 收集期望的 {@code module_name} 集合。
     * <p>function：{@code 模块 / 子模块 / 功能}；module：仅模块名。</p>
     */
    public static Set<String> collectExpectedModuleNames(ModuleHierarchy hierarchy, String granularity) {
        Set<String> names = new LinkedHashSet<>();
        if (hierarchy == null || hierarchy.getModules() == null || hierarchy.getModules().isEmpty()) {
            return names;
        }
        boolean functionGranularity = granularity == null
                || "function".equalsIgnoreCase(granularity.trim());
        for (ModuleDto m : hierarchy.getModules().values()) {
            if (m == null || !StringUtils.hasText(m.getModuleName())) {
                continue;
            }
            if (!functionGranularity) {
                names.add(m.getModuleName());
                continue;
            }
            if (m.getSubModules() == null) {
                continue;
            }
            for (SubModuleDto sm : m.getSubModules().values()) {
                if (sm == null || !StringUtils.hasText(sm.getSubModuleName()) || sm.getFunctions() == null) {
                    continue;
                }
                for (FunctionDto fn : sm.getFunctions().values()) {
                    if (fn == null || !StringUtils.hasText(fn.getFunctionName())) {
                        continue;
                    }
                    names.add(m.getModuleName() + " / " + sm.getSubModuleName() + " / " + fn.getFunctionName());
                }
            }
        }
        return names;
    }

    private void deleteDraftCompletely(KnowledgeDraft d) {
        Long draftId = d.getId();
        if (draftId == null) {
            return;
        }
        sourceReferenceMapper.delete(new LambdaQueryWrapper<DraftSourceReference>()
                .eq(DraftSourceReference::getDraftId, draftId));
        revisionMapper.delete(new LambdaQueryWrapper<DraftRevision>()
                .eq(DraftRevision::getDraftId, draftId));
        commentMapper.delete(new LambdaQueryWrapper<DraftReviewComment>()
                .eq(DraftReviewComment::getDraftId, draftId));
        int rows = draftMapper.deleteById(draftId);
        if (rows <= 0) {
            throw new BusinessException("裁剪失效草稿失败（DB 未删除）: draftId=" + draftId
                    + " moduleName=" + d.getModuleName());
        }
        deletePhysicalFileQuietly(d);
    }

    private void deletePhysicalFileQuietly(KnowledgeDraft d) {
        try {
            if (StringUtils.hasText(d.getContentUri())) {
                Path p = DraftFileUtil.resolve(d.getContentUri(), storageResolver);
                Files.deleteIfExists(p);
            }
        } catch (Exception e) {
            log.warn("PRUNE_STALE_DRAFT 删除物理文件失败 draftId={} uri={}: {}",
                    d.getId(), d.getContentUri(), e.toString());
        }
        // 兼容：contentUri 解析失败或路径不一致时，按 draftsRoot + filePath 再试
        try {
            if (StringUtils.hasText(d.getFilePath())) {
                Path byPath = storageResolver.draftsRoot().resolve(d.getFilePath()).normalize();
                Files.deleteIfExists(byPath);
            }
        } catch (Exception e) {
            log.debug("PRUNE_STALE_DRAFT filePath 清理跳过 draftId={}: {}", d.getId(), e.toString());
        }
    }

    /** 测试/诊断用：当前配置的文档粒度（小写） */
    public String currentGranularity() {
        return docGenerationGranularity == null
                ? "function"
                : docGenerationGranularity.toLowerCase(Locale.ROOT);
    }
}
