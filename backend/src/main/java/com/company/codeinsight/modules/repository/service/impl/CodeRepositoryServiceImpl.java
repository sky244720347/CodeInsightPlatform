package com.company.codeinsight.modules.repository.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
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

    @Override
    public Page<CodeRepository> listRepositoriesPage(int current, int size, Long systemId, String gitUrl) {
        Page<CodeRepository> page = new Page<>(current, size);
        LambdaQueryWrapper<CodeRepository> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(systemId != null, CodeRepository::getSystemId, systemId)
                .like(StringUtils.hasText(gitUrl), CodeRepository::getGitUrl, gitUrl)
                .orderByDesc(CodeRepository::getCreatedAt);
        return this.page(page, queryWrapper);
    }

    @Override
    public boolean testConnection(Long id) {
        CodeRepository repo = this.getById(id);
        if (repo == null) {
            throw new BusinessException("代码库配置不存在");
        }
        return testConnection(repo.getGitUrl(), repo.getBranch(), repo.getUsername(), repo.getPassword());
    }

    @Override
    public boolean testConnection(String gitUrl, String branch, String username, String password) {
        if (!StringUtils.hasText(gitUrl)) {
            return false;
        }
        try {
            LsRemoteCommand lsRemote = Git.lsRemoteRepository().setRemote(gitUrl);
            if (StringUtils.hasText(username)) {
                lsRemote.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password != null ? password : ""));
            }
            Collection<Ref> refs = lsRemote.call();
            return refs != null && !refs.isEmpty();
        } catch (Exception e) {
            log.error("JGit test connection failed for remote: " + gitUrl, e);
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CodeRepository createRepository(CodeRepository repository) {
        if (repository == null || repository.getSystemId() == null) {
            throw new BusinessException("仓库数据/systemId 必填");
        }
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

        repo.setDeletedAt(LocalDateTime.now());
        this.updateById(repo);
    }
}
