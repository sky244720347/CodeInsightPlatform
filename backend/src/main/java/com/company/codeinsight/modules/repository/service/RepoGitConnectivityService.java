package com.company.codeinsight.modules.repository.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.codeinsight.common.config.AsyncExecutorConfig;
import com.company.codeinsight.common.config.RepoGitCheckProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.exception.ErrorCode;
import com.company.codeinsight.common.util.DbStringLimits;
import com.company.codeinsight.modules.repository.dto.GitBatchCheckAccepted;
import com.company.codeinsight.modules.repository.dto.GitConnectivityResult;
import com.company.codeinsight.modules.repository.dto.GitConnectivitySummary;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 仓库 Git 连通性探测：超时 ls-remote、落库、按系统异步批量、全库有限并发扫描。
 */
@Slf4j
@Service
public class RepoGitConnectivityService {

    public static final int REACHABLE = 1;
    public static final int UNREACHABLE = 0;

    private final CodeRepositoryMapper codeRepositoryMapper;
    private final RepoGitCheckProperties properties;
    private final Executor repoGitCheckExecutor;

    public RepoGitConnectivityService(
            CodeRepositoryMapper codeRepositoryMapper,
            RepoGitCheckProperties properties,
            @Qualifier(AsyncExecutorConfig.REPO_GIT_CHECK_EXECUTOR) Executor repoGitCheckExecutor) {
        this.codeRepositoryMapper = codeRepositoryMapper;
        this.properties = properties;
        this.repoGitCheckExecutor = repoGitCheckExecutor;
    }

    /**
     * 任务门禁：必须已检测且连通。
     */
    public void assertReachableForTask(CodeRepository repository) {
        if (repository == null) {
            throw new BusinessException("所选代码库不存在");
        }
        Integer flag = repository.getGitReachable();
        if (flag == null) {
            throw new BusinessException(ErrorCode.GIT_UNREACHABLE,
                    "仓库尚未完成 Git 连通性检测，暂不可下发任务；请稍候或手动「测试 Git」");
        }
        if (flag != REACHABLE) {
            String detail = StringUtils.hasText(repository.getGitCheckMsg())
                    ? repository.getGitCheckMsg()
                    : "请检查地址与凭证后重试检测";
            throw new BusinessException(ErrorCode.GIT_UNREACHABLE,
                    "Git 仓库连通失败，暂不可下发任务；" + detail);
        }
    }

    public GitConnectivitySummary summarize() {
        List<CodeRepository> all = codeRepositoryMapper.selectList(
                new LambdaQueryWrapper<CodeRepository>().select(
                        CodeRepository::getId, CodeRepository::getGitReachable));
        long total = all.size();
        long reachable = 0;
        long unreachable = 0;
        long unchecked = 0;
        for (CodeRepository r : all) {
            Integer flag = r.getGitReachable();
            if (flag == null) {
                unchecked++;
            } else if (flag == REACHABLE) {
                reachable++;
            } else {
                unreachable++;
            }
        }
        return GitConnectivitySummary.builder()
                .total(total)
                .reachable(reachable)
                .unreachable(unreachable)
                .unchecked(unchecked)
                .build();
    }

    /** 已保存仓库：探测并落库 */
    public GitConnectivityResult checkAndPersist(Long repositoryId) {
        CodeRepository repo = codeRepositoryMapper.selectById(repositoryId);
        if (repo == null) {
            throw new BusinessException("代码库配置不存在");
        }
        return checkAndPersist(repo);
    }

    public GitConnectivityResult checkAndPersist(CodeRepository repo) {
        GitConnectivityResult result = probe(
                repo.getId(),
                repo.getGitUrl(),
                repo.getUsername(),
                repo.getPassword());
        // 超时等不确定结论不写成「不通」，避免启动抖动把未检测误标红
        if (!result.isInconclusive()) {
            persist(repo.getId(), result);
        } else {
            log.info("Git check inconclusive for repo #{}: {} (status unchanged)",
                    repo.getId(), result.getMessage());
        }
        return result;
    }

    /** 未保存参数探测（不落库） */
    public GitConnectivityResult probeOnly(String gitUrl, String username, String password) {
        return probe(null, gitUrl, username, password);
    }

    /**
     * 受理按系统异步批量检测。
     */
    public GitBatchCheckAccepted submitBatchForSystem(Long systemId) {
        if (systemId == null) {
            throw new BusinessException("systemId 必填");
        }
        List<CodeRepository> repos = codeRepositoryMapper.selectList(
                new LambdaQueryWrapper<CodeRepository>()
                        .eq(CodeRepository::getSystemId, systemId)
                        .orderByAsc(CodeRepository::getId));
        if (repos.isEmpty()) {
            return GitBatchCheckAccepted.builder()
                    .accepted(true)
                    .systemId(systemId)
                    .repoCount(0)
                    .message("该系统下无仓库")
                    .build();
        }
        List<CodeRepository> snapshot = List.copyOf(repos);
        repoGitCheckExecutor.execute(() -> checkAllLimited(snapshot));
        return GitBatchCheckAccepted.builder()
                .accepted(true)
                .systemId(systemId)
                .repoCount(snapshot.size())
                .message("已提交后台检测")
                .build();
    }

