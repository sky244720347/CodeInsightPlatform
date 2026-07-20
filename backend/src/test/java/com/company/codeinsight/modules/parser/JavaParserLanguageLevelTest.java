package com.company.codeinsight.modules.parser;

import com.company.codeinsight.modules.parser.config.JavaParserLanguageConfig;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.service.impl.AstJavaParserService;
import com.company.codeinsight.modules.parser.service.impl.FallbackJavaParserService;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * JavaParser language level = JAVA_17 与 parseDirectory 容错回归。
 */
@DisplayName("JavaParser JAVA_17 language level")
public class JavaParserLanguageLevelTest {

    @Test
    @DisplayName("ensure 后 StaticJavaParser language level 为 JAVA_17")
    void ensureSetsJava17() {
        JavaParserLanguageConfig.ensureStaticJavaParserConfigured();
        Assertions.assertEquals(
                JavaParserLanguageConfig.TARGET,
                StaticJavaParser.getParserConfiguration().getLanguageLevel());
    }

    @Test
    @DisplayName("含 Text Block 的源文件 AST 可解析")
    void parseFileWithTextBlockSucceeds() throws IOException {
        File f = writeTemp("TextBlockSample", ".java", """
                package com.example.demo;

                public class TextBlockSample {
                    public String sql() {
                        return ""\"
                               SELECT id FROM ci_task
                               WHERE status = 'PENDING'
                               ""\";
                    }
                }
                """);

        ParsedClassInfo info = new AstJavaParserService().parseFile(f);
        Assertions.assertNotNull(info);
        Assertions.assertEquals("TextBlockSample", info.getClassName());
        Assertions.assertEquals("com.example.demo", info.getPackageName());
    }

    @Test
    @DisplayName("Fallback parseDirectory：坏文件不拖垮整目录")
    void fallbackParseDirectorySkipsBrokenFile() throws IOException {
        File dir = Files.createTempDirectory("ci-jp-ll").toFile();
        dir.deleteOnExit();

        File good = new File(dir, "GoodController.java");
        try (FileWriter w = new FileWriter(good)) {
            w.write("""
                    package com.example.demo;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class GoodController {
                        public void ok() {}
                    }
                    """);
        }
        good.deleteOnExit();

        File bad = new File(dir, "Broken.java");
        try (FileWriter w = new FileWriter(bad)) {
            w.write("package com.example.demo;\npublic class Broken { this is not valid java !!!\n");
        }
        bad.deleteOnExit();

        List<ParsedClassInfo> list = new FallbackJavaParserService().parseDirectory(dir);
        Assertions.assertFalse(list.isEmpty(), "应至少解析出合法类");
        Assertions.assertTrue(
                list.stream().anyMatch(i -> "GoodController".equals(i.getClassName())),
                "应包含 GoodController");
    }

    private File writeTemp(String prefix, String suffix, String content) throws IOException {
        File f = File.createTempFile(prefix, suffix);
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write(content);
        }
        return f;
    }
}
