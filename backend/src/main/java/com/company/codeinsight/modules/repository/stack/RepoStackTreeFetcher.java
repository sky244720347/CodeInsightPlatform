package com.company.codeinsight.modules.repository.stack;

import com.company.codeinsight.common.util.DirectoryCleanupUtil;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 轻量取相对路径列表：本地列目录，或 JGit depth=1 bare clone + TreeWalk。
 * <p>远程 clone 默认<strong>不</strong>在方法内删除 workDir（由整轮统一清理）。</p>
 */
@Slf4j
public final class RepoStackTreeFetcher {

    private static final int MAX_PATHS = 80_000;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", "out",
            ".idea", ".vscode", "vendor", "__pycache__", ".gradle", ".svn");

    private RepoStackTreeFetcher() {
    }

    /**
     * @param workDir           远程 clone 目标目录（整轮 run 下的 repo 子目录）
     * @param deleteWorkDirAfter 为 true 时在 finally 删除 workDir（整轮模式传 false）
     */
    public static List<String> fetchPaths(String gitUrl, String branch, String username, String password,
                                          Path workDir, long timeoutMs, boolean deleteWorkDirAfter)
            throws Exception {
        if (RepoGitUrlKind.isExistingLocalDirectory(gitUrl)) {
            return listLocalPaths(Path.of(gitUrl.trim()));
        }
        return fetchRemoteWithTimeout(gitUrl, branch, username, password, workDir, timeoutMs, deleteWorkDirAfter);
    }

    private static List<String> fetchRemoteWithTimeout(String gitUrl, String branch, String username, String password,
                                                       Path workDir, long timeoutMs, boolean deleteWorkDirAfter)
            throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stack-probe-tree");
            t.setDaemon(true);
            return t;
        });
        try {
            Callable<List<String>> task = () -> fetchRemote(gitUrl, branch, username, password, workDir);
            Future<List<String>> future = single.submit(task);
            try {
                return future.get(Math.max(1_000L, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new TimeoutException("stack probe tree timeout " + timeoutMs + "ms url=" + gitUrl);
            }
        } finally {
            single.shutdownNow();
            if (deleteWorkDirAfter) {
                try {
                    DirectoryCleanupUtil.deleteRecursively(workDir);
                } catch (IOException cleanupEx) {
                    log.warn("清理 stack probe 工作目录失败 {}: {}", workDir, cleanupEx.getMessage());
                }
            }
        }
    }

    private static List<String> fetchRemote(String gitUrl, String branch, String username, String password,
                                            Path workDir) throws Exception {
        if (Files.exists(workDir)) {
            DirectoryCleanupUtil.deleteRecursively(workDir);
        }
        Files.createDirectories(workDir.getParent() == null ? workDir : workDir.getParent());

        var clone = Git.cloneRepository()
                .setURI(gitUrl.trim())
                .setDirectory(workDir.toFile())
                .setBare(true)
                .setCloneAllBranches(false)
                .setDepth(1);
        if (StringUtils.hasText(branch)) {
            clone.setBranch(branch.trim());
        }
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            clone.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password));
        }

        try (Git git = clone.call()) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            if (head == null && StringUtils.hasText(branch)) {
                head = repo.resolve("refs/heads/" + branch.trim());
            }
            if (head == null) {
                head = repo.resolve("refs/heads/master");
            }
            if (head == null) {
                head = repo.resolve("refs/heads/main");
            }
            if (head == null) {
                throw new IllegalStateException("无法解析 HEAD: " + gitUrl);
            }
            List<String> paths = new ArrayList<>();
            try (RevWalk revWalk = new RevWalk(repo);
                 TreeWalk treeWalk = new TreeWalk(repo)) {
                RevCommit commit = revWalk.parseCommit(head);
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    if (shouldSkipPath(path)) {
                        continue;
                    }
                    paths.add(path);
                    if (paths.size() >= MAX_PATHS) {
                        break;
                    }
                }
            }
            return paths;
        }
    }

    static List<String> listLocalPaths(Path root) throws IOException {
        List<String> paths = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path rel = root.relativize(file);
                String path = rel.toString().replace('\\', '/');
                if (!shouldSkipPath(path)) {
                    paths.add(path);
                }
                return paths.size() >= MAX_PATHS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        return paths;
    }

    private static boolean shouldSkipPath(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String skip : SKIP_DIRS) {
            if (lower.equals(skip) || lower.startsWith(skip + "/") || lower.contains("/" + skip + "/")) {
                return true;
            }
        }
        return false;
    }
}
