package com.company.codeinsight.modules.system.service.impl;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfigCodec;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.prompt.entity.DecompilePrompt;
import com.company.codeinsight.modules.prompt.mapper.DecompilePromptMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.system.dto.SystemImportExcelRow;
import com.company.codeinsight.modules.system.dto.SystemImportItemResult;
import com.company.codeinsight.modules.system.dto.SystemImportResult;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.service.SystemApplicationService;
import com.company.codeinsight.modules.system.service.SystemImportService;
import com.company.codeinsight.modules.system.support.SystemExcelParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Excel 批量导入：对齐「新建系统」向导默认配置，提示词绑定仓库级全局默认。
 * <p>无整批事务：逐行独立落库，部分失败不影响已成功行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemImportServiceImpl implements SystemImportService {

    private static final String DEFAULT_BRANCH = "master";
    private static final String DEFAULT_SCAN_ROOT = "/";
    private static final String DEFAULT_PUSH_FOLDER = "docs/code-insight";

    private final SystemApplicationService systemApplicationService;
    private final CodeRepositoryService codeRepositoryService;
    private final DecompilePromptMapper decompilePromptMapper;
    private final OperationLogService operationLogService;

    @Override
    public SystemImportResult importFromExcel(MultipartFile file) {
        List<SystemExcelParser.ParsedRow> rows = SystemExcelParser.parse(file);
        DefaultPrompts prompts = resolveDefaultPrompts();
        String defaultEntryScanJson = EntryPointConfigCodec.encode(EntryPointConfig.defaults());

        List<SystemImportItemResult> items = new ArrayList<>();
        Map<String, CachedSystem> systemCache = new HashMap<>();
        Set<Long> createdSystemIds = new HashSet<>();
        Set<Long> reusedSystemIds = new HashSet<>();
        int repoCreated = 0;
        int skipped = 0;
        int failed = 0;

        for (SystemExcelParser.ParsedRow parsed : rows) {
            SystemImportItemResult item = processRow(parsed, prompts, defaultEntryScanJson, systemCache, createdSystemIds);
            items.add(item);
            if (item.getSystemId() != null) {
                if (createdSystemIds.contains(item.getSystemId())) {
                    // already tracked
                } else {
                    reusedSystemIds.add(item.getSystemId());
                }
            }
            switch (item.getStatus()) {
                case SystemImportItemResult.STATUS_CREATED,
                     SystemImportItemResult.STATUS_SYSTEM_REUSED -> repoCreated++;
                case SystemImportItemResult.STATUS_SKIPPED -> skipped++;
                case SystemImportItemResult.STATUS_FAILED -> failed++;
                default -> {
                }
            }
        }
        // 复用集合去掉本批新建的系统
        reusedSystemIds.removeAll(createdSystemIds);

        SystemImportResult result = SystemImportResult.builder()
                .totalRows(rows.size())
                .systemCreated(createdSystemIds.size())
                .systemReused(reusedSystemIds.size())
                .repoCreated(repoCreated)
                .skipped(skipped)
                .failed(failed)
                .items(items)
                .build();

        operationLogService.logOperation(
                null,
                null,
                "BATCH_IMPORT_SYSTEM",
                String.format("Excel 批量导入：总行=%d 系统新建=%d 系统复用=%d 仓库新建=%d 跳过=%d 失败=%d",
                        result.getTotalRows(), result.getSystemCreated(), result.getSystemReused(),
                        result.getRepoCreated(), result.getSkipped(), result.getFailed()),
                null,
                result.getFailed() == 0);
        return result;
    }

    @Override
    public byte[] buildImportTemplate() {
        SystemImportExcelRow example = new SystemImportExcelRow();
        example.setSystemName("PH-NCS");
        example.setNameCn("普惠客服系统");
        example.setDescription("示例：请按实际系统填写；同系统多仓可多行，系统名保持一致");
        example.setOwner("zhangsan");
        example.setGitUrl("https://code.example.com/group/repo-name.git");
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            EasyExcel.write(out, SystemImportExcelRow.class)
                    .sheet("系统导入")
                    .doWrite(List.of(example));
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("生成导入模板失败: " + e.getMessage());
        }
    }

    private SystemImportItemResult processRow(SystemExcelParser.ParsedRow parsed,
                                              DefaultPrompts prompts,
                                              String defaultEntryScanJson,
                                              Map<String, CachedSystem> systemCache,
                                              Set<Long> createdSystemIds) {
        SystemImportExcelRow data = parsed.data();
        int row = parsed.excelRow();
        // 导入落库统一全大写；匹配仍忽略大小写（与库中历史大小写混用兼容）
        String systemNameRaw = trim(data.getSystemName());
        String systemName = StringUtils.hasText(systemNameRaw)
                ? systemNameRaw.toUpperCase(Locale.ROOT)
                : systemNameRaw;
        String gitUrl = normalizeGitUrl(data.getGitUrl());
        String owner = trim(data.getOwner());

        if (!StringUtils.hasText(systemName)) {
            return fail(row, systemName, gitUrl, "系统名称不能为空");
        }
        if (!StringUtils.hasText(gitUrl)) {
            return fail(row, systemName, gitUrl, "git完整地址不能为空");
        }

        try {
            // 仅本导入接口：系统名按忽略大小写归并；新建时落库为全大写
            String systemKey = systemName.toLowerCase(Locale.ROOT);
            CachedSystem cached = systemCache.get(systemKey);
            boolean systemCreatedThisRow = false;
            if (cached == null) {
                SystemApplication existing = findExistingSystemIgnoreCase(systemName);
                if (existing != null) {
                    cached = new CachedSystem(existing.getId(), false);
                    systemCache.put(systemKey, cached);
                } else {
                    if (!StringUtils.hasText(owner)) {
                        return fail(row, systemName, gitUrl, "新建系统时负责人不能为空");
                    }
                    SystemApplication created = createSystem(data, systemName, owner);
                    cached = new CachedSystem(created.getId(), true);
                    systemCache.put(systemKey, cached);
                    createdSystemIds.add(created.getId());
                    systemCreatedThisRow = true;
                }
            }

            Long systemId = cached.id();
            CodeRepository existingRepo = findExistingRepo(systemId, gitUrl);
            if (existingRepo != null) {
                return SystemImportItemResult.builder()
                        .row(row)
                        .systemName(systemName)
                        .gitUrl(gitUrl)
                        .status(SystemImportItemResult.STATUS_SKIPPED)
                        .systemId(systemId)
                        .repositoryId(existingRepo.getId())
                        .message(cached.createdInBatch()
                                ? "系统已存在（本批）；仓库已存在，跳过"
                                : "系统已存在；仓库已存在，跳过")
                        .build();
            }

            CodeRepository repo = createRepository(systemId, gitUrl, prompts, defaultEntryScanJson);
            boolean systemIsNew = cached.createdInBatch();
            return SystemImportItemResult.builder()
                    .row(row)
                    .systemName(systemName)
                    .gitUrl(gitUrl)
                    .status(systemIsNew && systemCreatedThisRow
                            ? SystemImportItemResult.STATUS_CREATED
                            : SystemImportItemResult.STATUS_SYSTEM_REUSED)
                    .systemId(systemId)
                    .repositoryId(repo.getId())
                    .message(systemCreatedThisRow
                            ? "系统与仓库已创建"
                            : (systemIsNew ? "本批已建系统，仓库已创建" : "系统已存在，仓库已创建"))
                    .build();
        } catch (BusinessException e) {
            return fail(row, systemName, gitUrl, e.getMessage());
        } catch (Exception e) {
            log.warn("import row {} failed: {}", row, e.toString());
            return fail(row, systemName, gitUrl, "导入失败: " + e.getMessage());
        }
    }

    private SystemApplication createSystem(SystemImportExcelRow data, String systemName, String owner) {
        SystemApplication system = new SystemApplication();
        system.setName(systemName);
        system.setComponent("");
        system.setNameCn(trim(data.getNameCn()));
        system.setDescription(trim(data.getDescription()));
        system.setOwner(owner);
        system.setMaxConcurrentTasks(1);
        return systemApplicationService.createSystemDraft(system);
    }

    private CodeRepository createRepository(Long systemId, String gitUrl,
                                            DefaultPrompts prompts, String entryScanJson) {
        CodeRepository repo = new CodeRepository();
        repo.setSystemId(systemId);
        repo.setGitUrl(gitUrl);
        repo.setBranch(DEFAULT_BRANCH);
        repo.setScanRoot(DEFAULT_SCAN_ROOT);
        repo.setPushTargetFolder(DEFAULT_PUSH_FOLDER);
        repo.setUsername(null);
        repo.setPassword(null);
        repo.setEntryScanConfig(entryScanJson);
        repo.setModularizePromptId(prompts.modularizeId());
        repo.setDocumentPromptId(prompts.documentId());
        return codeRepositoryService.createRepository(repo);
    }

    /**
     * 仅批量导入使用：按 LOWER(name) 匹配已有系统，不影响页面新建/编辑的大小写敏感查重。
     */
    private SystemApplication findExistingSystemIgnoreCase(String name) {
        return systemApplicationService.getOne(
                new LambdaQueryWrapper<SystemApplication>()
                        .eq(SystemApplication::getComponent, "")
                        .apply("LOWER(name) = LOWER({0})", name)
                        .last("LIMIT 1"),
                false);
    }

    private CodeRepository findExistingRepo(Long systemId, String gitUrl) {
        return codeRepositoryService.getOne(
                new LambdaQueryWrapper<CodeRepository>()
                        .eq(CodeRepository::getSystemId, systemId)
                        .eq(CodeRepository::getGitUrl, gitUrl)
                        .last("LIMIT 1"),
                false);
    }

    private DefaultPrompts resolveDefaultPrompts() {
        Long modularizeId = findDefaultPromptId(DecompilePrompt.TYPE_MODULARIZE);
        Long documentId = findDefaultPromptId(DecompilePrompt.TYPE_DOCUMENT_GENERATION);
        if (modularizeId == null) {
            throw new BusinessException("未找到模块提取默认提示词（is_default=1），请先在提示词管理配置");
        }
        if (documentId == null) {
            throw new BusinessException("未找到文档生成默认提示词（is_default=1），请先在提示词管理配置");
        }
        return new DefaultPrompts(modularizeId, documentId);
    }

    private Long findDefaultPromptId(String promptType) {
        DecompilePrompt prompt = decompilePromptMapper.selectOne(
                new LambdaQueryWrapper<DecompilePrompt>()
                        .eq(DecompilePrompt::getPromptType, promptType)
                        .eq(DecompilePrompt::getIsDefault, 1)
                        .orderByDesc(DecompilePrompt::getId)
                        .last("LIMIT 1"));
        return prompt == null ? null : prompt.getId();
    }

    private static SystemImportItemResult fail(int row, String systemName, String gitUrl, String message) {
        return SystemImportItemResult.builder()
                .row(row)
                .systemName(systemName)
                .gitUrl(gitUrl)
                .status(SystemImportItemResult.STATUS_FAILED)
                .message(message)
                .build();
    }

    private static String trim(String raw) {
        return raw == null ? null : raw.trim();
    }

    private static String normalizeGitUrl(String raw) {
        String v = trim(raw);
        if (!StringUtils.hasText(v)) {
            return v;
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private record DefaultPrompts(Long modularizeId, Long documentId) {
    }

    private record CachedSystem(Long id, boolean createdInBatch) {
    }
}
