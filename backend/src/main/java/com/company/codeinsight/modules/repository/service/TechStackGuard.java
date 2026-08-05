package com.company.codeinsight.modules.repository.service;

import com.company.codeinsight.common.config.TaskTechStackProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.exception.ErrorCode;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.model.RepoType;
import com.company.codeinsight.modules.repository.model.TechStackCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 代码库类型 / 技术栈目录校验，以及任务下发时的可执行白名单门禁。
 */
@Component
@RequiredArgsConstructor
public class TechStackGuard {

    private final TaskTechStackProperties taskTechStackProperties;

    /**
     * 新建 / 导入时：类型与技术栈必填，且须落在级联目录内。
     */
    public void requireValidCatalogPair(String repoType, String techStack) {
        if (!StringUtils.hasText(repoType)) {
            throw new BusinessException("请选择代码库类型");
        }
        if (!RepoType.isValid(repoType)) {
            throw new BusinessException("代码库类型无效，可选：前端 / 后端 / DB");
        }
        if (!StringUtils.hasText(techStack)) {
            throw new BusinessException("请选择技术栈");
        }
        String type = repoType.trim();
        String stack = techStack.trim();
        if (!TechStackCatalog.isValidPair(type, stack)) {
            throw new BusinessException("技术栈「" + stack + "」不属于代码库类型「" + type + "」的可选范围");
        }
    }

    /**
     * 任务创建门禁：仓库须已配置技术栈，且在可执行白名单内。
     */
    public void assertExecutableForTask(CodeRepository repository) {
        if (repository == null) {
            throw new BusinessException("所选代码库不存在");
        }
        String stack = repository.getTechStack();
        if (!StringUtils.hasText(stack)) {
            throw new BusinessException(ErrorCode.TECH_STACK_NOT_CONFIGURED);
        }
        String trimmed = stack.trim();
        if (!taskTechStackProperties.isSupported(trimmed)) {
            throw new BusinessException(
                    ErrorCode.TECH_STACK_UNSUPPORTED,
                    "技术栈「" + trimmed + "」不在可执行配置中，暂不支持生成知识");
        }
    }

    /**
     * 规范化后写回（trim）。
     * <p>类型与技术栈皆空（真空）允许保存，交给 {@code RepoStackProbe} 自动识别；
     * 任一端有值则须成对合法。</p>
     */
    public void normalizeAndValidate(CodeRepository repository) {
        if (repository == null) {
            return;
        }
        String type = StringUtils.hasText(repository.getRepoType()) ? repository.getRepoType().trim() : null;
        String stack = StringUtils.hasText(repository.getTechStack()) ? repository.getTechStack().trim() : null;
        repository.setRepoType(type);
        repository.setTechStack(stack);
        if (type == null && stack == null) {
            return;
        }
        requireValidCatalogPair(type, stack);
    }
}
