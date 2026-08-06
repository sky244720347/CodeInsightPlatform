package com.company.codeinsight.modules.repository;

import com.company.codeinsight.modules.repository.service.RepoGitConnectivityService;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.SymbolicRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GitRemoteHeadPickTest {

    private static final ObjectId COMMIT_A =
            ObjectId.fromString("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final ObjectId COMMIT_B =
            ObjectId.fromString("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private static final ObjectId COMMIT_C =
            ObjectId.fromString("cccccccccccccccccccccccccccccccccccccccc");

    @Test
    void prefersConfiguredBranch() {
        Ref develop = new ObjectIdRef.Unpeeled(Ref.Storage.NETWORK, "refs/heads/develop", COMMIT_A);
        Ref master = new ObjectIdRef.Unpeeled(Ref.Storage.NETWORK, "refs/heads/master", COMMIT_B);
        assertEquals(COMMIT_A.getName(),
                RepoGitConnectivityService.pickRemoteHead(List.of(develop, master), "develop"));
    }

    @Test
    void fallsBackToSymbolicHead() {
        Ref master = new ObjectIdRef.Unpeeled(Ref.Storage.NETWORK, "refs/heads/master", COMMIT_B);
        Ref head = new SymbolicRef("HEAD", master);
        assertEquals(COMMIT_B.getName(),
                RepoGitConnectivityService.pickRemoteHead(List.of(head, master), null));
    }

    @Test
    void fallsBackToMasterThenMain() {
        Ref main = new ObjectIdRef.Unpeeled(Ref.Storage.NETWORK, "refs/heads/main", COMMIT_C);
        assertEquals(COMMIT_C.getName(),
                RepoGitConnectivityService.pickRemoteHead(List.of(main), "missing"));
    }

    @Test
    void emptyRefsReturnsNull() {
        assertNull(RepoGitConnectivityService.pickRemoteHead(List.of(), "main"));
        assertNull(RepoGitConnectivityService.pickRemoteHead(null, "main"));
    }
}
