package com.company.codeinsight.modules.scanner;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.scanner.model.ScanScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ScanScope 两段过滤：先唯一 scan_root，再相对根 exclude。
 */
class ScanScopeTest {

    @TempDir
    Path tempDir;

    @Test
    void twoStageFilter_rootA_excludeD() throws Exception {
        Path a = tempDir.resolve("a");
        Files.createDirectories(a.resolve("b/c"));
        Files.createDirectories(a.resolve("d/e"));
        Files.createDirectories(tempDir.resolve("b/b/c"));
        Files.writeString(a.resolve("b/c/Keep.java"), "class Keep {}");
        Files.writeString(a.resolve("d/e/Drop.java"), "class Drop {}");
        Files.writeString(tempDir.resolve("b/b/c/Out.java"), "class Out {}");

        CodeRepository repo = new CodeRepository();
        repo.setScanRoot("/a");
        repo.setExcludeDirs("/d");

        ScanScope scope = ScanScope.from(repo, tempDir.toFile());

        assertTrue(scope.accepts("a/b/c/Keep.java"));
        assertFalse(scope.accepts("a/d/e/Drop.java"));
        assertFalse(scope.accepts("b/b/c/Out.java"));
        assertTrue(scope.acceptsDirectory("a/b"));
        assertFalse(scope.acceptsDirectory("a/d"));
        assertFalse(scope.acceptsDirectory("b"));
    }

    @Test
    void scanRootSlash_meansWholeRepo() throws Exception {
        Files.createDirectories(tempDir.resolve("x"));
        Files.writeString(tempDir.resolve("x/A.java"), "class A {}");

        CodeRepository repo = new CodeRepository();
        repo.setScanRoot("/");
        repo.setExcludeDirs("x");

        ScanScope scope = ScanScope.from(repo, tempDir.toFile());
        assertEquals("", scope.getScanRootRel());
        assertEquals(tempDir.toFile().getCanonicalFile(), scope.getEffectiveRoot().getCanonicalFile());
        assertFalse(scope.accepts("x/A.java"));
    }

    @Test
    void missingScanRoot_fails() {
        CodeRepository repo = new CodeRepository();
        repo.setScanRoot("/missing-mod");
        assertThrows(BusinessException.class, () -> ScanScope.from(repo, tempDir.toFile()));
    }

    @Test
    void excludeJava_ignored() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/A.java"), "class A {}");
        Files.writeString(tempDir.resolve("src/note.md"), "# n");

        CodeRepository repo = new CodeRepository();
        repo.setScanRoot("/");
        repo.setExcludeFileTypes(".java,.md");

        ScanScope scope = ScanScope.from(repo, tempDir.toFile());
        assertTrue(scope.accepts("src/A.java"), ".java 排除应被忽略");
        assertFalse(scope.accepts("src/note.md"));
    }

    @Test
    void filterPaths_keepsInScopeOnly() throws Exception {
        Files.createDirectories(tempDir.resolve("mod-a"));
        Files.createDirectories(tempDir.resolve("mod-b"));

        CodeRepository repo = new CodeRepository();
        repo.setScanRoot("mod-a");

        ScanScope scope = ScanScope.from(repo, tempDir.toFile());
        Set<String> filtered = scope.filterPaths(Set.of(
                "mod-a/Foo.java",
                "mod-b/Bar.java",
                "mod-a/target/X.java"
        ));
        assertEquals(Set.of("mod-a/Foo.java"), filtered);
    }

    @Test
    void normalizeScanRoot_variants() {
        assertEquals("", ScanScope.normalizeScanRoot("/"));
        assertEquals("", ScanScope.normalizeScanRoot("."));
        assertEquals("", ScanScope.normalizeScanRoot(""));
        assertEquals("a", ScanScope.normalizeScanRoot("/a/"));
        assertEquals("rms-service", ScanScope.normalizeScanRoot("\\rms-service\\"));
    }

    @Test
    void matchesExclude_segmentAndPrefix() {
        assertTrue(ScanScope.matchesExclude("d/e", "d"));
        assertTrue(ScanScope.matchesExclude("b/d/e", "d"));
        assertTrue(ScanScope.matchesExclude("src/test/java", "src/test"));
        assertFalse(ScanScope.matchesExclude("b/c", "d"));
        assertFalse(ScanScope.matchesExclude("contest", "test"));
    }
}
