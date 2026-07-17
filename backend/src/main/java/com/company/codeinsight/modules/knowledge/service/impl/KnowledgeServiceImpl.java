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
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.draft.entity.DraftSourceReference;
import com.company.codeinsight.modules.draft.mapper.DraftSourceReferenceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private JavaParserService javaParserService;

    @Autowired
    private DraftSourceReferenceMapper draftSourceReferenceMapper;

    @Autowired
    private com.company.codeinsight.modules.knowledge.service.KnowledgeIndexService knowledgeIndexService;

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

    /**
     * 生成知识发布版本
     * 严格语义：仅提取工作区内所有 CONFIRMED（已确认）状态的草稿，
     * 在本地临时 Git 库下生成结构化文档索引并提交版本。
     * 处于 PENDING_REVIEW / REVISED / AI_GENERATED / REJECTED 等状态的草稿不会进入版本。
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

        DraftWorkspace ws = workspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId)
        );
        if (ws == null) {
            throw new BusinessException("草稿工作区不存在");
        }

        // 严格语义：知识库只收录"人审核通过"的草稿（CONFIRMED 或 PUSHED）。
        // DRAFT / EDITING（未确认）/ ARCHIVED（已归档）不直接进版本。
        List<KnowledgeDraft> drafts = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .eq(KnowledgeDraft::getWorkspaceId, ws.getId())
                        .in(KnowledgeDraft::getStatus, DraftStatus.CONFIRMED.name(), DraftStatus.PUSHED.name())
        );

        if (drafts.isEmpty()) {
            throw new BusinessException("工作区内没有已确认（CONFIRMED/PUSHED）的草稿，无法生成知识版本");
        }

        // 定位并创建临时 Git 克隆工程目录中的 docs 文件夹
        File taskRepoDir = taskWorkspacePaths.taskProjectDir(taskId);
        if (!taskRepoDir.exists()) {
            taskRepoDir.mkdirs(); 
        }

        Path docsPath = taskRepoDir.toPath().resolve("docs/code-insight");
        Path modulesPath = docsPath.resolve("modules");
        Path metaPath = docsPath.resolve("meta");

        try {
            Files.createDirectories(modulesPath);
            Files.createDirectories(metaPath);

            // 1. 构建主索引（index.md）
            StringBuilder indexBuilder = new StringBuilder();
            indexBuilder.append("# 代码洞察平台知识索引\n\n");
            indexBuilder.append("本目录包含系统代码解析生成的完整架构和模块说明文档。\n\n");
            indexBuilder.append("## 文档导览\n");
            indexBuilder.append("- [架构概览](architecture-overview.md)\n");
            indexBuilder.append("- [模块索引](module-index.md)\n");
            indexBuilder.append("- [接口索引](api-index.md)\n");
            indexBuilder.append("- [数据库索引](database-index.md)\n");
            indexBuilder.append("- [依赖与调用链路](dependency-index.md)\n");
            indexBuilder.append("- [待确认事项清单](pending-confirmation.md)\n");

            // 2. 构建模块目录索引（module-index.md）—— 项 4 升级为三级表格
            StringBuilder yamlBuilder = new StringBuilder();
            yamlBuilder.append("modules:\n");

            // 拷贝各个草稿的物理 Markdown 到发布目录下
            for (KnowledgeDraft draft : drafts) {
                File draftFile = DraftFileUtil.resolve(draft.getContentUri(), storageResolver).toFile();
                String content = draftFile.exists() ? Files.readString(draftFile.toPath()) : "# " + draft.getModuleName();

                String cleanFileName = draft.getModuleName().replaceAll("[\\s/\\(\\)]", "_") + ".md";
                Files.writeString(modulesPath.resolve(cleanFileName), content);

                yamlBuilder.append("  - name: \"").append(draft.getModuleName()).append("\"\n");
                yamlBuilder.append("    path: \"docs/code-insight/modules/").append(cleanFileName).append("\"\n");
            }

            // 加载 ModuleHierarchy DTO（项 2 产出），由 KnowledgeIndexService 生成三级索引
            try {
                com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy =
                        moduleHierarchyService.loadByTaskId(taskId);
                knowledgeIndexService.generateModuleIndex(docsPath, hierarchy, drafts);
                log.info("项 4 三级索引 module-index.md 生成完成（taskId={}）", taskId);
            } catch (Exception e) {
                // 索引生成失败不能影响主流程，降级为旧版两行列表（这里直接保留 fallback）
                log.warn("KnowledgeIndexService 调用失败，写 fallback module-index.md: {}", e.getMessage());
                writeFallbackModuleIndex(docsPath, drafts);
            }

            // 3. 构建架构整体设计大纲（architecture-overview.md）
            StringBuilder archBuilder = new StringBuilder();
            archBuilder.append("# 系统整体架构设计说明\n\n");
            archBuilder.append("## 系统划分\n");
            archBuilder.append("目前平台将代码仓库划分为 ").append(drafts.size()).append(" 个主要业务模块：\n\n");
            for (KnowledgeDraft d : drafts) {
                archBuilder.append("- **").append(d.getModuleName()).append("**: ").append(d.getFilePath()).append("\n");
            }
            archBuilder.append("\n## 技术决策与原则\n1. 前后端分离设计\n2. 引入 Token 审计拦截与操作追踪日志\n");

            // 4. 构建前端子系统说明文档
            StringBuilder feBuilder = new StringBuilder();
            feBuilder.append("# 前端子系统架构设计 (React)\n\n");
            feBuilder.append("## 核心组件与库\n- 核心框架: React 18\n- 构建工具: Vite\n- 状态库: Zustand\n- 组件库: Ant Design 5\n");

            // 5. 构建后端服务说明文档
            StringBuilder beBuilder = new StringBuilder();
            beBuilder.append("# 后端服务系统架构设计 (Java 17)\n\n");
            beBuilder.append("## 核心框架\n- 基础框架: Spring Boot 3.x\n- 持久层: MyBatis-Plus / PostgreSQL\n- 缓存: Redis\n- 工作流: 任务状态机\n");

            // 6. 静态 AST 解析并自动合成系统路由接口说明（api-index.md）
            StringBuilder apiBuilder = new StringBuilder();
            apiBuilder.append("# 系统接口路由索引 (API Index)\n\n");
            apiBuilder.append("| 模块名称 | 接口 URL | HTTP 方法 | 实现方法 |\n");
            apiBuilder.append("| --- | --- | --- | --- |\n");
            boolean hasApi = false;
            for (KnowledgeDraft draft : drafts) {
                List<DraftSourceReference> refs = draftSourceReferenceMapper.selectList(
                        new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draft.getId())
                );
                for (DraftSourceReference ref : refs) {
                    if (ref.getFilePath().endsWith(".java")) {
                        Path classFile = taskWorkspacePaths.taskProjectPath(taskId).resolve(ref.getFilePath());
                        if (Files.exists(classFile)) {
                            try {
                                ParsedClassInfo info = javaParserService.parseFile(classFile.toFile());
                                if (info != null && !info.getMethods().isEmpty()) {
                                    for (ParsedClassInfo.MethodInfo m : info.getMethods()) {
                                        if (StringUtils.hasText(m.getRequestMapping())) {
                                            apiBuilder.append("| ").append(draft.getModuleName())
                                                    .append(" | `").append(m.getRequestMapping()).append("` | `")
                                                    .append(m.getHttpMethod() != null ? m.getHttpMethod() : "GET").append("` | `")
                                                    .append(m.getName()).append("` |\n");
                                            hasApi = true;
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            if (!hasApi) {
                apiBuilder.append("| 暂无 | 暂无 | 暂无 | 暂无 |\n");
            }

            // 7. 静态 AST 解析并自动合成数据库表操作说明（database-index.md）
            StringBuilder dbBuilder = new StringBuilder();
            dbBuilder.append("# 数据库操作与表关联索引 (Database Index)\n\n");
            dbBuilder.append("| 涉及数据表 | 模块名称 | 操作方法 |\n");
            dbBuilder.append("| --- | --- | --- |\n");
            boolean hasDb = false;
            for (KnowledgeDraft draft : drafts) {
                List<DraftSourceReference> refs = draftSourceReferenceMapper.selectList(
                        new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draft.getId())
                );
                for (DraftSourceReference ref : refs) {
                    if (ref.getFilePath().endsWith(".java")) {
                        Path classFile = taskWorkspacePaths.taskProjectPath(taskId).resolve(ref.getFilePath());
                        if (Files.exists(classFile)) {
                            try {
                                ParsedClassInfo info = javaParserService.parseFile(classFile.toFile());
                                if (info != null && info.getTables() != null && !info.getTables().isEmpty()) {
                                    for (String table : info.getTables()) {
                                        dbBuilder.append("| `").append(table).append("` | ")
                                                .append(draft.getModuleName()).append(" | ")
                                                .append(info.getType()).append(" 类操作 |\n");
                                        hasDb = true;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            if (!hasDb) {
                dbBuilder.append("| 暂无 | 暂无 | 暂无 |\n");
            }

            // 8. 静态 AST 解析并自动合成模块类依赖关系表（dependency-index.md）
            StringBuilder depBuilder = new StringBuilder();
            depBuilder.append("# 模块调用与类依赖链路 (Dependency Index)\n\n");
            depBuilder.append("| 涉及类名 | 模块名称 | 依赖的其他类 |\n");
            depBuilder.append("| --- | --- | --- |\n");
            boolean hasDep = false;
            for (KnowledgeDraft draft : drafts) {
                List<DraftSourceReference> refs = draftSourceReferenceMapper.selectList(
                        new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draft.getId())
                );
                for (DraftSourceReference ref : refs) {
                    if (ref.getFilePath().endsWith(".java")) {
                        Path classFile = taskWorkspacePaths.taskProjectPath(taskId).resolve(ref.getFilePath());
                        if (Files.exists(classFile)) {
                            try {
                                ParsedClassInfo info = javaParserService.parseFile(classFile.toFile());
                                if (info != null && info.getDependencies() != null && !info.getDependencies().isEmpty()) {
                                    depBuilder.append("| `").append(info.getClassName()).append("` | ")
                                            .append(draft.getModuleName()).append(" | `")
                                            .append(String.join(", ", info.getDependencies())).append("` |\n");
                                    hasDep = true;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            if (!hasDep) {
                depBuilder.append("| 暂无 | 暂无 | 暂无 |\n");
            }

            // 9. 扫描提取未处理的待确认事项汇总（pending-confirmation.md）
            StringBuilder pcBuilder = new StringBuilder();
            pcBuilder.append("# 待确认事项汇总清单\n\n");
            boolean hasPc = false;
            for (KnowledgeDraft draft : drafts) {
                File draftFile = DraftFileUtil.resolve(draft.getContentUri(), storageResolver).toFile();
                if (draftFile.exists()) {
                    List<String> lines = Files.readAllLines(draftFile.toPath());
                    for (String line : lines) {
                        if (line.trim().startsWith("- [ ]")) {
                            pcBuilder.append("- 模块 `").append(draft.getModuleName()).append("`: ").append(line.trim().substring(5).trim()).append("\n");
                            hasPc = true;
                        }
                    }
                }
            }
            if (!hasPc) {
                pcBuilder.append("恭喜，目前系统内无待确认的阻断事项！\n");
            }

            // 执行本地磁盘写入落盘
            Files.writeString(docsPath.resolve("index.md"), indexBuilder.toString());
            // module-index.md 已在前面 KnowledgeIndexService 调用时生成（项 4）
            Files.writeString(docsPath.resolve("architecture-overview.md"), archBuilder.toString());
            Files.writeString(docsPath.resolve("frontend-overview.md"), feBuilder.toString());
            Files.writeString(docsPath.resolve("backend-overview.md"), beBuilder.toString());
            Files.writeString(docsPath.resolve("api-index.md"), apiBuilder.toString());
            Files.writeString(docsPath.resolve("database-index.md"), dbBuilder.toString());
            Files.writeString(docsPath.resolve("dependency-index.md"), depBuilder.toString());
            Files.writeString(docsPath.resolve("pending-confirmation.md"), pcBuilder.toString());

            Files.writeString(metaPath.resolve("module-map.yaml"), yamlBuilder.toString());

            // 10. 保存发布版本控制及提示词使用历史数据至 meta 文件夹下做离线审计
            ObjectNode versionJson = objectMapper.createObjectNode();
            versionJson.put("version", normalizedVersionNum);
            versionJson.put("systemId", task.getSystemId());
            versionJson.put("commitId", task.getSourceCommit());
            versionJson.put("generatedAt", LocalDateTime.now().toString());
            versionJson.put("confirmedBy", confirmedBy);

            Files.writeString(metaPath.resolve("knowledge-version.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(versionJson));

            ObjectNode promptJson = objectMapper.createObjectNode();
            promptJson.put("taskId", taskId);
            promptJson.putNull("promptVersion");
            promptJson.put("modelName", "MiniMax-M3");
            promptJson.put("appliedAt", LocalDateTime.now().toString());
            Files.writeString(metaPath.resolve("prompt-used.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(promptJson));

        } catch (IOException e) {
            log.error("生成本地知识文件结构失败", e);
            throw new BusinessException("本地知识文件生成失败: " + e.getMessage());
        }

        // 保存知识发布版本实体记录，初始状态为 DRAFT (待推送)
        KnowledgeVersion version = new KnowledgeVersion();
        version.setSystemId(task.getSystemId());
        version.setRepositoryId(task.getRepositoryId());
        version.setTaskId(taskId);
        version.setVersionNum(normalizedVersionNum);
        version.setSourceBranch(repo.getBranch());
        version.setSourceCommit(task.getSourceCommit());
        version.setTargetBranch(
                org.springframework.util.StringUtils.hasText(repo.getPushBranch())
                        ? repo.getPushBranch()
                        : "docs-code-insight");
        version.setTargetCommit(null);
        version.setPromptVersion(null);
        version.setModelName("MiniMax-M3");
        version.setStatus("DRAFT");
        version.setPushMethod("GIT");
        version.setConfirmedBy(confirmedBy);
        version.setConfirmedAt(LocalDateTime.now());
        version.setCreatedDate(LocalDateTime.now());

        versionMapper.insert(version);
        return version;
    }

    /**
     * 导出为 ZIP 压缩包二进制流
     */
    @Override
    public byte[] exportZip(Long versionId) {
        KnowledgeVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("知识版本不存在");
        }

        File taskRepoDir = taskWorkspacePaths.taskProjectDir(version.getTaskId());
        File docsDir = new File(taskRepoDir, "docs/code-insight");
        if (!docsDir.exists()) {
            throw new BusinessException("知识库本地目录不存在，请先创建版本");
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
            String cleanFileName = draft.getModuleName().replaceAll("[\\s/\\(\\)]", "_") + ".md";
            sb.append("- [").append(draft.getModuleName()).append("](modules/").append(cleanFileName).append(")\n");
        }
        Files.writeString(docsPath.resolve("module-index.md"), sb.toString());
    }
}
