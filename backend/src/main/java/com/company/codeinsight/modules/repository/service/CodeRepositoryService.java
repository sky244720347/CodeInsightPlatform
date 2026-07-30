package com.company.codeinsight.modules.repository.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.company.codeinsight.modules.repository.dto.GitConnectivityResult;
import com.company.codeinsight.modules.repository.entity.CodeRepository;

/**
 * 代码仓库管理服务接口
 * 负责定义代码仓库列表分页查询、Git 网络测试连通性、创建/更新时触发系统状态机、软删除强校验等业务规则。
 */
public interface CodeRepositoryService extends IService<CodeRepository> {

    /**
     * 分页多条件查询代码仓库配置记录列表
     * <p>当 {@code hasPublished} 为 true 时，仅返回已发布知识版本的仓库（last_published_version_id IS NOT NULL）。</p>
     */
    Page<CodeRepository> listRepositoriesPage(int current, int size, Long systemId, String gitUrl, Boolean hasPublished);

    /**
     * 对已保存的仓库记录进行连接有效性测试
     */
    boolean testConnection(Long id);

    /**
     * 针对新输入的仓库参数进行实时连接有效性测试
     */
    boolean testConnection(String gitUrl, String branch, String username, String password);

    /**
     * 连通性检测（结构化结果）。带 id 时测完落库；密码为 ****** 时复用库中凭证。
     */
    GitConnectivityResult testConnectionDetailed(CodeRepository repository);

    /**
     * 新建仓库：保存后自动推进系统状态
     *  - 若系统当前 DRAFT → REPO_CONFIGURED
     *  - 若新建时已带 entryScanConfig → 同时推进到 SCAN_CONFIGURED
     */
    CodeRepository createRepository(CodeRepository repository);

    /**
     * 更新仓库：若 entryScanConfig 由 null 变为非空，推进系统到 SCAN_CONFIGURED
     */
    CodeRepository updateRepository(Long id, CodeRepository repository);

    /**
     * 软删除代码库。强校验：存在活跃任务时拒绝。
     *
     * @param id 仓库 ID
     */
    void softDeleteRepository(Long id);
}
