package com.company.codeinsight.common.net;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 本 JVM 所在机器的本机地址集合（网卡 IPv4/IPv6 + loopback）。
 * <p>提供 {@link #preferredMachineIp()} 作为本机可辨识地址（优先非回环 IPv4），
 * 供操作日志在 peer 为 loopback 时回落。</p>
 */
@Slf4j
@Component
public class LocalAddressSet {

    private volatile Set<String> addresses = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private volatile String preferredMachineIp = "127.0.0.1";

    /** 供静态工具（无 Spring 注入时）读取；由 {@link #init()} 刷新 */
    private static volatile String PREFERRED_STATIC = "127.0.0.1";

    @PostConstruct
    public void init() {
        Set<String> found = new LinkedHashSet<>();
        found.add("127.0.0.1");
        found.add("::1");
        found.add("0:0:0:0:0:0:0:1");
        String preferredV4 = null;
        String preferredAny = null;
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics != null) {
                while (nics.hasMoreElements()) {
                    NetworkInterface nic = nics.nextElement();
                    Enumeration<InetAddress> addrs = nic.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        String normalized = normalize(addr.getHostAddress());
                        if (normalized == null) {
                            continue;
                        }
                        found.add(normalized);
                        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                            continue;
                        }
                        if (preferredAny == null) {
                            preferredAny = normalized;
                        }
                        if (preferredV4 == null && normalized.indexOf(':') < 0) {
                            preferredV4 = normalized;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            log.warn("枚举本机网卡地址失败，仅保留 loopback: {}", e.getMessage());
        }
        this.addresses = Collections.unmodifiableSet(found);
        String preferred = preferredV4 != null ? preferredV4
                : (preferredAny != null ? preferredAny : "127.0.0.1");
        this.preferredMachineIp = preferred;
        PREFERRED_STATIC = preferred;
        log.info("LocalAddressSet loaded size={} preferredMachineIp={} sample={}",
                found.size(), preferred, found.stream().limit(8).toList());
    }

    /** 当前本机地址快照（不可变） */
    public Set<String> snapshot() {
        return addresses;
    }

    /**
     * 本机可辨识 IP：优先非回环 IPv4，否则非回环 IPv6，再否则 127.0.0.1。
     * 用于区分不同开发机（本机 Vite 代理 peer 常为 loopback）。
     */
    public String preferredMachineIp() {
        return preferredMachineIp;
    }

    public static String preferredMachineIpStatic() {
        return PREFERRED_STATIC;
    }

    public static boolean isLoopback(String ip) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }
        String n = normalize(ip);
        if (n == null) {
            return false;
        }
        return "127.0.0.1".equals(n)
                || "0:0:0:0:0:0:0:1".equals(n)
                || "::1".equals(n)
                || n.startsWith("127.");
    }

    /**
     * {@code ip} 是否属于本机。null / blank → false。
     */
    public boolean matchesLocal(String ip) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }
        String raw = ip.trim();
        if ("sys".equalsIgnoreCase(raw) || "scheduler".equalsIgnoreCase(raw)) {
            return false;
        }
        String normalized = normalize(raw);
        if (normalized == null) {
            return false;
        }
        if (addresses.contains(normalized)) {
            return true;
        }
        return addresses.contains(raw.toLowerCase(Locale.ROOT));
    }

    /**
     * 规范化 IP：去掉 IPv6 zone id，经 {@link InetAddress} 得到 canonical host address。
     */
    public static String normalize(String ip) {
        if (!StringUtils.hasText(ip)) {
            return null;
        }
        String s = ip.trim();
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        try {
            return InetAddress.getByName(s).getHostAddress().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return s.toLowerCase(Locale.ROOT);
        }
    }
}
