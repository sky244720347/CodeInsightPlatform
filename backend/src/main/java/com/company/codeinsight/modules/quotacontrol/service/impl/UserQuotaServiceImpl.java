package com.company.codeinsight.modules.quotacontrol.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.codeinsight.modules.auth.entity.UserAccount;
import com.company.codeinsight.modules.auth.mapper.UserAccountMapper;
import com.company.codeinsight.modules.quotacontrol.entity.UserQuota;
import com.company.codeinsight.modules.quotacontrol.mapper.UserQuotaMapper;
import com.company.codeinsight.modules.quotacontrol.service.UserQuotaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class UserQuotaServiceImpl extends ServiceImpl<UserQuotaMapper, UserQuota> implements UserQuotaService {

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Override
    public UserQuota findByUserId(Long userId) {
        if (userId == null) return null;
        return this.getOne(new LambdaQueryWrapper<UserQuota>().eq(UserQuota::getUserId, userId), false);
    }

    @Override
    public Page<UserQuota> pageQuery(int current, int size, String username, Integer enabled) {
        Page<UserQuota> page = new Page<>(current, size);
        LambdaQueryWrapper<UserQuota> qw = new LambdaQueryWrapper<>();
        if (enabled != null) {
            qw.eq(UserQuota::getEnabled, enabled);
        }
        // 先按 userId 过滤：如果传了 username，先查 user 拿到 id 列表
        if (StringUtils.hasText(username)) {
            java.util.List<UserAccount> users = userAccountMapper.selectList(
                    new LambdaQueryWrapper<UserAccount>().like(UserAccount::getUsername, username.trim()));
            if (users.isEmpty()) {
                return page; // 空结果
            }
            java.util.List<Long> ids = users.stream().map(UserAccount::getId).toList();
            qw.in(UserQuota::getUserId, ids);
        }
        qw.orderByDesc(UserQuota::getUpdatedDate);
        return this.page(page, qw);
    }
}
