package com.company.codeinsight.modules.repository.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.repository.dto.GitConnectivityResult;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.repository.service.RepoGitConnectivityService;
import com.company.codeinsight.modules.repository.service.TechStackGuard;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 代码仓库管理服务实现类
 * 负责组装条件模糊分页查询代码库、JGit 连通性测试、软删除强校验。
 */
@Slf4j
@Service
public class CodeRepositoryServiceImpl extends ServiceImpl<CodeRepositoryMapper, CodeRepository> implements CodeRepositoryService {

    /** 处于活跃态的任务集合，这些状态下不允许删除关联仓库 */
    private static final Set<String> ACTIVE_TASK_STATUSES = Set.of(
            "PENDING", "PULLING_CODE", "PARSING_CODE", "SPLITTING_TASK",
            "AI_ANALYZING", "GENERATING_DOC", "REVIEWING", "PUSHING"
    );

    @Autowired
    private DecompileTaskMapper decompileTaskMapper;

    @Autowired
    private TechStackGuard techStackGuard;

    @Autowired
    private RepoGitConnectivityService repoGitConnectivityService;

    @Override
    public Page<CodeRepository> listRepositoriesPage(int current, int size, Long systemId, String gitUrl, Boolean hasPublished) {
        Page<CodeRepository> page = new Page<>(current, size);
        LambdaQueryWrapper<CodeRepository> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(systemId != null, CodeRepository::getSystemId, systemId)
                .like(StringUtils.hasText(gitUrl), CodeRepository::getGitUrl, gitUrl)
                .isNotNull(Boolean.TRUE.equals(hasPublished), CodeRepository::getLastPublishedVersionId)
                .orderByDesc(CodeRepository::getCreatedDate);
        return this.page(page, queryWrapper);
    }

    @Override
    public boolean testConnection(Long id) {
        return repoGitConnectivityService.checkAndPersist(id).isReachable();
    }

    @Override
    public boolean testConnection(String gitUrl, String branch, String username, String password) {
        return repoGitConnectivityService.probeOnly(gitUrl, username, password).isReachable();
    }

    /**
     * 带落库的连通性检测（已保存仓库）；未保存仅探测。
     */
    @Override
    public GitConnectivityResult testConnectionDetailed(CodeRepository repository) {
        if (repository == null) {
            throw new BusinessException("仓库参数不能为空");
        }
        String password = repository.getPassword();
        if ("******".equals(password) && repository.getId() != null) {
            CodeRepository existing = this.getById(repository.getId());
            if (existing == null) {
                throw new BusinessException("代码库配置不存在");
            }
            password = existing.getPassword();
        }
        if (repository.getId() != null) {
            CodeRepository existing = this.getById(repository.getId());
            if (existing == null) {
                throw new BusinessException("代码库配置不存在");
            }
            // 允许用表单里未保存的 url/凭证试通并落库到该 id
            existing.setGitUrl(StringUtils.hasText(repository.getGitUrl()) ? repository.getGitUrl() : existing.getGitUrl());
            existing.setUsername(repository.getUsername() != null ? repository.getUsername() : existing.getUsername());
            existing.setPassword(password);
            return repoGitConnectivityService.checkAndPersist(existing);
        }
        return repoGitConnectivityService.probeOnly(repository.getGitUrl(), repository.getUsername(), password);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CodeRepository createRepository(CodeRepository repository) {
        if (repository == null || repository.getSystemId() == null) {
            throw new BusinessException("仓库数据/systemId 必填");
        }
        techStackGuard.normalizeAndValidate(repository);
        repository.setId(null);
        this.save(repository);
        return repository;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CodeRepository updateRepository(Long id, CodeRepository repository) {
        CodeRepository existing = this.getById(id);
        if (existing == null) {
            throw new BusinessException("代码库不存在");
        }
        // 若密码为 ****** 占位符，复用旧密码
        if ("******".equals(repository.getPassword())) {
            repository.setPassword(existing.getPassword());
        }
        // 入参未携带提示词 ID 时（前端编辑表单未提交此字段），复用旧值
        // 避免 MyBatis-Plus updateById 的全字段替换把已配置的仓库级提示词清空
        if (repository.getModularizePromptId() == null) {
            repository.setModularizePromptId(existing.getModularizePromptId());
        }
        if (repository.getDocumentPromptId() == null) {
            repository.setDocumentPromptId(existing.getDocumentPromptId());
        }
        // 局部更新（入口扫描 / 提示词绑定）可能不带类型与技术栈：复用旧值
        if (!StringUtils.hasText(repository.getRepoType())) {
            repository.setRepoType(existing.getRepoType());
        }
        if (!StringUtils.hasText(repository.getTechStack())) {
            repository.setTechStack(existing.getTechStack());
        }
        // 历史仓库补全 / 编辑时仍须合法；若两端仍为空则暂不强制（下发任务时再门禁）
        if (StringUtils.hasText(repository.getRepoType()) || StringUtils.hasText(repository.getTechStack())) {
            techStackGuard.normalizeAndValidate(repository);
        }
        repository.setId(id);
        this.updateById(repository);
        return repository;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void softDeleteRepository(Long id) {
        CodeRepository repo = this.getById(id);
        if (repo == null) {
            throw new BusinessException("代码库不存在");
        }

        // 强校验：是否还有未完成任务
        Long activeCount = decompileTaskMapper.selectCount(
                new LambdaQueryWrapper<DecompileTask>()
                        .eq(DecompileTask::getRepositoryId, id)
                        .in(DecompileTask::getStatus, ACTIVE_TASK_STATUSES)
        );
        if (activeCount != null && activeCount > 0) {
            throw new BusinessException("该代码库下存在 " + activeCount + " 个未完成任务，请先处理后再删除");
        }

        // @TableLogic 字段不能通过 updateById 写入，须走 removeById 触发逻辑删除
        if (!this.removeById(id)) {
            throw new BusinessException("代码库删除失败");
        }
    }
}
