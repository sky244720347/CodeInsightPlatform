package com.company.codeinsight.modules.knowledge.browse;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RepositoryActiveKnowledgeResolver {

    private final CodeRepositoryMapper repositoryMapper;
    private final KnowledgeVersionMapper versionMapper;
    private final EnvStorageResolver storageResolver;

    public Optional<ActiveKnowledgeContext> resolve(Long repositoryId) {
        if (repositoryId == null) {
            return Optional.empty();
        }
        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        if (repo == null || repo.getLastPublishedVersionId() == null) {
            return Optional.empty();
        }
        KnowledgeVersion version = versionMapper.selectById(repo.getLastPublishedVersionId());
        if (version == null || !"PUSHED".equals(version.getStatus())) {
            return Optional.empty();
        }
        Path releaseDir = storageResolver.releaseDir(
                version.getSystemId(), version.getRepositoryId(), version.getVersionNum());

        ActiveKnowledgeContext ctx = new ActiveKnowledgeContext();
        ctx.setSystemId(version.getSystemId());
        ctx.setRepositoryId(version.getRepositoryId());
        ctx.setVersionId(version.getId());
        ctx.setVersionNum(version.getVersionNum());
        ctx.setTaskId(version.getTaskId());
        ctx.setReleaseDir(releaseDir);
        ctx.setReleaseDirExists(Files.isDirectory(releaseDir));
        return Optional.of(ctx);
    }

    public ActiveKnowledgeContext require(Long repositoryId) {
        return resolve(repositoryId)
                .filter(ActiveKnowledgeContext::isReleaseDirExists)
                .orElseThrow(() -> new BusinessException("该仓库尚无已发布且可读取的生效知识版本，请先完成 NAS 发布"));
    }

    public Path releaseDirForVersion(KnowledgeVersion version) {
        if (version == null) {
            throw new BusinessException("知识版本不存在");
        }
        return storageResolver.releaseDir(
                version.getSystemId(), version.getRepositoryId(), version.getVersionNum());
    }
}
