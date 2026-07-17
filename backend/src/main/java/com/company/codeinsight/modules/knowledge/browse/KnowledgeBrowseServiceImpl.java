package com.company.codeinsight.modules.knowledge.browse;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.response.PageResult;
import com.company.codeinsight.common.util.DraftFileUtil;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.draft.service.DraftService;
import com.company.codeinsight.modules.knowledge.browse.dto.KnowledgeBrowseContentRequest;
import com.company.codeinsight.modules.knowledge.browse.dto.KnowledgeBrowseItem;
import com.company.codeinsight.modules.knowledge.browse.dto.KnowledgeBrowseQuery;
import com.company.codeinsight.modules.knowledge.browse.dto.KnowledgeBrowseTreeQuery;
import com.company.codeinsight.modules.knowledge.browse.dto.KnowledgeBrowseTreeResult;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.mapper.SystemApplicationMapper;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识查看服务实现：按系统聚合草稿 + 索引/清单文件。
 * <ul>
 *   <li>草稿：通过 {@code ci_knowledge_draft JOIN ci_draft_workspace JOIN ci_task} 限定 systemId</li>
 *   <li>索引/清单：调注入的 {@link KnowledgeBrowseSource}（默认 temp-repos 实现）</li>
 * </ul>
 */
@Slf4j
@Service
public class KnowledgeBrowseServiceImpl implements KnowledgeBrowseService {

    /** 与 index/manifest 行的 status 字段对齐 */
    private static final String STATUS_GENERATED = "GENERATED";

    /** draft 类型枚举（与 controller / DTO 对齐） */
    public static final String TYPE_DRAFT = "DRAFT";
    public static final String TYPE_INDEX = "INDEX";
    public static final String TYPE_MANIFEST = "MANIFEST";
    public static final String TYPE_ALL = "ALL";

    /** 数据源标识 */
    public static final String SOURCE_DB = "DB";
    public static final String SOURCE_TEMP_REPOS = "TEMP_REPOS";
    public static final String SOURCE_RELEASE = "RELEASE";

    @Autowired
    private KnowledgeDraftMapper draftMapper;

    @Autowired
    private DraftWorkspaceMapper workspaceMapper;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired
    private KnowledgeVersionMapper versionMapper;

    @Autowired
    private DraftService draftService;

    @Autowired
    private KnowledgeBrowseSource browseSource;

    @Value("${code-insight.browse.source:temp-repos}")
    private String sourceType;

    @Autowired
    private com.company.codeinsight.common.storage.EnvStorageResolver storageResolver;

    @Autowired
    private SystemApplicationMapper systemMapper;

    @Autowired
    private CodeRepositoryMapper repositoryMapper;

    @Autowired
    private KnowledgeBrowseTreeService treeService;

    @Autowired
    private RepositoryActiveKnowledgeResolver activeKnowledgeResolver;

    @Autowired
    private ReleaseKnowledgeBrowseHelper releaseBrowseHelper;

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Override
    public PageResult<KnowledgeBrowseItem> listPage(KnowledgeBrowseQuery query) {
        if (query == null) {
            query = new KnowledgeBrowseQuery();
        }
        long current = query.getCurrent() == null || query.getCurrent() < 1 ? 1L : query.getCurrent();
        long size = query.getSize() == null || query.getSize() < 1 ? 20L : Math.min(query.getSize(), 200L);

        String type = StringUtils.hasText(query.getType()) ? query.getType().toUpperCase(Locale.ROOT) : TYPE_ALL;

        if (query.getRepositoryId() != null) {
            return listPublishedPage(query, type, current, size);
        }

        List<DecompileTask> tasks = collectTasks(query);
        if (tasks.isEmpty()) {
            return new PageResult<>(0, size, current, Collections.emptyList());
        }
        List<Long> taskIds = tasks.stream().map(DecompileTask::getId).collect(Collectors.toList());
        Map<Long, DecompileTask> taskById = tasks.stream()
                .collect(Collectors.toMap(DecompileTask::getId, t -> t, (a, b) -> a));

        List<KnowledgeBrowseItem> items = new ArrayList<>();
        if (TYPE_ALL.equals(type) || TYPE_DRAFT.equals(type)) {
            items.addAll(loadDraftItems(taskIds, query, taskById));
        }
        if (TYPE_ALL.equals(type) || TYPE_INDEX.equals(type) || TYPE_MANIFEST.equals(type)) {
            items.addAll(loadIndexItems(taskIds, query, type, taskById));
        }

        enrichItemsWithContext(items, taskById);
        items = applyClientSideFilters(items, query);
        items.sort((a, b) -> {
            String ua = a.getUpdatedDate() == null ? "" : a.getUpdatedDate();
            String ub = b.getUpdatedDate() == null ? "" : b.getUpdatedDate();
            return ub.compareTo(ua);
        });

        long total = items.size();
        int from = (int) Math.min((current - 1) * size, total);
        int to = (int) Math.min(from + size, total);
        List<KnowledgeBrowseItem> page = from >= to ? Collections.emptyList() : items.subList(from, to);

        log.debug("KnowledgeBrowseService.listPage systemId={} repositoryId={} type={} total={} page={}/{}",
                query.getSystemId(), query.getRepositoryId(), type, total, current, size);
        return new PageResult<>(total, size, current, page);
    }

