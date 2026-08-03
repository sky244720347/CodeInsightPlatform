package com.company.codeinsight.modules.entrypoint;

import com.company.codeinsight.modules.callchain.service.MethodCallService;
import com.company.codeinsight.modules.entrypoint.model.EntryPoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import com.company.codeinsight.modules.entrypoint.model.ExcludeTarget;
import com.company.codeinsight.modules.entrypoint.model.TypeIncludeRules;
import com.company.codeinsight.modules.entrypoint.service.EntryPointDiscoveryService;
import com.company.codeinsight.modules.entrypoint.service.impl.EntryPointDiscoveryServiceImpl;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.company.codeinsight.modules.scanner.model.ScanScope;
import com.company.codeinsight.modules.scanner.service.ScanScopeResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 入口识别服务测试（mock 掉 JavaParserService / MethodCallService）
 */
public class EntryPointDiscoveryServiceTest {

    private JavaParserService javaParserService;
    private MethodCallService methodCallService;
    private EntryPointDiscoveryService service;

    @BeforeEach
    void setUp() {
        javaParserService = mock(JavaParserService.class);
        methodCallService = mock(MethodCallService.class);
        // forEachEdgeLite 默认 no-op = 无调用边
        ScanScopeResolver scanScopeResolver = mock(ScanScopeResolver.class);
        when(scanScopeResolver.resolveBestEffort(any(), any())).thenAnswer(inv ->
                ScanScope.wholeRepository(inv.getArgument(1)));
        service = new EntryPointDiscoveryServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "javaParserService", javaParserService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "methodCallService", methodCallService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scanScopeResolver", scanScopeResolver);
    }

    private ParsedClassInfo buildClass(String className, String type, boolean hasMain, String... annotations) {
        ParsedClassInfo info = new ParsedClassInfo();
        info.setClassName(className);
        info.setPackageName("com.demo");
        info.setType(type);
        info.setHasMainMethod(hasMain);
        info.setAnnotations(Arrays.asList(annotations));
        return info;
    }

    private EntryPointConfig controllerClasspathOnly(String pattern) {
        EntryPointConfig cfg = EntryPointConfig.defaults();
        TypeIncludeRules rules = cfg.getIncludesByType().get(EntryPointConfig.TYPE_CONTROLLER);
        rules.setIncludeAnnotations(new ArrayList<>());
        rules.setIncludeClasspaths(new ArrayList<>(List.of(pattern)));
        rules.setIncludeExtends(new ArrayList<>());
        cfg.getIncludesByType().put(EntryPointConfig.TYPE_SCHEDULED_JOB, new TypeIncludeRules());
        cfg.getIncludesByType().put(EntryPointConfig.TYPE_MQ_LISTENER, new TypeIncludeRules());
        cfg.getIncludesByType().put(EntryPointConfig.TYPE_OTHER, new TypeIncludeRules());
        return cfg;
    }

