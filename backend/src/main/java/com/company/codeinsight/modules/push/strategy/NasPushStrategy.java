package com.company.codeinsight.modules.push.strategy;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.common.util.DraftFileUtil;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.knowledge.service.KnowledgeIndexService;
import com.company.codeinsight.modules.push.entity.PushTask;
import com.company.codeinsight.modules.push.enums.PushMethod;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * NAS 文件系统推送策略：将知识文件从任务工作区复制到 NAS releases 目录。
 * <p>目标路径：{releasesRoot}/{sysId}/{repoId}/{versionNum}/</p>
 * <p>推送成功后，根据配置决定是否清理 drafts 下的源文件。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NasPushStrategy implements PushStrategy {

    private final DecompileTaskMapper taskMapper;
    private final DraftWorkspaceMapper workspaceMapper;
    private final KnowledgeDraftMapper draftMapper;
    private final KnowledgeIndexService knowledgeIndexService;
    private final EnvStorageResolver storageResolver;
    private final TaskWorkspacePaths taskWorkspacePaths;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String execute(KnowledgeVersion version, PushTask pushTask) {
        DecompileTask task = taskMapper.selectById(version.getTaskId());
        if (task == null) throw new BusinessException("版本关联的任务不存在");

        Long sysId = task.getSystemId();
        Long repoId = task.getRepositoryId();
        Path releaseDir = storageResolver.releaseDir(sysId, repoId, version.getVersionNum());
        Path modulesDir = releaseDir.resolve("modules");
        Path indexDir = releaseDir.resolve("index");
        Path metaDir = releaseDir.resolve("meta");

        try {
            Files.createDirectories(modulesDir);
            Files.createDirectories(indexDir);
            Files.createDirectories(metaDir);

            // 1. 从 drafting workspace 拷贝草稿文件到 modules/
            List<DraftWorkspace> workspaces = workspaceMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DraftWorkspace>()
                            .eq(DraftWorkspace::getTaskId, version.getTaskId()));
            List<KnowledgeDraft> drafts = new java.util.ArrayList<>();
            for (DraftWorkspace w : workspaces) {
                drafts.addAll(draftMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeDraft>()
                                .eq(KnowledgeDraft::getWorkspaceId, w.getId())));
            }
            for (KnowledgeDraft d : drafts) {
                Path src = DraftFileUtil.resolve(d.getContentUri(), storageResolver);
                if (Files.exists(src)) {
                    String safe = com.company.codeinsight.modules.knowledge.service.impl.KnowledgeIndexServiceImpl
                            .flattenKnowledgeDocFileName(d.getModuleName());
                    Files.copy(src, modulesDir.resolve(safe), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }

            // 2. 从 workspace 拷贝索引文件（如果存在）
            Path wsDocs = taskWorkspacePaths.taskDocsCodeInsight(version.getTaskId());
            if (Files.isDirectory(wsDocs.resolve("index"))) {
                copyDir(wsDocs.resolve("index"), indexDir);
            }
            if (Files.isDirectory(wsDocs.resolve("modules"))) {
                copyDir(wsDocs.resolve("modules"), modulesDir);
            }
            if (Files.isDirectory(wsDocs.resolve("meta"))) {
                copyDir(wsDocs.resolve("meta"), metaDir);
            }

            // 3. 写 knowledge-version.json
            var vj = new java.util.LinkedHashMap<String, Object>();
            vj.put("versionNum", version.getVersionNum());
            vj.put("systemId", sysId);
            vj.put("repositoryId", repoId);
            vj.put("pushedAt", LocalDateTime.now().toString());
            vj.put("documentCount", drafts.size());
            Files.writeString(metaDir.resolve("knowledge-version.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(vj));

            String resultPath = releaseDir.toAbsolutePath().normalize().toString();
            log.info("NAS push success: versionId={} path={} drafts={}", version.getId(), resultPath, drafts.size());

            // 4. 推送后清理 drafts 源文件（可选：通过 storage.releases-root 已写入即安全）
            for (KnowledgeDraft d : drafts) {
                Path src = DraftFileUtil.resolve(d.getContentUri(), storageResolver);
                try { Files.deleteIfExists(src); } catch (Exception e) { log.debug("cleanup draft file {}: {}", src, e.getMessage()); }
            }

            return resultPath;
        } catch (Exception e) {
            log.error("NAS push failed versionId={}", version.getId(), e);
            throw new BusinessException("NAS 推送失败：" + e.getMessage());
        }
    }

    @Override
    public PushMethod getMethod() {
        return PushMethod.NAS;
    }

    private void copyDir(Path src, Path dst) throws java.io.IOException {
        Files.walk(src).forEach(s -> {
            Path d = dst.resolve(src.relativize(s));
            try {
                if (Files.isDirectory(s)) {
                    Files.createDirectories(d);
                } else {
                    Files.copy(s, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                log.warn("copyDir: {} → {} failed: {}", s, d, e.getMessage());
            }
        });
    }
}
