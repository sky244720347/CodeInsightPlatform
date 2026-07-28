package com.company.codeinsight.modules.push.strategy;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.push.entity.PushTask;
import com.company.codeinsight.modules.push.enums.PushMethod;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * NAS 文件系统推送策略：将 {@code docs/code-insight} 发布包复制到 releases。
 * <p>目标路径：{releasesRoot}/{sysId}/{repoId}/{versionNum}/</p>
 * <p>仅读取工作区 docs，不依赖 drafts。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NasPushStrategy implements PushStrategy {

    private final DecompileTaskMapper taskMapper;
    private final EnvStorageResolver storageResolver;
    private final TaskWorkspacePaths taskWorkspacePaths;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String execute(KnowledgeVersion version, PushTask pushTask) {
        DecompileTask task = taskMapper.selectById(version.getTaskId());
        if (task == null) {
            throw new BusinessException("版本关联的任务不存在");
        }

        Long sysId = task.getSystemId();
        Long repoId = task.getRepositoryId();
        Path releaseDir = storageResolver.releaseDir(sysId, repoId, version.getVersionNum());
        Path modulesDir = releaseDir.resolve("modules");
        Path indexDir = releaseDir.resolve("index");
        Path metaDir = releaseDir.resolve("meta");

        Path wsDocs = taskWorkspacePaths.taskDocsCodeInsight(version.getTaskId());
        if (!Files.isDirectory(wsDocs)) {
            throw new BusinessException("发布包不存在: " + wsDocs + "，请先完成知识确认组装");
        }
        Path srcModules = wsDocs.resolve("modules");
        if (!Files.isDirectory(srcModules)) {
            throw new BusinessException("发布包 modules 不存在: " + srcModules);
        }

        try {
            Files.createDirectories(modulesDir);
            Files.createDirectories(indexDir);
            Files.createDirectories(metaDir);

            copyDir(srcModules, modulesDir);
            if (Files.isDirectory(wsDocs.resolve("index"))) {
                copyDir(wsDocs.resolve("index"), indexDir);
            }
            // 根级导航 md 落入 release index/
            try (var stream = Files.list(wsDocs)) {
                stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md"))
                        .forEach(p -> {
                            try {
                                Files.copy(p, indexDir.resolve(p.getFileName().toString()),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            } catch (Exception e) {
                                log.warn("copy index md {} failed: {}", p, e.getMessage());
                            }
                        });
            }
            if (Files.isDirectory(wsDocs.resolve("meta"))) {
                copyDir(wsDocs.resolve("meta"), metaDir);
            }

            var vj = new java.util.LinkedHashMap<String, Object>();
            vj.put("versionNum", version.getVersionNum());
            vj.put("systemId", sysId);
            vj.put("repositoryId", repoId);
            vj.put("pushedAt", LocalDateTime.now().toString());
            long docCount = 0;
            try (var s = Files.list(modulesDir)) {
                docCount = s.filter(Files::isRegularFile).count();
            }
            vj.put("documentCount", docCount);
            Files.writeString(metaDir.resolve("knowledge-version.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(vj));

            String resultPath = releaseDir.toAbsolutePath().normalize().toString();
            log.info("NAS push success: versionId={} path={} docs={}", version.getId(), resultPath, docCount);
            return resultPath;
        } catch (BusinessException e) {
            throw e;
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
                    Files.createDirectories(d.getParent());
                    Files.copy(s, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                log.warn("copyDir: {} → {} failed: {}", s, d, e.getMessage());
            }
        });
    }
}
