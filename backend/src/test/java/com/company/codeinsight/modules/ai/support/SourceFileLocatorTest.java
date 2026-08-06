package com.company.codeinsight.modules.ai.support;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class SourceFileLocatorTest {

    @TempDir
    Path temp;

    @Test
    void firstExisting_skipsMissingAndPicksRealFile() throws Exception {
        Path java = temp.resolve("src/main/java/com/demo/Foo.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "class Foo {}");

        File root = temp.toFile();
        String hit = SourceFileLocator.firstExisting(
                List.of("src/main/java/com/demo/Missing.java",
                        "src/main/java/com/demo/Foo.java"),
                root,
                null);
        Assertions.assertEquals("src/main/java/com/demo/Foo.java", hit);
    }

    @Test
    void findBySimpleName_locatesUnderNestedModule() throws Exception {
        Path java = temp.resolve("module-a/src/main/java/com/demo/Bar.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "package com.demo; class Bar {}");

        List<String> hits = SourceFileLocator.findBySimpleName(
                temp.toFile(), "com.demo.Bar", null, 5);
        Assertions.assertFalse(hits.isEmpty());
        Assertions.assertTrue(hits.get(0).endsWith("Bar.java"));
    }

    @Test
    void inferMavenMainPath_andClassAnchor() {
        Assertions.assertEquals(
                "src/main/java/com/demo/Baz.java",
                SourceFileLocator.inferMavenMainPath("com.demo.Baz"));
        Assertions.assertTrue(SourceFileLocator.isClassAnchorSignature(
                SourceFileLocator.CLASS_ANCHOR_SIGNATURE));
        Assertions.assertFalse(SourceFileLocator.isClassAnchorSignature("list()"));
    }
}
