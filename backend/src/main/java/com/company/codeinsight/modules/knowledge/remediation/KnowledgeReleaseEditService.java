package com.company.codeinsight.modules.knowledge.remediation;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.common.util.DataUriUtil;
import com.company.codeinsight.modules.knowledge.browse.ActiveKnowledgeContext;
import com.company.codeinsight.modules.knowledge.browse.RepositoryActiveKnowledgeResolver;
import com.company.codeinsight.modules.knowledge.remediation.dto.ReleaseDocumentEditRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class KnowledgeReleaseEditService {

    private final RepositoryActiveKnowledgeResolver activeKnowledgeResolver;
    private final KnowledgeReleaseEditMapper editMapper;
    private final EnvStorageResolver storageResolver;

    @Transactional(rollbackFor = Exception.class)
    public Long submitEdit(ReleaseDocumentEditRequest request) {
        if (request.getRepositoryId() == null || !StringUtils.hasText(request.getRelativePath())) {
            throw new BusinessException("仓库与文件路径不能为空");
        }
        if (!StringUtils.hasText(request.getContent())) {
            throw new BusinessException("文档内容不能为空");
        }
        String relativePath = normalizeRelativePath(request.getRelativePath());
        ActiveKnowledgeContext ctx = activeKnowledgeResolver.require(request.getRepositoryId());

        KnowledgeReleaseEditEntity row = new KnowledgeReleaseEditEntity();
        row.setRepositoryId(request.getRepositoryId());
        row.setVersionId(ctx.getVersionId());
        row.setRelativePath(relativePath);
        row.setContentUri("");
        row.setStatus("PENDING");
        row.setSubmittedBy(StringUtils.hasText(request.getOperator()) ? request.getOperator() : "system");
        row.setCreatedDate(LocalDateTime.now());
        editMapper.insert(row);

        String uri = DataUriUtil.buildReleaseEditUri(row.getId());
        String hash = DataUriUtil.writeUtf8(uri, request.getContent(), storageResolver);
        row.setContentUri(uri);
        row.setHash(hash);
        editMapper.updateById(row);
        return row.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void approveEdit(Long editId, String operator) {
        KnowledgeReleaseEditEntity row = editMapper.selectById(editId);
        if (row == null) {
            throw new BusinessException("修订记录不存在");
        }
        if (!"PENDING".equals(row.getStatus())) {
            throw new BusinessException("仅待审核记录可批准，当前: " + row.getStatus());
        }
        ActiveKnowledgeContext ctx = activeKnowledgeResolver.require(row.getRepositoryId());
        Path releaseDir = ctx.getReleaseDir().toAbsolutePath().normalize();
        Path target = releaseDir.resolve(row.getRelativePath()).normalize();
        if (!target.startsWith(releaseDir)) {
            throw new BusinessException("非法文件路径");
        }
        String contentText = loadContent(row);
        String body = ensureHumanEditedFrontMatter(contentText);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, body);
        } catch (IOException e) {
            throw new BusinessException("写入发布文件失败: " + e.getMessage());
        }
        row.setStatus("APPROVED");
        row.setApprovedBy(StringUtils.hasText(operator) ? operator : "system");
        row.setApprovedAt(LocalDateTime.now());
        editMapper.updateById(row);
    }

    private String loadContent(KnowledgeReleaseEditEntity row) {
        if (row == null) {
            return "";
        }
        if (StringUtils.hasText(row.getContentText())) {
            return row.getContentText();
        }
        if (!StringUtils.hasText(row.getContentUri())) {
            throw new BusinessException("修订正文 URI 为空");
        }
        String body = DataUriUtil.readUtf8(row.getContentUri(), storageResolver);
        row.setContentText(body);
        return body;
    }

    private String normalizeRelativePath(String path) {
        String p = path.trim().replace('\\', '/');
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.contains("..")) {
            throw new BusinessException("非法路径");
        }
        return p;
    }

    private String ensureHumanEditedFrontMatter(String content) {
        String trimmed = content == null ? "" : content;
        if (trimmed.startsWith("---")) {
            int end = trimmed.indexOf("\n---", 3);
            if (end > 0) {
                String front = trimmed.substring(0, end + 4);
                String rest = trimmed.substring(end + 4);
                if (front.contains("contentOrigin:")) {
                    return trimmed.replaceFirst("contentOrigin:\\s*\\S+", "contentOrigin: HUMAN_EDITED");
                }
                return front + "\ncontentOrigin: HUMAN_EDITED\n---" + rest;
            }
        }
        return "---\ncontentOrigin: HUMAN_EDITED\n---\n\n" + trimmed;
    }
}