    @Test
    public void testRestControllerDetected() {
        ParsedClassInfo ctl = buildClass("UserController", "CONTROLLER", false, "RestController");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(ctl));
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), EntryPointConfig.defaults());
        Assertions.assertEquals(1, entries.size());
        Assertions.assertEquals("CONTROLLER", entries.get(0).getEntryType());
        Assertions.assertEquals("com.demo.UserController", entries.get(0).getClassName());
    }

    @Test
    public void testScheduledJobDetected() {
        ParsedClassInfo job = buildClass("DailySyncJob", "JOB", false, "Component", "Scheduled");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(job));
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), EntryPointConfig.defaults());
        Assertions.assertEquals(1, entries.size());
        Assertions.assertEquals("SCHEDULED_JOB", entries.get(0).getEntryType());
    }

    @Test
    public void testMqListenerDetected() {
        ParsedClassInfo mq = buildClass("OrderConsumer", "MESSAGE_LISTENER", false, "Component", "RabbitListener");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(mq));
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), EntryPointConfig.defaults());
        Assertions.assertEquals(1, entries.size());
        Assertions.assertEquals("MQ_LISTENER", entries.get(0).getEntryType());
    }

    @Test
    public void testMainMethodNotDetectedWithoutOtherRules() {
        ParsedClassInfo app = buildClass("CodeInsightApplication", "APPLICATION", true, "SpringBootApplication");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(app));
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), EntryPointConfig.defaults());
        Assertions.assertEquals(0, entries.size());
    }

    @Test
    public void testDuplicateDedup() {
        ParsedClassInfo a = buildClass("DupController", "CONTROLLER", false, "RestController");
        ParsedClassInfo b = buildClass("DupController", "CONTROLLER", false, "RestController");
        when(javaParserService.parseDirectory(any())).thenReturn(Arrays.asList(a, b));
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), EntryPointConfig.defaults());
        Assertions.assertEquals(1, entries.size());
    }

    @Test
    public void testCollectReachableSourceIncludesClassFile() throws Exception {
        File root = Files.createTempDirectory("entry-test").toFile();
        root.deleteOnExit();
        File source = new File(root, "src/main/java/com/demo/UserController.java");
        source.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(source)) {
            w.write("package com.demo;\npublic class UserController {}\n");
        }

        ParsedClassInfo info = buildClass("UserController", "CONTROLLER", false, "RestController");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(info));
        String src = service.collectReachableSource(1L, "com.demo.UserController", root);
        Assertions.assertTrue(src.contains("public class UserController"));
    }

    @Test
    public void testIncludeAnnotationsHit() {
        ParsedClassInfo ctl = buildClass("UserController", "UNKNOWN", false, "RestController");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(ctl));
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), EntryPointConfig.defaults());
        Assertions.assertEquals(1, entries.size());
        Assertions.assertEquals("com.demo.UserController", entries.get(0).getClassName());
    }

    @Test
    public void testIncludeClasspathsAnt() {
        ParsedClassInfo ctl = buildClass("UserController", "UNKNOWN", false);
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(ctl));
        EntryPointConfig cfg = controllerClasspathOnly("com.demo.*");
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), cfg);
        Assertions.assertEquals(1, entries.size());
    }

    @Test
    public void testIncludeExtends() {
        ParsedClassInfo child = buildClass("MyEntry", "UNKNOWN", false);
        child.setExtendsClass("com.demo.BaseEntry");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(child));
        EntryPointConfig cfg = EntryPointConfig.defaults();
        TypeIncludeRules other = new TypeIncludeRules();
        other.setIncludeExtends(new ArrayList<>(List.of("com.demo.BaseEntry")));
        cfg.getIncludesByType().put(EntryPointConfig.TYPE_OTHER, other);
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), cfg);
        Assertions.assertEquals(1, entries.size());
        Assertions.assertEquals(EntryPointConfig.TYPE_OTHER, entries.get(0).getEntryType());
    }

    @Test
    public void testExcludeClasspath() {
        ParsedClassInfo ctl = buildClass("TestController", "UNKNOWN", false, "RestController");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(ctl));
        EntryPointConfig cfg = EntryPointConfig.defaults();
        cfg.setExcludeClasspaths(new ArrayList<>(List.of("*.TestController")));
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), cfg);
        Assertions.assertEquals(0, entries.size());
    }

    @Test
    public void testExcludePackage() {
        ParsedClassInfo ctl = buildClass("ConfigController", "UNKNOWN", false, "RestController");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(ctl));
        EntryPointConfig cfg = EntryPointConfig.defaults();
        cfg.setExcludePackages(new ArrayList<>(List.of("com.demo.config")));
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), cfg);
        Assertions.assertEquals(0, entries.size());
    }

    @Test
    public void testExcludeAnnotation() {
        ParsedClassInfo ctl = buildClass("UserController", "UNKNOWN", false, "RestController", "Internal");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(ctl));
        EntryPointConfig cfg = EntryPointConfig.defaults();
        cfg.setExcludeAnnotations(new ArrayList<>(List.of("Internal")));
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), cfg);
        Assertions.assertEquals(0, entries.size());
    }

    @Test
    public void testExcludeTargetClassLevel() {
        ParsedClassInfo ctl = buildClass("UserController", "CONTROLLER", false, "RestController");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(ctl));
        EntryPointConfig cfg = EntryPointConfig.defaults();
        cfg.setExcludeTargets(new ArrayList<>(List.of(new ExcludeTarget("com.demo.UserController", null))));
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), cfg);
        Assertions.assertEquals(0, entries.size());
    }

    @Test
    public void testControllerPriorityOverJob() {
        ParsedClassInfo both = buildClass("Hybrid", "JOB", false, "RestController", "Scheduled");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(both));
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), EntryPointConfig.defaults());
        Assertions.assertEquals(1, entries.size());
        Assertions.assertEquals("CONTROLLER", entries.get(0).getEntryType());
    }

    @Test
    public void testConfigNullUsesDefaults() {
        ParsedClassInfo ctl = buildClass("UserController", "CONTROLLER", false, "RestController");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(ctl));
        List<EntryPoint> entries = service.discoverEntries(1L, new File("."), null);
        Assertions.assertEquals(1, entries.size());
        Assertions.assertEquals("CONTROLLER", entries.get(0).getEntryType());
    }

    @Test
    public void testReadEntrySourceUsesMultiModulePath() throws Exception {
        File root = Files.createTempDirectory("entry-multi").toFile();
        root.deleteOnExit();
        File source = new File(root, "accounting-service/src/main/java/net/demo/AccountsController.java");
        source.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(source)) {
            w.write("package net.demo;\n@RestController\npublic class AccountsController {}\n");
        }

        ParsedClassInfo info = buildClass("AccountsController", "CONTROLLER", false, "RestController");
        info.setPackageName("net.demo");
        info.setSourceRelativePath("accounting-service/src/main/java/net/demo/AccountsController.java");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(info));
        when(javaParserService.parseFile(any())).thenReturn(info);
        List<EntryPoint> entries = service.discoverEntries(1L, root, EntryPointConfig.defaults());
        Assertions.assertEquals(1, entries.size());
        Assertions.assertEquals("accounting-service/src/main/java/net/demo/AccountsController.java", entries.get(0).getFilePath());

        String content = service.readEntrySource(root, entries.get(0), null);
        Assertions.assertTrue(content.contains("AccountsController"));
    }

    @Test
    public void testCollectReachableSourceRespectsExclude() throws Exception {
        File root = Files.createTempDirectory("entry-test-excl").toFile();
        root.deleteOnExit();
        File src = new File(root, "src/main/java/com/demo/UserController.java");
        src.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(src)) {
            w.write("package com.demo;\npublic class UserController {}\n");
        }

        ParsedClassInfo info = buildClass("UserController", "CONTROLLER", false, "RestController");
        when(javaParserService.parseDirectory(any())).thenReturn(Collections.singletonList(info));
        EntryPointConfig cfg = EntryPointConfig.defaults();
        cfg.setExcludeClasspaths(new ArrayList<>(List.of("com.demo.UserController")));
        String result = service.collectReachableSource(1L, "com.demo.UserController", root, cfg);
        Assertions.assertEquals("", result);
    }
}
