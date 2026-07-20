package com.company.codeinsight.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * NAS / Windows 友好的递归目录清理。
 * <p>{@link java.io.File#delete()} 在只读位、文件锁、NAS 延迟下会静默失败，
 * 导致 JGit clone 报 destination already exists。本工具清只读位、按深度倒序删除，并做有限次重试。</p>
 */
public final class DirectoryCleanupUtil {

    private static final Logger log = LoggerFactory.getLogger(DirectoryCleanupUtil.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_SLEEP_MS = 200L;

    private DirectoryCleanupUtil() {
    }

    /**
     * 删除目录（或其本身是文件时删除该文件）。不存在则视为成功。
     *
     * @throws IOException 重试后目录仍存在或删除过程中不可恢复错误
     */
    public static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                deleteOnce(root);
                if (!Files.exists(root)) {
                    return;
                }
                last = new IOException("目录删除后仍存在: " + root.toAbsolutePath());
            } catch (IOException e) {
                last = e;
                log.warn("DirectoryCleanupUtil 第 {}/{} 次删除失败 path={}: {}",
                        attempt, MAX_ATTEMPTS, root.toAbsolutePath(), e.getMessage());
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_SLEEP_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("删除目录被中断: " + root, ie);
                }
            }
        }
        throw last != null ? last : new IOException("无法删除目录: " + root);
    }

    /**
     * 同 {@link #deleteRecursively(Path)}，失败时包装为 RuntimeException，便于非检查上下文调用。
     */
    public static void deleteRecursivelyUnchecked(Path root) {
        try {
            deleteRecursively(root);
        } catch (IOException e) {
            throw new IllegalStateException("清理目录失败: " + (root == null ? null : root.toAbsolutePath()), e);
        }
    }

    private static void deleteOnce(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        // 先清只读再删，避免 Windows/NAS 上 File.delete 静默失败
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                clearReadonly(file);
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                clearReadonly(dir);
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void clearReadonly(Path path) {
        try {
            Files.setAttribute(path, "dos:readonly", false);
        } catch (Exception ignored) {
            // 非 Windows / 不支持 dos 属性时忽略
        }
        path.toFile().setWritable(true);
    }

    /** 目录不存在或为空返回 true。 */
    public static boolean isAbsentOrEmpty(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return true;
        }
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /** 用于调试：统计残留条目数（失败时返回 -1）。 */
    public static long countEntries(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.count();
        } catch (IOException e) {
            return -1;
        }
    }

    /** Comparator 备用：深度大的先删（若改用 stream 排序删除）。 */
    @SuppressWarnings("unused")
    private static Comparator<Path> deepestFirst() {
        return Comparator.comparingInt(Path::getNameCount).reversed();
    }
}
