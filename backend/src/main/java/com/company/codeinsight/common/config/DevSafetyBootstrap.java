package com.company.codeinsight.common.config;

import com.company.codeinsight.common.net.LocalAddressSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.stream.Collectors;

/**
 * Dev 启动告警：提醒禁用孤儿接管与本机 IP 派发，并打印脱敏 JDBC / 本机 IP。
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class DevSafetyBootstrap {

    private final CodeInsightEnvProperties envProperties;
    private final LocalAddressSet localAddressSet;
    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!envProperties.isDev()) {
            return;
        }
        log.warn("DEV 安全模式：禁用孤儿接管；仅执行 is_dev=true 的任务；请确认未误连共享库");
        log.info("DEV safety preferredMachineIp={} localIps=[{}] jdbcUrl={}",
                localAddressSet.preferredMachineIp(),
                localAddressSet.snapshot().stream().limit(16).collect(Collectors.joining(",")),
                maskJdbcUrl());
    }

    private String maskJdbcUrl() {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData meta = c.getMetaData();
            String url = meta != null ? meta.getURL() : null;
            if (url == null || url.isBlank()) {
                return "(unknown)";
            }
            return url.replaceAll("(?i)([?&]password=)[^&]*", "$1***");
        } catch (Exception e) {
            return "(unavailable: " + e.getMessage() + ")";
        }
    }
}