    @Override
    public KnowledgeBrowseTreeResult buildTree(KnowledgeBrowseTreeQuery query) {
        return treeService.buildTree(query);
    }

    @Override
    public String readContent(KnowledgeBrowseContentRequest req) {
        if (req == null) {
            throw new BusinessException("请求不能为空");
        }
        if (StringUtils.hasText(req.getContentUri())) {
            try {
                Path p = DraftFileUtil.resolve(req.getContentUri(), storageResolver);
                if (!Files.isRegularFile(p)) {
                    throw new BusinessException("文件不存在");
                }
                long size = Files.size(p);
                if (size > ReleaseKnowledgeBrowseHelper.SIZE_LIMIT_BYTES) {
                    throw new BusinessException("文件过大（" + size + " 字节），超过 5 MB 上限");
                }
                return Files.readString(p);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException("读取发布产物失败：" + e.getMessage());
            }
        }
        if (!StringUtils.hasText(req.getType())) {
            throw new BusinessException("type 不能为空");
        }
        String type = req.getType().toUpperCase(Locale.ROOT);
        switch (type) {
            case TYPE_DRAFT:
                return readDraftContent(req);
            case TYPE_INDEX:
            case TYPE_MANIFEST:
                return browseSource.readIndexFile(req.getTaskId(), req.getFilePath());
            default:
                throw new BusinessException("不支持的文件类型：" + type);
        }
    }

    private PageResult<KnowledgeBrowseItem> listPublishedPage(KnowledgeBrowseQuery query, String type,
                                                              long current, long size) {
        ActiveKnowledgeContext ctx = activeKnowledgeResolver.require(query.getRepositoryId());
        if (query.getSystemId() != null && !query.getSystemId().equals(ctx.getSystemId())) {
            return new PageResult<>(0, size, current, Collections.emptyList());
        }
        if (query.getVersionId() != null && !query.getVersionId().equals(ctx.getVersionId())) {
            return new PageResult<>(0, size, current, Collections.emptyList());
        }
        if (query.getTaskId() != null && !query.getTaskId().equals(ctx.getTaskId())) {
            return new PageResult<>(0, size, current, Collections.emptyList());
        }

        List<KnowledgeBrowseItem> items = new ArrayList<>();
        if (TYPE_ALL.equals(type) || TYPE_INDEX.equals(type) || TYPE_MANIFEST.equals(type)) {
            for (KnowledgeBrowseSource.IndexFileEntry e : releaseBrowseHelper.listFiles(ctx)) {
                if (!typeMatches(e.type(), type)) {
                    continue;
                }
                KnowledgeBrowseItem item = new KnowledgeBrowseItem();
                item.setId(e.type().toLowerCase(Locale.ROOT) + ":release:" + ctx.getVersionId() + ":" + e.relativePath());
                item.setName(basename(e.relativePath()));
                item.setType(e.type());
                item.setTaskId(ctx.getTaskId());
                item.setVersionId(ctx.getVersionId());
                item.setVersionNum(ctx.getVersionNum());
                item.setFilePath(e.relativePath());
                item.setSize(e.size());
                item.setStatus(STATUS_GENERATED);
                item.setUpdatedDate(formatDate(e.updatedDate()));
                item.setSource(SOURCE_RELEASE);
                item.setContentUri(releaseBrowseHelper.buildContentUri(ctx, e.relativePath()));
                item.setSystemId(ctx.getSystemId());
                item.setRepositoryId(ctx.getRepositoryId());
                items.add(item);
            }
        }

        SystemApplication sys = systemMapper.selectById(ctx.getSystemId());
        CodeRepository repo = repositoryMapper.selectById(ctx.getRepositoryId());
        for (KnowledgeBrowseItem item : items) {
            item.setSystemName(KnowledgeBrowseTreeService.formatSystemName(sys));
            item.setRepositoryName(KnowledgeBrowseTreeService.formatRepositoryName(repo));
        }

        items = applyClientSideFilters(items, query);
        items.sort((a, b) -> {
            String ua = a.getUpdatedDate() == null ? "" : a.getUpdatedDate();
            String ub = b.getUpdatedDate() == null ? "" : b.getUpdatedDate();
            return ub.compareTo(ua);
        });

        long total = items.size();
        int from = (int) Math.min((current - 1) * size, total);
        int to = (int) Math.min(from + size, total);
        List<KnowledgeBrowseItem> page = from >= to ? Collections.emptyList() : items.subList(from, to);
        return new PageResult<>(total, size, current, page);
    }

