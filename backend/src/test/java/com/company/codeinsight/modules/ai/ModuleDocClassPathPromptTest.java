package com.company.codeinsight.modules.ai;

import com.company.codeinsight.common.util.PromptTemplateLoader;
import com.company.codeinsight.modules.ai.service.impl.AiSummaryServiceImpl;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.callchain.service.MethodCallGraphService;
import com.company.codeinsight.modules.hierarchy.entity.MethodFunctionBinding;
import com.company.codeinsight.modules.hierarchy.mapper.MethodFunctionBindingMapper;
import com.company.codeinsight.modules.hierarchy.model.FunctionDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.SubModuleDto;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 文档生成类路径权威注入 / FQ 头 — 无 Spring 单测（不依赖 PG/Redis）。
 */
@DisplayName("知识文档类路径提示词修复")
public class ModuleDocClassPathPromptTest {

    private AiSummaryServiceImpl service;
    private MethodFunctionBindingMapper bindingMapper;
    private MethodCallMapper methodCallMapper;
    private MethodCallGraphService graphService;
    private JavaParserService javaParserService;

    @BeforeEach
    void setUp() {
        service = new AiSummaryServiceImpl();
        bindingMapper = Mockito.mock(MethodFunctionBindingMapper.class);
        methodCallMapper = Mockito.mock(MethodCallMapper.class);
        graphService = Mockito.mock(MethodCallGraphService.class);
        javaParserService = Mockito.mock(JavaParserService.class);
        ReflectionTestUtils.setField(service, "methodFunctionBindingMapper", bindingMapper);
        ReflectionTestUtils.setField(service, "methodCallMapper", methodCallMapper);
        ReflectionTestUtils.setField(service, "methodCallGraphService", graphService);
        ReflectionTestUtils.setField(service, "javaParserService", javaParserService);
        ReflectionTestUtils.setField(service, "promptTemplateLoader", new PromptTemplateLoader());
    }

