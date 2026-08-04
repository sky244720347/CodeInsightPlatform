package com.company.codeinsight.common.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.net.LocalAddressSet;
import com.company.codeinsight.modules.auth.entity.UserAccount;
import com.company.codeinsight.modules.auth.mapper.UserAccountMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 从 HTTP header 中读取当前操作人并写入 {@link OperatorContext}，
 * 同时解析有效客户端 IP 写入 {@link ClientIpContext}（loopback 回落本机网卡 IP）。
 */
public class OperatorHeaderFilter extends OncePerRequestFilter {

    public static final String OPERATOR_HEADER = "X-Operator";

    private static final ConcurrentMap<String, CachedUser> USER_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5L * 60 * 1000;

    private final UserAccountMapper userAccountMapper;
    private final LocalAddressSet localAddressSet;

    public OperatorHeaderFilter(UserAccountMapper userAccountMapper, LocalAddressSet localAddressSet) {
        this.userAccountMapper = userAccountMapper;
        this.localAddressSet = localAddressSet;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String operator = request.getHeader(OPERATOR_HEADER);
        if (operator != null && !operator.isBlank()) {
            String username = operator.trim();
            ResolvedUser resolved = resolveUser(username);
            OperatorContext.set(resolved.username, resolved.userId, resolved.role);
        }
        String machineIp = localAddressSet != null
                ? localAddressSet.preferredMachineIp()
                : LocalAddressSet.preferredMachineIpStatic();
        ClientIpContext.set(ClientIpResolver.resolveEffective(request, machineIp));
        try {
            filterChain.doFilter(request, response);
        } finally {
            ClientIpContext.clear();
            OperatorContext.clear();
        }
    }

    private ResolvedUser resolveUser(String username) {
        long now = System.currentTimeMillis();
        CachedUser cached = USER_CACHE.get(username);
        if (cached != null && now - cached.cachedAt < CACHE_TTL_MS) {
            return new ResolvedUser(username, cached.userId, cached.role);
        }
        Long userId = OperatorContext.DEFAULT_USER_ID;
        String role = "ADMIN";
        if (userAccountMapper != null) {
            try {
                UserAccount account = userAccountMapper.selectOne(
                        new LambdaQueryWrapper<UserAccount>()
                                .eq(UserAccount::getUsername, username)
                                .last("LIMIT 1"));
                if (account != null) {
                    if (account.getId() != null) userId = account.getId();
                    if (account.getRole() != null && !account.getRole().isBlank()) role = account.getRole();
                }
            } catch (Exception ignored) {
                // ci_user 表未初始化或查询失败时回退 admin，避免阻塞请求
            }
        }
        USER_CACHE.put(username, new CachedUser(userId, role, now));
        return new ResolvedUser(username, userId, role);
    }

    private record ResolvedUser(String username, Long userId, String role) {}

    private record CachedUser(Long userId, String role, long cachedAt) {}
}