    // ============================ private helpers ============================

    /**
     * 按 query 条件收集任务（systemId / repositoryId 均可选）。
     */
    private List<DecompileTask> collectTasks(KnowledgeBrowseQuery query) {
        LambdaQueryWrapper<DecompileTask> qw = new LambdaQueryWrapper<>();
        if (query.getSystemId() != null) {
            qw.eq(DecompileTask::getSystemId, query.getSystemId());
        }
        if (query.getRepositoryId() != null) {
            qw.eq(DecompileTask::getRepositoryId, query.getRepositoryId());
        }
        qw.select(DecompileTask::getId, DecompileTask::getSystemId, DecompileTask::getRepositoryId);
        return taskMapper.selectList(qw);
    }

    private void enrichItemsWithContext(List<KnowledgeBrowseItem> items, Map<Long, DecompileTask> taskById) {
        if (items.isEmpty()) {
            return;
        }
        Map<Long, SystemApplication> systemCache = new HashMap<>();
        Map<Long, CodeRepository> repoCache = new HashMap<>();
        for (KnowledgeBrowseItem item : items) {
            if (item.getTaskId() == null) {
                continue;
            }
            DecompileTask task = taskById.get(item.getTaskId());
            if (task == null) {
                task = taskMapper.selectById(item.getTaskId());
                if (task != null) {
                    taskById.put(task.getId(), task);
                }
            }
            if (task == null) {
                continue;
            }
            item.setSystemId(task.getSystemId());
            item.setRepositoryId(task.getRepositoryId());
            if (task.getSystemId() != null) {
                SystemApplication sys = systemCache.computeIfAbsent(task.getSystemId(), id -> systemMapper.selectById(id));
                item.setSystemName(KnowledgeBrowseTreeService.formatSystemName(sys));
            }
            if (task.getRepositoryId() != null) {
                CodeRepository repo = repoCache.computeIfAbsent(task.getRepositoryId(), id -> repositoryMapper.selectById(id));
                item.setRepositoryName(KnowledgeBrowseTreeService.formatRepositoryName(repo));
            }
        }
    }

