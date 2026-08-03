package com.company.codeinsight.modules.scanner;

import com.company.codeinsight.modules.scanner.support.GitCloneRetrySupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.NoSuchFileException;

class GitCloneRetrySupportTest {

    @Test
    void retryableNasErrors() {
        Assertions.assertTrue(GitCloneRetrySupport.isRetryable(new RuntimeException("Stale file handle")));
        Assertions.assertTrue(GitCloneRetrySupport.isRetryable(new RuntimeException("No such file or directory")));
        Assertions.assertTrue(GitCloneRetrySupport.isRetryable(
                new RuntimeException("Missing unknown 00e81864c6840ab9a9bd31d22f66ddb20e23")));
        Assertions.assertTrue(GitCloneRetrySupport.isRetryable(new NoSuchFileException("/tmp/x")));
    }

    @Test
    void nonRetryableAuthAndMissingRepo() {
        Assertions.assertFalse(GitCloneRetrySupport.isRetryable(
                new FakeTransportException("not authorized")));
        Assertions.assertFalse(GitCloneRetrySupport.isRetryable(
                new FakeNoRemoteRepositoryException("repository not found")));
        Assertions.assertFalse(GitCloneRetrySupport.isRetryable(
                new FakeAuthenticationFailedException("auth fail")));
    }

    @Test
    void backoffSchedule() {
        Assertions.assertEquals(2_000L, GitCloneRetrySupport.backoffMillisAfterAttempt(1));
        Assertions.assertEquals(5_000L, GitCloneRetrySupport.backoffMillisAfterAttempt(2));
        Assertions.assertEquals(5_000L, GitCloneRetrySupport.backoffMillisAfterAttempt(3));
    }

    /** 名称含 TransportException，触发文案判定 */
    private static final class FakeTransportException extends RuntimeException {
        FakeTransportException(String message) {
            super(message);
        }
    }

    private static final class FakeNoRemoteRepositoryException extends RuntimeException {
        FakeNoRemoteRepositoryException(String message) {
            super(message);
        }
    }

    private static final class FakeAuthenticationFailedException extends RuntimeException {
        FakeAuthenticationFailedException(String message) {
            super(message);
        }
    }
}
