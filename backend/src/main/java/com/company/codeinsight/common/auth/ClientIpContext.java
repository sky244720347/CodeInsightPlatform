package com.company.codeinsight.common.auth;

import com.company.codeinsight.common.net.LocalAddressSet;

/**
 * 当前请求线程的有效客户端 / 本机 IP（由 {@link OperatorHeaderFilter} 写入）。
 * <p>无 HTTP 上下文时 {@link #get()} 返回本机网卡可辨识 IP（区分不同机器），不再写 {@code scheduler}。</p>
 */
public final class ClientIpContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ClientIpContext() {
    }

    public static void set(String ip) {
        if (ip == null || ip.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(ip.trim());
        }
    }

    /**
     * @return 请求有效 IP；未设置时返回本机 {@link LocalAddressSet#preferredMachineIpStatic()}
     */
    public static String get() {
        String v = CURRENT.get();
        return (v == null || v.isBlank()) ? LocalAddressSet.preferredMachineIpStatic() : v;
    }

    /** 是否存在显式绑定的请求 IP（调度线程通常为 false） */
    public static boolean isPresent() {
        String v = CURRENT.get();
        return v != null && !v.isBlank();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