    /**
     * 加载 task 的草稿条目，转成 KnowledgeBrowseItem。
     */
    private List<KnowledgeBrowseItem> loadDraftItems(List<Long> taskIds, KnowledgeBrowseQuery query,
                                                     Map<Long, DecompileTask> taskById) {
        // 1. workspace.taskId IN (...) → workspaceId list
        List<DraftWorkspace> workspaces = workspaceMapper.selectList(
                new LambdaQueryWrapper<DraftWorkspace>().in(DraftWorkspace::getTaskId, taskIds));
        if (workspaces.isEmpty()) return Collections.emptyList();
        List<Long> workspaceIds = workspaces.stream().map(DraftWorkspace::getId).collect(Collectors.toList());

        // 2. draft.workspace_id IN (...) 按 query.taskId 收窄
        LambdaQueryWrapper<KnowledgeDraft> qw = new LambdaQueryWrapper<>();
        qw.in(KnowledgeDraft::getWorkspaceId, workspaceIds);
        if (query.getTaskId() != null) {
            // 通过 workspace.task_id = query.taskId 二次过滤
            List<Long> narrowedWsIds = workspaces.stream()
                    .filter(w -> query.getTaskId().equals(w.getTaskId()))
                    .map(DraftWorkspace::getId).collect(Collectors.toList());
            if (narrowedWsIds.isEmpty()) return Collections.emptyList();
            qw.in(KnowledgeDraft::getWorkspaceId, narrowedWsIds);
        }
        qw.orderByDesc(KnowledgeDraft::getUpdatedDate);
        List<KnowledgeDraft> drafts = draftMapper.selectList(qw);
        if (drafts.isEmpty()) return Collections.emptyList();

        // 3. workspace.taskId → task 简单映射（用于关联 KnowledgeVersion）
        Map<Long, Long> wsIdToTaskId = new HashMap<>();
        for (DraftWorkspace w : workspaces) wsIdToTaskId.put(w.getId(), w.getTaskId());

        // 4. taskId → KnowledgeVersion（最新 CONFIRMED/PUSHED 的版本）
        Map<Long, KnowledgeVersion> taskIdToVersion = loadLatestVersionsForTasks(taskIds);

        List<KnowledgeBrowseItem> out = new ArrayList<>(drafts.size());
        for (KnowledgeDraft d : drafts) {
            Long tid = wsIdToTaskId.get(d.getWorkspaceId());
            KnowledgeBrowseItem item = new KnowledgeBrowseItem();
            item.setId(TYPE_DRAFT.toLowerCase(Locale.ROOT) + ":" + d.getId());
            item.setName(d.getFilePath() == null ? null : basename(d.getFilePath()));
            item.setType(TYPE_DRAFT);
            item.setTaskId(tid);
            item.setVersionId(null);  // 暂不强绑定（多版本场景下 draft 可能与多版本相关）
            item.setVersionNum(null);
            item.setFilePath(d.getFilePath());
            item.setSize(safeFileSize(d.getContentUri()));
            item.setStatus(d.getStatus());
            item.setUpdatedDate(formatDate(d.getUpdatedDate()));
            item.setSource(SOURCE_DB);
            out.add(item);
        }
        return out;
    }

    /**
     * 加载 systemId 下每个 task 的索引/清单条目，转成 KnowledgeBrowseItem。
     */
    private List<KnowledgeBrowseItem> loadIndexItems(List<Long> taskIds, KnowledgeBrowseQuery query, String typeFilter,
                                                     Map<Long, DecompileTask> taskById) {
        List<Long> scopedTaskIds = query.getTaskId() != null
                ? taskIds.stream().filter(t -> t.equals(query.getTaskId())).collect(Collectors.toList())
                : taskIds;
        if (scopedTaskIds.isEmpty()) return Collections.emptyList();

        Map<Long, KnowledgeVersion> taskIdToVersion = loadLatestVersionsForTasks(scopedTaskIds);
        List<KnowledgeBrowseItem> out = new ArrayList<>();
        for (Long tid : scopedTaskIds) {
            List<KnowledgeBrowseSource.IndexFileEntry> entries;
            try {
                entries = browseSource.listIndexFiles(tid);
            } catch (Exception e) {
                log.warn("listIndexFiles 失败 taskId={} — {}", tid, e.getMessage());
                continue;
            }
            for (KnowledgeBrowseSource.IndexFileEntry e : entries) {
                if (!typeMatches(e.type(), typeFilter)) continue;
                KnowledgeBrowseItem item = new KnowledgeBrowseItem();
                item.setId(e.type().toLowerCase(Locale.ROOT) + ":" + tid + ":" + e.relativePath());
                item.setName(basename(e.relativePath()));
                item.setType(e.type());
                item.setTaskId(tid);
                KnowledgeVersion v = taskIdToVersion.get(tid);
                if (v != null) {
                    item.setVersionId(v.getId());
                    item.setVersionNum(v.getVersionNum());
                }
                item.setFilePath(e.relativePath());
                item.setSize(e.size());
                item.setStatus(STATUS_GENERATED);
                item.setUpdatedDate(formatDate(e.updatedDate()));
                item.setSource(SOURCE_TEMP_REPOS);
                out.add(item);
            }
        }
        return out;
    }

