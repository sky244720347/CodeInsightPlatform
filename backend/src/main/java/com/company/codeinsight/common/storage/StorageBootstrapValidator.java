package com.company.codeinsight.common.storage;

import com.company.codeinsight.common.config.CodeInsightEnvProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动时校验存储两根（runtime-root + releases-root）与集群/Redis 约束（见 cluster-shared-storage-design §8）。
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class StorageBootstrapValidator implements ApplicationRunner {

    private final CodeInsightEnvProperties envProperties;
    private final EnvStorageResolver storageResolver;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureWritable(storageResolver.getActiveRuntimeRoot(), "runtimeRoot");
        ensureWritable(storageResolver.getActiveReleasesRoot(), "releasesRoot");

        if (storageResolver.getActiveRuntimeRoot().equals(storageResolver.getActiveReleasesRoot())) {
            log.warn("runtimeRoot 与 releasesRoot 相同（{}），不推荐但允许", storageResolver.getActiveRuntimeRoot());
        }

        if (!envProperties.isDev()) {
            pingRedisOrFail();
        }
    }

    private void ensureWritable(Path root, String label) {
        try {
            Files.createDirectories(root);
            Path probe = root.resolve(".ci-write-probe");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            throw new IllegalStateException("存储根不可写: " + label + "=" + root, e);
        }
    }

    private void pingRedisOrFail() {
        try {
            stringRedisTemplate.getConnectionFactory().getConnection().ping();
        } catch (Exception e) {
            throw new IllegalStateException("非 dev 环境要求 Redis 可达（一律集群），ping 失败", e);
        }
    }
}