    /** 全库有限并发探测（供 Leader 调度调用） */
    public void checkAllRepositories() {
        checkAllRepositories(null);
    }

    /**
     * @param heartbeat 可选：每完成一个仓库后回调（用于 Leader 锁续租）
     */
    public void checkAllRepositories(Runnable heartbeat) {
        List<CodeRepository> repos = codeRepositoryMapper.selectList(
                new LambdaQueryWrapper<CodeRepository>().orderByAsc(CodeRepository::getId));
        checkAllLimited(repos, heartbeat);
    }

    private void checkAllLimited(List<CodeRepository> repos) {
        checkAllLimited(repos, null);
    }

    private void checkAllLimited(List<CodeRepository> repos, Runnable heartbeat) {
        if (repos == null || repos.isEmpty()) {
            return;
        }
        int concurrency = Math.max(1, properties.getGitCheckConcurrency());
        Semaphore sem = new Semaphore(concurrency);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        List<CompletableFuture<Void>> futures = repos.stream()
                .map(repo -> CompletableFuture.runAsync(() -> {
                    try {
                        sem.acquire();
                        try {
                            GitConnectivityResult r = checkAndPersist(repo);
                            if (r.isReachable()) {
                                ok.incrementAndGet();
                            } else {
                                fail.incrementAndGet();
                            }
                        } finally {
                            sem.release();
                            if (heartbeat != null) {
                                try {
                                    heartbeat.run();
                                } catch (Exception ignored) {
                                    // ignore renew failures
                                }
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        fail.incrementAndGet();
                        log.warn("Git check failed for repo #{}: {}", repo.getId(), e.toString());
                    }
                }, repoGitCheckExecutor))
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        log.info("Git connectivity sweep done: total={} reachable={} unreachable={}",
                repos.size(), ok.get(), fail.get());
    }

    private void persist(Long repositoryId, GitConnectivityResult result) {
        if (repositoryId == null || result == null) {
            return;
        }
        codeRepositoryMapper.update(null, new LambdaUpdateWrapper<CodeRepository>()
                .eq(CodeRepository::getId, repositoryId)
                .set(CodeRepository::getGitReachable, result.isReachable() ? REACHABLE : UNREACHABLE)
                .set(CodeRepository::getGitCheckedAt, result.getCheckedAt())
                .set(CodeRepository::getGitCheckMsg, DbStringLimits.truncate(result.getMessage(), 255)));
    }

    private GitConnectivityResult probe(Long repositoryId, String gitUrl, String username, String password) {
        LocalDateTime now = LocalDateTime.now();
        if (!StringUtils.hasText(gitUrl)) {
            return GitConnectivityResult.builder()
                    .repositoryId(repositoryId)
                    .reachable(false)
                    .checkedAt(now)
                    .message("Git 地址为空")
                    .build();
        }
        int timeoutSec = Math.max(1, properties.getGitCheckTimeoutSeconds());
        ExecutorService single = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "git-ls-remote");
            t.setDaemon(true);
            return t;
        });
        try {
            Boolean ok = single.submit(() -> lsRemote(gitUrl.trim(), username, password))
                    .get(timeoutSec, TimeUnit.SECONDS);
            boolean reachable = Boolean.TRUE.equals(ok);
            return GitConnectivityResult.builder()
                    .repositoryId(repositoryId)
                    .reachable(reachable)
                    .checkedAt(now)
                    .message(reachable ? null : "ls-remote 无有效 refs 或认证失败")
                    .build();
        } catch (TimeoutException te) {
            return GitConnectivityResult.builder()
                    .repositoryId(repositoryId)
                    .reachable(false)
                    .inconclusive(true)
                    .checkedAt(now)
                    .message("检测超时（" + timeoutSec + "s），状态保持不变")
                    .build();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return GitConnectivityResult.builder()
                    .repositoryId(repositoryId)
                    .reachable(false)
                    .inconclusive(true)
                    .checkedAt(now)
                    .message("检测被中断，状态保持不变")
                    .build();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            return GitConnectivityResult.builder()
                    .repositoryId(repositoryId)
                    .reachable(false)
                    .checkedAt(now)
                    .message(DbStringLimits.truncate(msg, 255))
                    .build();
        } finally {
            single.shutdownNow();
        }
    }

    private static boolean lsRemote(String gitUrl, String username, String password) throws Exception {
        LsRemoteCommand lsRemote = Git.lsRemoteRepository().setRemote(gitUrl);
        if (StringUtils.hasText(username)) {
            lsRemote.setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                    username, password != null ? password : ""));
        }
        Collection<Ref> refs = lsRemote.call();
        return refs != null && !refs.isEmpty();
    }
}
