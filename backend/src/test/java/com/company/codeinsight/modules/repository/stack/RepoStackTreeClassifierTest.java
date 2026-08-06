package com.company.codeinsight.modules.repository.stack;

import com.company.codeinsight.modules.repository.model.RepoType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoStackTreeClassifierTest {

    @Test
    void classifiesJavaBackend() {
        var r = RepoStackTreeClassifier.classify(List.of(
                "pom.xml",
                "src/main/java/com/acme/App.java",
                "src/main/java/com/acme/Svc.java",
                "src/main/java/com/acme/Repo.java"));
        assertNotNull(r);
        assertEquals(RepoType.BACKEND.getCode(), r.getRepoType());
        assertEquals("Java", r.getTechStack());
        assertTrue(r.isHighEnough());
    }

    @Test
    void classifiesVueFrontend() {
        var r = RepoStackTreeClassifier.classify(List.of(
                "package.json",
                "src/App.vue",
                "src/views/Home.vue",
                "src/components/X.vue"));
        assertNotNull(r);
        assertEquals(RepoType.FRONTEND.getCode(), r.getRepoType());
        assertEquals("Vue", r.getTechStack());
        assertTrue(r.isHighEnough());
    }

    @Test
    void classifiesReactViaTsx() {
        var r = RepoStackTreeClassifier.classify(List.of(
                "package.json",
                "src/App.tsx",
                "src/pages/A.tsx",
                "src/pages/B.tsx",
                "src/pages/C.tsx"));
        assertNotNull(r);
        assertEquals(RepoType.FRONTEND.getCode(), r.getRepoType());
        assertEquals("React", r.getTechStack());
    }

    @Test
    void conflictMonorepoYieldsLow() {
        var r = RepoStackTreeClassifier.classify(List.of(
                "pom.xml",
                "src/main/java/A.java",
                "src/main/java/B.java",
                "src/main/java/C.java",
                "frontend/package.json",
                "frontend/src/App.vue",
                "frontend/src/Home.vue",
                "frontend/src/X.vue"));
        assertNotNull(r);
        assertEquals(RepoStackTreeClassifier.Confidence.LOW, r.getConfidence());
        assertNull(r.getRepoType());
    }

    @Test
    void emptyReturnsNull() {
        assertNull(RepoStackTreeClassifier.classify(List.of()));
        assertNull(RepoStackTreeClassifier.classify(null));
    }
}