    /**
     * 给定 taskIds，返回 taskId → 该 task 最新一条 CONFIRMED/PUSHED 状态的 KnowledgeVersion（若无则 null）。
     */
    private Map<Long, KnowledgeVersion> loadLatestVersionsForTasks(List<Long> taskIds) {
        Map<Long, KnowledgeVersion> out = new HashMap<>();
        if (taskIds == null || taskIds.isEmpty()) return out;
        List<KnowledgeVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeVersion>()
                        .in(KnowledgeVersion::getTaskId, taskIds)
                        .in(KnowledgeVersion::getStatus, "CONFIRMED", "PUSHED", "PUSHING")
                        .orderByDesc(KnowledgeVersion::getId));
        // 每 task 只保留一条（第一条即最新）
        for (KnowledgeVersion v : versions) {
            if (!out.containsKey(v.getTaskId())) {
                out.put(v.getTaskId(), v);
            }
        }
        return out;
    }

    /**
     * 在后端再过滤一次 keyword / status / taskId / versionId / 时间区间。
     * 注意：DRAFT 行已经按 query.taskId 在 SQL 层过滤过；index/manifest 行也已在 loadIndexItems 按 taskId 收窄。
     * 这里补 keyword / status / 时间区间 / versionId 的过滤。
     */
    private List<KnowledgeBrowseItem> applyClientSideFilters(List<KnowledgeBrowseItem> items, KnowledgeBrowseQuery query) {
        if (items.isEmpty()) return items;
        String keyword = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim().toLowerCase(Locale.ROOT) : null;
        String status = StringUtils.hasText(query.getStatus()) ? query.getStatus().toUpperCase(Locale.ROOT) : null;
        LocalDateTime from = parseDate(query.getCreatedDateStart());
        LocalDateTime to = parseDate(query.getCreatedDateEnd());

        return items.stream().filter(it -> {
            // keyword 匹配文件名（不区分大小写）
            if (keyword != null) {
                String name = it.getName() == null ? "" : it.getName().toLowerCase(Locale.ROOT);
                String path = it.getFilePath() == null ? "" : it.getFilePath().toLowerCase(Locale.ROOT);
                String sys = it.getSystemName() == null ? "" : it.getSystemName().toLowerCase(Locale.ROOT);
                String repo = it.getRepositoryName() == null ? "" : it.getRepositoryName().toLowerCase(Locale.ROOT);
                if (!name.contains(keyword) && !path.contains(keyword)
                        && !sys.contains(keyword) && !repo.contains(keyword)) {
                    return false;
                }
            }
            // status 仅对 DRAFT 生效；其它类型固定为 GENERATED
            if (status != null) {
                if (TYPE_DRAFT.equals(it.getType())) {
                    if (!status.equalsIgnoreCase(it.getStatus() == null ? "" : it.getStatus())) {
                        return false;
                    }
                }
                // INDEX / MANIFEST 状态下不应用 status 过滤
            }
            // versionId 仅对 DRAFT 生效
            if (query.getVersionId() != null) {
                if (TYPE_DRAFT.equals(it.getType()) && !query.getVersionId().equals(it.getVersionId())) {
                    return false;
                }
            }
            // 时间区间（updatedDate）
            if (from != null || to != null) {
                LocalDateTime u = parseDate(it.getUpdatedDate());
                if (u == null) return false;
                if (from != null && u.isBefore(from)) return false;
                if (to != null && u.isAfter(to)) return false;
            }
            return true;
        }).collect(Collectors.toList());
    }

    private boolean typeMatches(String entryType, String typeFilter) {
        if (typeFilter == null || TYPE_ALL.equals(typeFilter)) return true;
        return typeFilter.equals(entryType);
    }

    /**
     * 读草稿内容（复用 DraftService.getDraftContent）。
     */
    private String readDraftContent(KnowledgeBrowseContentRequest req) {
        if (req.getId() == null) {
            throw new BusinessException("草稿 id 不能为空");
        }
        return draftService.getDraftContent(req.getId());
    }

    // ============================ low-level helpers ============================

    private String basename(String path) {
        if (path == null) return null;
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private Long safeFileSize(String contentUri) {
        if (!StringUtils.hasText(contentUri)) return null;
        try {
            Path p = DraftFileUtil.resolve(contentUri, storageResolver);
            File f = p.toFile();
            return f.exists() ? Files.size(p) : 0L;
        } catch (Exception e) {
            log.debug("safeFileSize 无法读取 {}：{}", contentUri, e.getMessage());
            return 0L;
        }
    }

    private String formatDate(LocalDateTime dt) {
        return dt == null ? null : dt.format(ISO_FORMATTER);
    }

    private LocalDateTime parseDate(String s) {
        if (!StringUtils.hasText(s)) return null;
        try {
            return LocalDateTime.parse(s, ISO_FORMATTER);
        } catch (Exception e) {
            try {
                // 兼容 "yyyy-MM-dd" 形式
                return LocalDateTime.parse(s + "T00:00:00");
            } catch (Exception e2) {
                log.warn("parseDate 失败：{}", s);
                return null;
            }
        }
    }
}