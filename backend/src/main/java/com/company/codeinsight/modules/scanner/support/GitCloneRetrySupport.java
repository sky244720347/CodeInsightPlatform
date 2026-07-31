package com.company.codeinsight.modules.scanner.support;

/**
 * JGit clone 失败是否可重试的判定（NAS 瞬时 IO / 对象缺失等）。
 * <p>认证失败、仓库不存在等确定性错误不可重试。</p>
 */
public final class GitCloneRetrySupport {

    /** 首次 + 重试，合计最大 attempt 数 */
    public static final int MAX_ATTEMPTS = 3;

    private GitCloneRetrySupport() {
    }

    /**
     * 第 {@code attempt} 次失败后、准备下一次重试前的退避毫秒。
     * attempt=1 → 2s；attempt=2 → 5s；其它 → 5s。
     */
    public static long backoffMillisAfterAttempt(int attempt) {
        if (attempt <= 1) {
            return 2_000L;
        }
        return 5_000L;
    }

    public static boolean isRetryable(Throwable error) {
        if (error == null || isNonRetryable(error)) {
            return false;
        }
        Throwable cur = error;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("stale file handle")
                        || lower.contains("no such file or directory")
                        || lower.contains("missing unknown")) {
                    return true;
                }
            }
            String simple = cur.getClass().getSimpleName();
            if ("NoSuchFileException".equals(simple)
                    || "FileSystemException".equals(simple)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean isNonRetryable(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            String name = cur.getClass().getName();
            if (name.contains("TransportException")
                    || name.contains("NoRemoteRepositoryException")
                    || name.contains("InvalidRemoteException")
                    || name.contains("AuthenticationFailedException")
                    || name.contains("ServiceDeniedException")) {
                // TransportException 既可能是认证也可能是网络；再看文案
                String msg = cur.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase();
                    if (lower.contains("not authorized")
                            || lower.contains("authentication")
                            || lower.contains("auth fail")
                            || lower.contains("invalid credentials")
                            || lower.contains("401")
                            || lower.contains("403")
                            || lower.contains("repository not found")
                            || lower.contains("does not exist")
                            || lower.contains("remote hung up")
                            || lower.contains("access denied")) {
                        return true;
                    }
                }
                // NoRemote / InvalidRemote / Auth 类名本身即不可重试
                if (!name.contains("TransportException")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}
