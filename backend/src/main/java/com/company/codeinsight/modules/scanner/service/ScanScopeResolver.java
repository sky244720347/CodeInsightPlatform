package com.company.codeinsight.modules.scanner.service;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.entrypoint.trial.EntryScanTrialEntity;
import com.company.codeinsight.modules.entrypoint.trial.EntryScanTrialMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.scanner.model.ScanScope;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 按仓库配置解析 {@link ScanScope}；支持正式任务与入口试跑（trialId 同工作区 id）。
 */
@Slf4j
@Component
public class ScanScopeResolver {

    @Autowired
    private CodeRepositoryMapper repositoryMapper;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired(required = false)
    private EntryScanTrialMapper trialMapper;

    public ScanScope require(CodeRepository repo, File repoRoot) {
        return ScanScope.from(repo, repoRoot);
    }

    public ScanScope requireByRepositoryId(Long repositoryId, File repoRoot) {
        if (repositoryId == null) {
            throw new BusinessException("repositoryId 为空，无法解析扫描范围");
        }
        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        if (repo == null) {
            throw new BusinessException("未找到代码库配置: " + repositoryId);
        }
        return ScanScope.from(repo, repoRoot);
    }

    /**
     * 按 taskId / trialId 尽力解析；都找不到时退回整仓（兼容无配置上下文）。
     */
    public ScanScope resolveBestEffort(Long taskOrTrialId, File repoRoot) {
        if (repoRoot == null || !repoRoot.isDirectory()) {
            throw new BusinessException("仓库根目录无效，无法解析扫描范围");
        }
        Long repositoryId = lookupRepositoryId(taskOrTrialId);
        if (repositoryId == null) {
            log.warn("ScanScope: taskOrTrialId={} 无关联仓库，使用整仓范围", taskOrTrialId);
            return ScanScope.wholeRepository(repoRoot);
        }
        return requireByRepositoryId(repositoryId, repoRoot);
    }

    private Long lookupRepositoryId(Long taskOrTrialId) {
        if (taskOrTrialId == null) {
            return null;
        }
        DecompileTask task = taskMapper.selectById(taskOrTrialId);
        if (task != null && task.getRepositoryId() != null) {
            return task.getRepositoryId();
        }
        if (trialMapper != null) {
            EntryScanTrialEntity trial = trialMapper.selectById(taskOrTrialId);
            if (trial != null && trial.getRepositoryId() != null) {
                return trial.getRepositoryId();
            }
        }
        return null;
    }
}
