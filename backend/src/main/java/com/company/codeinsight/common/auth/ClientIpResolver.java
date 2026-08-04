package com.company.codeinsight.common.auth;

import com.company.codeinsight.common.net.LocalAddressSet;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 解析客户端 / 本机可辨识 IP。
 * <p>本机 Vite 代理时 peer 常为 loopback，此时回落到本机网卡 IP，便于操作日志区分不同机器。</p>
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    /**
     * HTTP 有效 IP：非回环 peer 直接用；回环 / 无法解析时用 {@code machineFallback}。
     */
    public static String resolveEffective(HttpServletRequest request, String machineFallback) {
        String peer = resolve(request);
        String fallback = StringUtils.hasText(machineFallback)
                ? machineFallback
                : LocalAddressSet.preferredMachineIpStatic();
        if (!StringUtils.hasText(peer) || LocalAddressSet.isLoopback(peer)) {
            return truncate(fallback, 64);
        }
        String normalized = LocalAddressSet.normalize(peer);
        return truncate(StringUtils.hasText(normalized) ? normalized : peer, 64);
    }

    /**
     * @return 解析到的原始 peer IP；无法解析时返回 null（不做本机回落）
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = firstHop(request.getHeader("X-Forwarded-For"));
        if (StringUtils.hasText(forwarded)) {
            return truncate(forwarded, 50);
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return truncate(realIp.trim(), 50);
        }
        String remote = request.getRemoteAddr();
        if (StringUtils.hasText(remote)) {
            return truncate(remote.trim(), 50);
        }
        return null;
    }

    private static String firstHop(String xff) {
        if (!StringUtils.hasText(xff)) {
            return null;
        }
        int comma = xff.indexOf(',');
        String first = comma >= 0 ? xff.substring(0, comma) : xff;
        first = first.trim();
        return first.isEmpty() ? null : first;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
