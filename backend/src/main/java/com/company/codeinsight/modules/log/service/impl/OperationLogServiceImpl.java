package com.company.codeinsight.modules.log.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.codeinsight.modules.log.entity.OperationLog;
import com.company.codeinsight.modules.log.mapper.OperationLogMapper;
import com.company.codeinsight.modules.log.service.OperationLogService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 系统操作审计日志服务实现类
 * 负责审计日志信息的拼装入库（采用 Mock 简易用户身份）和分页模糊条件匹配逻辑。
 */
@Service
public class OperationLogServiceImpl extends ServiceImpl<OperationLogMapper, OperationLog> implements OperationLogService {

    private static final int MAX_DETAIL_LENGTH = 1000;
    private static final int MAX_ACTION_TYPE_LENGTH = 50;

    /**
     * 保存单条操作日志
     * 临时采用固定用户（id=1L, username="Owner"）和固定 IP ("127.0.0.1") 写入。
     */
    @Override
    public void logOperation(Long systemId, Long taskId, String actionType, String detail, String exceptionMsg, boolean success) {
        OperationLog log = new OperationLog();
        log.setSystemId(systemId);
        log.setTaskId(taskId);
        log.setUserId(1L); // Mock 用户 ID
        log.setUsername("Owner"); // Mock 用户名
        log.setActionType(truncate(actionType, MAX_ACTION_TYPE_LENGTH));
        log.setDetail(truncate(detail, MAX_DETAIL_LENGTH));
        log.setIpAddress("127.0.0.1");
        log.setExceptionMsg(com.company.codeinsight.common.util.DbStringLimits.truncate(
                exceptionMsg, com.company.codeinsight.common.util.DbStringLimits.EXCEPTION_MSG));
        log.setIsSuccess(success ? 1 : 0);
        log.setCreatedDate(LocalDateTime.now());
        this.save(log);
    }

    /**
     * 分页查询审计日志
     * 针对系统ID、任务ID、类型与状态进行精确定位，对操作人用户名进行 like 模糊匹配，并按最新时间倒序排列。
     */
    @Override
    public Page<OperationLog> listLogsPage(int current, int size, Long systemId, Long taskId, String username, String actionType, Integer isSuccess) {
        Page<OperationLog> page = new Page<>(current, size);
        LambdaQueryWrapper<OperationLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(systemId != null, OperationLog::getSystemId, systemId)
                .eq(taskId != null, OperationLog::getTaskId, taskId)
                .like(StringUtils.hasText(username), OperationLog::getUsername, username)
                .eq(StringUtils.hasText(actionType), OperationLog::getActionType, actionType)
                .eq(isSuccess != null, OperationLog::getIsSuccess, isSuccess)
                .orderByDesc(OperationLog::getCreatedDate);
        return this.page(page, queryWrapper);
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }
}

