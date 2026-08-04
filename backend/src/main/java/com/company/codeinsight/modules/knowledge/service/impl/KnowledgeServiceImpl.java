package com.company.codeinsight.modules.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.common.util.DraftFileUtil;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.knowledge.service.KnowledgeService;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 知识推送与版本管理服务实现类
 * 负责抓取已确认通过的草稿文档，自动在本地代码目录中构建并生成 Markdown 文档目录树结构
 * （如：整体架构、模块索引、API路由、数据库依赖和待确认事项等），并协调 JGit 进行版本提交和推送。
 */
@Slf4j
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    @Autowired
    private KnowledgeVersionMapper versionMapper;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired
    private CodeRepositoryMapper repositoryMapper;

    @Autowired
    private DraftWorkspaceMapper workspaceMapper;

    @Autowired
    private KnowledgeDraftMapper draftMapper;

    @Autowired
    private com.company.codeinsight.modules.knowledge.service.KnowledgeIndexService knowledgeIndexService;

    private static final Pattern SIMPLE_VERSION = Pattern.compile("^v(\\d+)$");

    @Autowired
    private com.company.codeinsight.modules.hierarchy.service.ModuleHierarchyService moduleHierarchyService;

    @Autowired
    private TaskWorkspacePaths taskWorkspacePaths;

    /**
     * 草稿正文存储根目录，与 ci_knowledge_draft.content_uri 经 EnvStorageResolver 解析。
     */
    @Autowired
    private com.company.codeinsight.common.storage.EnvStorageResolver storageResolver;

    @Autowired
    private com.company.codeinsight.modules.draft.service.DraftService draftService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void assemblePublishPackage(Long taskId) {
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("未找到知识构建任务");
        }
        List<KnowledgeDraft> drafts = loadConfirmedDrafts(taskId);
        Path docsPath = taskWorkspacePaths.taskDocsCodeInsight(taskId);
        Path modulesPath = docsPath.resolve("modules");
        Path metaPath = docsPath.resolve("meta");
        try {
            Files.createDirectories(modulesPath);
            Files.createDirectories(metaPath);

            StringBuilder indexBuilder = new StringBuilder();
            indexBuilder.append("# 代码洞察平台知识索引\n\n");
            indexBuilder.append("本目录包含系统代码解析生成的完整架构和模块说明文档。\n\n");
            indexBuilder.append("## 文档导览\n");
            indexBuilder.append("- [架构概览](architecture-overview.md)\n");
            indexBuilder.append("- [模块索引](module-index.md)\n");
            indexBuilder.append("- [知识文档索引](meta/document-index.md)\n");
            indexBuilder.append("- [待确认事项清单](pending-confirmation.md)\n");

            StringBuilder yamlBuilder = new StringBuilder();
            yamlBuilder.append("modules:\n");

            for (KnowledgeDraft draft : drafts) {
                File draftFile = DraftFileUtil.resolve(draft.getContentUri(), storageResolver).toFile();
                Path staged = modulesPath.resolve(
                        KnowledgeIndexServiceImpl.flattenKnowledgeDocFileName(draft.getModuleName()));
                String content;
                if (draftFile.exists()) {
                    content = Files.readString(draftFile.toPath());
                } else if (Files.exists(staged)) {
                    content = Files.readString(staged);
                } else {
                    content = "# " + draft.getModuleName();
                }
                Files.writeString(staged, content);
                yamlBuilder.append("  - name: \"").append(escapeYamlDoubleQuoted(draft.getModuleName())).append("\"\n");
                yamlBuilder.append("    path: \"docs/code-insight/modules/")
                        .append(KnowledgeIndexServiceImpl.flattenKnowledgeDocFileName(draft.getModuleName()))
                        .append("\"\n");
                if (draft.getGeneratedAt() != null) {
                    yamlBuilder.append("    generatedAt: \"")
                            .append(draft.getGeneratedAt().toString())
                            .append("\"\n");
                }
            }

            try {
                var hierarchy = moduleHierarchyService.loadByTaskId(taskId);
                knowledgeIndexService.generateModuleIndex(docsPath, hierarchy, drafts);
                knowledgeIndexService.generateDocumentIndex(docsPath, hierarchy, drafts);
            } catch (Exception e) {
                log.warn("KnowledgeIndexService 调用失败，写 fallback: {}", e.getMessage());
                writeFallbackModuleIndex(docsPath, drafts);
                try {
                    knowledgeIndexService.generateDocumentIndex(docsPath, null, drafts);
                } catch (Exception ex) {
                    log.warn("fallback document-index.md 生成失败: {}", ex.getMessage());
                }
            }

            StringBuilder archBuilder = new StringBuilder();
            archBuilder.append("# 系统整体架构设计说明\n\n");
            archBuilder.append("## 系统划分\n");
            archBuilder.append("目前平台将代码仓库划分为 ").append(drafts.size()).append(" 个主要业务模块：\n\n");
            for (KnowledgeDraft d : drafts) {
                archBuilder.append("- **").append(d.getModuleName()).append("**: ").append(d.getFilePath()).append("\n");
            }

            StringBuilder pcBuilder = new StringBuilder();
            pcBuilder.append("# 待确认事项汇总清单\n\n");
            boolean hasPc = false;
            for (KnowledgeDraft draft : drafts) {
                Path moduleFile = modulesPath.resolve(
                        KnowledgeIndexServiceImpl.flattenKnowledgeDocFileName(draft.getModuleName()));
                if (!Files.exists(moduleFile)) {
                    continue;
                }
                for (String line : Files.readAllLines(moduleFile)) {
                    if (line.trim().startsWith("- [ ]")) {
                        pcBuilder.append("- 模块 `").append(draft.getModuleName()).append("`: ")
                                .append(line.trim().substring(5).trim()).append("\n");
                        hasPc = true;
                    }
                }
            }
            if (!hasPc) {
                pcBuilder.append("恭喜，目前系统内无待确认的阻断事项！\n");
            }

            Files.writeString(docsPath.resolve("index.md"), indexBuilder.toString());
            Files.writeString(docsPath.resolve("architecture-overview.md"), archBuilder.toString());
            Files.writeString(docsPath.resolve("pending-confirmation.md"), pcBuilder.toString());
            Files.writeString(metaPath.resolve("module-map.yaml"), yamlBuilder.toString());
            log.info("assemblePublishPackage 完成 taskId={} modules={}", taskId, drafts.size());
        } catch (IOException e) {
            log.error("组装发布包失败 taskId={}", taskId, e);
            throw new BusinessException("组装发布包失败: " + e.getMessage());
        }
    }

    @Override
    public String nextSimpleVersionNum(Long repositoryId) {
        List<KnowledgeVersion> existing = versionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeVersion>()
                        .eq(KnowledgeVersion::getRepositoryId, repositoryId)
                        .select(KnowledgeVersion::getVersionNum));
        int max = 0;
        for (KnowledgeVersion v : existing) {
            if (v.getVersionNum() == null) {
                continue;
            }
            Matcher m = SIMPLE_VERSION.matcher(v.getVersionNum().trim());
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        int candidate = max + 1;
        while (true) {
            String num = "v" + candidate;
            Long count = versionMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeVersion>()
                            .eq(KnowledgeVersion::getRepositoryId, repositoryId)
                            .eq(KnowledgeVersion::getVersionNum, num));
            if (count == null || count == 0) {
                return num;
            }
            candidate++;
        }
    }

    /**
     * 生成知识发布版本：依赖已组装的 docs/code-insight；若 modules 为空则先 assemble。
     */
    @Override
    @Transactional
    public KnowledgeVersion createVersion(Long taskId, String versionNum, String confirmedBy) {
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("未找到知识构建任务");
        }
        if (!StringUtils.hasText(task.getSourceCommit())) {
            throw new BusinessException("任务尚未完成代码拉取，缺少 source_commit，无法生成知识版本");
        }
        draftService.assertTaskReadyForKnowledgePublish(taskId);

        CodeRepository repo = repositoryMapper.selectById(task.getRepositoryId());
        if (repo == null) {
            throw new BusinessException("未找到关联的代码库配置");
        }

        String normalizedVersionNum = requireUniqueVersionNum(task.getRepositoryId(), versionNum);
        Path docsPath = taskWorkspacePaths.taskDocsCodeInsight(taskId);
        Path modulesPath = docsPath.resolve("modules");
        if (!Files.isDirectory(modulesPath) || isDirEmpty(modulesPath)) {
            assemblePublishPackage(taskId);
        }
        if (!Files.isDirectory(modulesPath) || isDirEmpty(modulesPath)) {
            throw new BusinessException("发布包 modules 为空，无法创建版本");
        }

        try {
            Path metaPath = docsPath.resolve("meta");
            Files.createDirectories(metaPath);
            ObjectNode versionJson = objectMapper.createObjectNode();
            versionJson.put("version", normalizedVersionNum);
            versionJson.put("systemId", task.getSystemId());
            versionJson.put("commitId", task.getSourceCommit());
            versionJson.put("generatedAt", LocalDateTime.now().toString());
            versionJson.put("confirmedBy", confirmedBy);
            Files.writeString(metaPath.resolve("knowledge-version.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(versionJson));

            ObjectNode promptJson = objectMapper.createObjectNode();
            promptJson.put("taskId", taskId);
            promptJson.putNull("promptVersion");
            promptJson.put("modelName", task.getModelName() != null ? task.getModelName() : "default");
            promptJson.put("appliedAt", LocalDateTime.now().toString());
            Files.writeString(metaPath.resolve("prompt-used.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(promptJson));
        } catch (IOException e) {
            throw new BusinessException("写入版本元数据失败: " + e.getMessage());
        }

        // target_branch 表字段 NOT NULL；NAS 建版时尚无独立推送分支，先与源分支对齐
        String branch = StringUtils.hasText(repo.getBranch()) ? repo.getBranch() : "master";

        KnowledgeVersion version = new KnowledgeVersion();
        version.setSystemId(task.getSystemId());
        version.setRepositoryId(task.getRepositoryId());
        version.setTaskId(taskId);
        version.setVersionNum(normalizedVersionNum);
        version.setSourceBranch(branch);
        version.setSourceCommit(task.getSourceCommit());
        version.setTargetBranch(branch);
        version.setTargetCommit(null);
        version.setPromptVersion(null);
        version.setModelName(task.getModelName());
        version.setStatus("DRAFT");
        version.setPushMethod("NAS");
        version.setConfirmedBy(confirmedBy);
        version.setConfirmedAt(LocalDateTime.now());
        version.setCreatedDate(LocalDateTime.now());

        versionMapper.insert(version);
        return version;
    }

    private List<KnowledgeDraft> loadConfirmedDrafts(Long taskId) {
        DraftWorkspace ws = workspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId));
        if (ws == null) {
            throw new BusinessException("草稿工作区不存在");
        }
        List<KnowledgeDraft> drafts = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .eq(KnowledgeDraft::getWorkspaceId, ws.getId())
                        .in(KnowledgeDraft::getStatus, DraftStatus.CONFIRMED.name(), DraftStatus.PUSHED.name()));
        if (drafts.isEmpty()) {
            throw new BusinessException("工作区内没有已确认（CONFIRMED/PUSHED）的草稿，无法组装发布包");
        }
        return drafts;
    }

    private static boolean isDirEmpty(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * 导出为 ZIP 压缩包二进制流。
     * <p>优先打包 NAS {@code releases/{sys}/{repo}/{ver}}（推送成功后工作区已清理）；
     * 若尚无 release，则回退任务工作区 {@code docs/code-insight}。</p>
     */
    @Override
    public byte[] exportZip(Long versionId) {
        KnowledgeVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("知识版本不存在");
        }

        File docsDir = resolveExportSourceDir(version);
        if (docsDir == null || !docsDir.isDirectory()) {
            throw new BusinessException("知识库目录不存在，无法导出 ZIP（请确认版本已创建或已推送至 NAS）");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zipDirectory(docsDir, docsDir, zos);
        } catch (IOException e) {
            log.error("打包 ZIP 失败", e);
            throw new BusinessException("导出打包 ZIP 失败");
        }
        return baos.toByteArray();
    }

    /**
     * ZIP 打包源目录：releases 优先，否则 workspace docs/code-insight。
     */
    private File resolveExportSourceDir(KnowledgeVersion version) {
        if (version.getSystemId() != null && version.getRepositoryId() != null
                && StringUtils.hasText(version.getVersionNum())) {
            Path releaseDir = storageResolver.releaseDir(
                    version.getSystemId(), version.getRepositoryId(), version.getVersionNum());
            if (Files.isDirectory(releaseDir)) {
                return releaseDir.toFile();
            }
        }
        File workspaceDocs = new File(
                taskWorkspacePaths.taskProjectDir(version.getTaskId()), "docs/code-insight");
        if (workspaceDocs.isDirectory()) {
            return workspaceDocs;
        }
        return null;
    }

    /**
     * 分页多条件查询已发布的版本记录
     */
    @Override
    public Page<KnowledgeVersion> listVersionsPage(int current, int size, Long systemId, Long repositoryId) {
        Page<KnowledgeVersion> page = new Page<>(current, size);
        LambdaQueryWrapper<KnowledgeVersion> qw = new LambdaQueryWrapper<>();
        qw.eq(systemId != null, KnowledgeVersion::getSystemId, systemId)
          .eq(repositoryId != null, KnowledgeVersion::getRepositoryId, repositoryId)
          .orderByDesc(KnowledgeVersion::getCreatedDate);
        Page<KnowledgeVersion> result = versionMapper.selectPage(page, qw);
        enrichActivePublished(result.getRecords());
        return result;
    }

    private void enrichActivePublished(List<KnowledgeVersion> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Map<Long, Long> activeVersionByRepo = new HashMap<>();
        for (KnowledgeVersion record : records) {
            Long repoId = record.getRepositoryId();
            if (repoId == null || activeVersionByRepo.containsKey(repoId)) {
                continue;
            }
            CodeRepository repo = repositoryMapper.selectById(repoId);
            activeVersionByRepo.put(repoId, repo != null ? repo.getLastPublishedVersionId() : null);
        }
        for (KnowledgeVersion record : records) {
            Long activeVersionId = activeVersionByRepo.get(record.getRepositoryId());
            record.setActivePublished(activeVersionId != null && activeVersionId.equals(record.getId()));
        }
    }

    private String requireUniqueVersionNum(Long repositoryId, String versionNum) {
        if (!StringUtils.hasText(versionNum)) {
            throw new BusinessException("版本号不能为空");
        }
        String normalized = versionNum.trim();
        Long existing = versionMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeVersion>()
                        .eq(KnowledgeVersion::getRepositoryId, repositoryId)
                        .eq(KnowledgeVersion::getVersionNum, normalized));
        if (existing != null && existing > 0) {
            throw new BusinessException("该仓库已存在版本号 " + normalized + "，请使用不同的 versionNum");
        }
        return normalized;
    }

    /** module-map.yaml 双引号值转义（模块名偶含引号） */
    private static String escapeYamlDoubleQuoted(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 递归遍历打包压缩目录辅助方法
     */
    private void zipDirectory(File baseDir, File currentDir, ZipOutputStream zos) throws IOException {
        File[] files = currentDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                zipDirectory(baseDir, file, zos);
            } else {
                String entryName = baseDir.toURI().relativize(file.toURI()).getPath();
                ZipEntry entry = new ZipEntry("code-insight/" + entryName);
                zos.putNextEntry(entry);
                Files.copy(file.toPath(), zos);
                zos.closeEntry();
            }
        }
    }

    /**
     * 项 4 KnowledgeIndexService 失败时的降级：写原版两行列表 module-index.md
     * 保持与旧版本行为兼容，避免推送阶段因索引异常阻塞
     */
    private void writeFallbackModuleIndex(Path docsPath, List<KnowledgeDraft> drafts) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 模块知识归纳索引\n\n");
        sb.append("本知识库由代码洞察平台基于大模型及静态解析自动归纳生成。\n\n");
        sb.append("## 系统模块列表\n");
        for (KnowledgeDraft draft : drafts) {
            String cleanFileName = KnowledgeIndexServiceImpl.flattenKnowledgeDocFileName(draft.getModuleName());
            sb.append("- [").append(draft.getModuleName()).append("](modules/").append(cleanFileName).append(")\n");
        }
        Files.writeString(docsPath.resolve("module-index.md"), sb.toString());
    }
}