    @Test
    @DisplayName("module_doc_prompt.md 不再含可照抄的 com.peig.prep 示例")
    void promptResourceHasNoPeigPrepExample() throws Exception {
        String content = new String(
                new org.springframework.core.io.ClassPathResource("module_doc_prompt.md")
                        .getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        Assertions.assertFalse(content.contains("com.peig.prep"), "提示词不得再含 com.peig.prep");
        Assertions.assertTrue(content.contains("权威"), "应声明类路径权威来源");
        Assertions.assertTrue(content.contains("禁止") && content.contains("自造"), "应禁止自造包名");
    }

    @Test
    @DisplayName("file_path 可反推 FQ")
    void fqFromSourceRelativePath() {
        Assertions.assertEquals(
                "com.demo.controller.ProductController",
                AiSummaryServiceImpl.fqFromSourceRelativePath(
                        "src/main/java/com/demo/controller/ProductController.java"));
        Assertions.assertEquals(
                "com.demo.ProductService",
                AiSummaryServiceImpl.fqFromSourceRelativePath(
                        "module-a/src/main/java/com/demo/ProductService.java"));
        Assertions.assertNull(AiSummaryServiceImpl.fqFromSourceRelativePath("README.md"));
    }

    @Test
    @DisplayName("权威 class_paths / methods 来自 binding FQ")
    void authoritativeClassesAndMethodsFromBinding() {
        Long taskId = 99L;
        FunctionDto fn = new FunctionDto();
        fn.setId("f00001");
        fn.setFunctionName("商品查询");
        fn.setClassPaths(Collections.singleton("com.other.OldController"));

        MethodFunctionBinding b = new MethodFunctionBinding();
        b.setClassName("com.codeinsight.demo.controller.ProductController");
        b.setMethodSignature("listInStockProducts()");
        Mockito.when(bindingMapper.selectByTaskAndFunction(taskId, "f00001"))
                .thenReturn(Collections.singletonList(b));
        Mockito.when(graphService.resolveReachableMethods(Mockito.eq(taskId), Mockito.anySet()))
                .thenReturn(Collections.emptySet());

        List<String> classes = service.resolveAuthoritativeClasses(taskId, fn, null);
        Assertions.assertTrue(classes.contains("com.codeinsight.demo.controller.ProductController"));
        Assertions.assertTrue(classes.contains("com.other.OldController"));
        Assertions.assertEquals(2, classes.size());

        List<Map<String, String>> methods = service.resolveAuthoritativeMethods(taskId, fn, null);
        Assertions.assertEquals(1, methods.size());
        Assertions.assertEquals("com.codeinsight.demo.controller.ProductController", methods.get(0).get("classFq"));
        Assertions.assertEquals("listInStockProducts()", methods.get(0).get("methodSignature"));
    }

    @Test
    @DisplayName("scoped JSON 含 class_paths 与 methods")
    @SuppressWarnings("unchecked")
    void buildScopedHierarchyJsonIncludesClassPaths() throws Exception {
        Long taskId = 100L;
        ModuleDto m = new ModuleDto();
        m.setId("m00001");
        m.setModuleName("商品管理");
        SubModuleDto sm = new SubModuleDto();
        sm.setId("s00001");
        sm.setSubModuleName("商品查询");
        FunctionDto fn = new FunctionDto();
        fn.setId("f00001");
        fn.setFunctionName("商品查询");
        MethodFunctionBinding b = new MethodFunctionBinding();
        b.setClassName("com.demo.ProductController");
        b.setMethodSignature("list()");
        Mockito.when(bindingMapper.selectByTaskAndFunction(taskId, "f00001"))
                .thenReturn(Collections.singletonList(b));
        Mockito.when(graphService.resolveReachableMethods(Mockito.eq(taskId), Mockito.anySet()))
                .thenReturn(Collections.emptySet());

        String json = (String) ReflectionTestUtils.invokeMethod(
                service, "buildScopedHierarchyJson", taskId, m, sm, fn, null);
        Assertions.assertNotNull(json);
        Assertions.assertTrue(json.contains("class_paths"), json);
        Assertions.assertTrue(json.contains("com.demo.ProductController"), json);
        Assertions.assertTrue(json.contains("methods"), json);
        Assertions.assertTrue(json.contains("list()"), json);
    }

    @Test
    @DisplayName("方法块头含 FQ 与 package 行")
    void appendClassMethodSnippetWritesFqAndPackage() throws Exception {
        File tmp = Files.createTempDirectory("ci-doc-cp").toFile();
        File src = new File(tmp, "src/main/java/com/demo/HelloController.java");
        src.getParentFile().mkdirs();
        Files.writeString(src.toPath(), """
                package com.demo;
                public class HelloController {
                  public void hello() { }
                }
                """);

        MethodCall mc = new MethodCall();
        mc.setFilePath("src/main/java/com/demo/HelloController.java");
        mc.setClassName("HelloController");
        Mockito.when(methodCallMapper.selectList(Mockito.any()))
                .thenReturn(Collections.singletonList(mc));

        ParsedClassInfo info = new ParsedClassInfo();
        info.setPackageName("com.demo");
        info.setClassName("HelloController");
        ParsedClassInfo.MethodInfo mi = new ParsedClassInfo.MethodInfo();
        mi.setName("hello");
        mi.setStartLine(3);
        mi.setEndLine(3);
        info.setMethods(Collections.singletonList(mi));
        Mockito.when(javaParserService.parseFile(Mockito.any(File.class))).thenReturn(info);

        AiSummaryServiceImpl.ClassMethodSnippet snippet =
                new AiSummaryServiceImpl.ClassMethodSnippet(info, "  public void hello() { }\n");
        StringBuilder sb = new StringBuilder();
        ReflectionTestUtils.invokeMethod(
                service, "appendClassMethodSnippet", sb, 1L, "HelloController", tmp, snippet);

        String out = sb.toString();
        Assertions.assertTrue(out.contains("// === Class: com.demo.HelloController ==="), out);
        Assertions.assertTrue(out.contains("package com.demo;"), out);
        Assertions.assertTrue(out.contains("public void hello()"), out);
    }

    @Test
    @DisplayName("升 FQ 失败时保留短名，不编造包名")
    void resolveFqKeepsShortNameWhenUnknown() {
        Mockito.when(methodCallMapper.selectList(Mockito.any())).thenReturn(Collections.emptyList());
        String resolved = service.resolveFqClassName(1L, "OnlyShort", null, null);
        Assertions.assertEquals("OnlyShort", resolved);
        Assertions.assertFalse(resolved.contains("peig"));
    }
}
