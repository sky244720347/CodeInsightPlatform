package com.company.codeinsight.modules.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.vo.SystemSummaryVO;

/**
 * 业务系统应用管理服务接口
 * 负责定义接入系统的分页查询、软删除强校验等业务逻辑规则。
 */
public interface SystemApplicationService extends IService<SystemApplication> {

    /**
     * 分页多条件查询接入业务系统列表（带聚合指标：代码库数 / 知识版本数 / 最近扫描时间）
     * <p>仅按 name / owner 模糊过滤。</p>
     */
    Page<SystemSummaryVO> listSystemsPage(int current, int size, String name, String owner);

    /**
     * 新建系统（向导 Step 1）。必填：name、owner。
     */
    SystemApplication createSystemDraft(SystemApplication system);

    /**
     * 软删除系统。强校验活跃任务，并级联软删除该系统下所有未删除的代码库。
     *
     * @param id 系统 ID
     * @throws com.company.codeinsight.common.exception.BusinessException 当存在未完成任务时
     */
    void softDeleteSystem(Long id);
}
