package com.company.codeinsight.modules.repository.stack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoGitUrlKindTest {

    @TempDir
    Path tempDir;

    @Test
    void remoteUrlsNotTreatedAsLocal() {
        assertTrue(RepoGitUrlKind.isRemoteUrl("https://gitee.com/heweinan/code-insight_inc_demo"));
        assertTrue(RepoGitUrlKind.isRemoteUrl("http://example.com/a.git"));
        assertTrue(RepoGitUrlKind.isRemoteUrl("git@gitee.com:user/repo.git"));
        assertFalse(RepoGitUrlKind.isExistingLocalDirectory("https://gitee.com/heweinan/code-insight_inc_demo"));
        assertFalse(RepoGitUrlKind.isExistingLocalDirectory("https://code.example.com/group/repo-name.git"));
    }

    @Test
    void existingLocalDirDetected() {
        assertTrue(RepoGitUrlKind.isExistingLocalDirectory(tempDir.toString()));
        assertFalse(RepoGitUrlKind.isRemoteUrl(tempDir.toString()));
    }
}
