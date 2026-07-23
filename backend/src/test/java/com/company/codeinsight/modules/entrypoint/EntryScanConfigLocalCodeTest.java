package com.company.codeinsight.modules.entrypoint;

import com.company.codeinsight.modules.callchain.service.MethodCallService;
import com.company.codeinsight.modules.entrypoint.model.DiscoveredEntrypoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import com.company.codeinsight.modules.entrypoint.model.TypeIncludeRules;
import com.company.codeinsight.modules.entrypoint.service.EntryPointDiscoveryService;
import com.company.codeinsight.modules.entrypoint.service.impl.EntryPointDiscoveryServiceImpl;
import com.company.codeinsight.modules.parser.service.impl.FallbackJavaParserService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本地代码集成测试：真实写盘 → Fallback 解析 → 入口扫描，验证扫描配置是否生效。
 * <p>不启 Spring / 不连 PG，可单独跑：
 * {@code mvn -Dtest=EntryScanConfigLocalCodeTest test}</p>
 */
@DisplayName("入口扫描配置 · 本地代码解析")
public class EntryScanConfigLocalCodeTest {

    private static final Long TASK_ID = 9001L;

    @TempDir
    Path tempRoot;

    private EntryPointDiscoveryService discovery;
    private File projectDir;

    @BeforeEach
    void setUp() throws IOException {
        projectDir = tempRoot.toFile();
        // 项目标记，便于 AST 源根探测
        Files.writeString(tempRoot.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>t</artifactId></project>",
                StandardCharsets.UTF_8);

        writeJava("com/demo/web/ProductController.java", """
                package com.demo.web;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class ProductController {
                    @GetMapping("/products")
                    public String list() { return "ok"; }
                }
                """);

        // 方法级 @Scheduled：默认注解规则当前依赖类级 annotations，此用例用于对照
        writeJava("com/demo/job/DailySyncJob.java", """
                package com.demo.job;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class DailySyncJob {
                    @Scheduled(cron = "0 0 * * * ?")
                    public void sync() { }
                }
                """);

        // 方法级 MQ 注解
        writeJava("com/demo/mq/OrderConsumer.java", """
                package com.demo.mq;

                import org.springframework.amqp.rabbit.annotation.RabbitListener;
                import org.springframework.stereotype.Component;

                @Component
                public class OrderConsumer {
                    @RabbitListener(queues = "order.q")
                    public void onMessage(String body) { }
                }
                """);

        // 用户场景：无入口注解，仅靠类路径命中（包名对齐真实业务样例）
        writeJava("com/paic/phcrs/core/service/phmq/CrsCoreWork.java", """
                package com.paic.phcrs.core.service.phmq;

                import org.springframework.stereotype.Component;

                @Component
                public class CrsCoreWork {
                    public void handle(String payload) {
                        System.out.println(payload);
                    }
                }
                """);

        // CommandLineRunner：源码写简单名，默认配置是 FQCN
        writeJava("com/demo/job/BootInitRunner.java", """
                package com.demo.job;

                import org.springframework.boot.CommandLineRunner;
                import org.springframework.stereotype.Component;

                @Component
                public class BootInitRunner implements CommandLineRunner {
                    @Override
                    public void run(String... args) { }
                }
                """);

        MethodCallService methodCallService = mock(MethodCallService.class);
        when(methodCallService.listByTaskId(any())).thenReturn(List.of());

        EntryPointDiscoveryServiceImpl impl = new EntryPointDiscoveryServiceImpl();
        ReflectionTestUtils.setField(impl, "javaParserService", new FallbackJavaParserService());
        ReflectionTestUtils.setField(impl, "methodCallService", methodCallService);
        discovery = impl;
    }

    @Test
    @DisplayName("类路径 com.paic...phmq.** 应命中 CrsCoreWork 为 MQ")
    void classpathPattern_matchesPhmqWorkerAsMq() {
        EntryPointConfig cfg = blankIncludes();
        TypeIncludeRules mq = new TypeIncludeRules();
        mq.setIncludeClasspaths(new ArrayList<>(List.of("com.paic.phcrs.core.service.phmq.**")));
        cfg.getIncludesByType().put(EntryPointConfig.TYPE_MQ_LISTENER, mq);

        Map<String, EntryPoint> byClass = indexByClass(discovery.discoverEntries(TASK_ID, projectDir, cfg));

        Assertions.assertTrue(byClass.containsKey("com.paic.phcrs.core.service.phmq.CrsCoreWork"),
                "类路径规则应扫到 CrsCoreWork");
        Assertions.assertEquals(EntryPointConfig.TYPE_MQ_LISTENER,
                byClass.get("com.paic.phcrs.core.service.phmq.CrsCoreWork").getEntryType());
        Assertions.assertFalse(byClass.containsKey("com.demo.web.ProductController"),
                "未配 Controller 规则时不应扫到 ProductController");
    }

    @Test
    @DisplayName("试跑路径 discoverEntriesWithMethods：类路径命中后应保留方法")
    void classpathPattern_survivesDiscoverWithMethods() {
        EntryPointConfig cfg = blankIncludes();
        TypeIncludeRules mq = new TypeIncludeRules();
        mq.setIncludeClasspaths(new ArrayList<>(List.of("com.paic.phcrs.core.service.phmq.**")));
        cfg.getIncludesByType().put(EntryPointConfig.TYPE_MQ_LISTENER, mq);

        List<DiscoveredEntrypoint> found = discovery.discoverEntriesWithMethods(TASK_ID, projectDir, cfg);
        DiscoveredEntrypoint hit = found.stream()
                .filter(e -> "com.paic.phcrs.core.service.phmq.CrsCoreWork".equals(e.getBase().getClassName()))
                .findFirst()
                .orElse(null);

        Assertions.assertNotNull(hit, "试跑结果应包含 CrsCoreWork（不应被空 methods 过滤）");
        Assertions.assertFalse(hit.getMethods().isEmpty(), "应抽出业务方法 handle");
        Assertions.assertEquals(EntryPointConfig.TYPE_MQ_LISTENER, hit.getBase().getEntryType());
    }

    @Test
    @DisplayName("默认注解：类级 @RestController 应识别为 CONTROLLER")
    void defaultAnnotations_detectClassLevelRestController() {
        List<EntryPoint> entries = discovery.discoverEntries(TASK_ID, projectDir, EntryPointConfig.defaults());
        Map<String, EntryPoint> byClass = indexByClass(entries);

        Assertions.assertTrue(byClass.containsKey("com.demo.web.ProductController"));
        Assertions.assertEquals("CONTROLLER", byClass.get("com.demo.web.ProductController").getEntryType());
    }

    @Test
    @DisplayName("已知缺口：方法级 @Scheduled / @RabbitListener 默认注解扫不到")
    void defaultAnnotations_methodLevelJobAndMq_notDetectedYet() {
        List<EntryPoint> entries = discovery.discoverEntries(TASK_ID, projectDir, EntryPointConfig.defaults());
        Map<String, EntryPoint> byClass = indexByClass(entries);

        // AST 只收集类/字段注解；方法级 Scheduled / RabbitListener 进不了匹配集
        Assertions.assertFalse(byClass.containsKey("com.demo.job.DailySyncJob"),
                "当前实现下方法级 @Scheduled 不应被默认识别（回归保护，修好后应改断言）");
        Assertions.assertFalse(byClass.containsKey("com.demo.mq.OrderConsumer"),
                "当前实现下方法级 @RabbitListener 不应被默认识别（回归保护，修好后应改断言）");
    }

    @Test
    @DisplayName("已知缺口：includeExtends 默认 FQCN 对不上源码简单名")
    void includeExtends_defaultFqcn_missesSimpleNameImplements() {
        List<EntryPoint> entries = discovery.discoverEntries(TASK_ID, projectDir, EntryPointConfig.defaults());
        Map<String, EntryPoint> byClass = indexByClass(entries);

        Assertions.assertFalse(byClass.containsKey("com.demo.job.BootInitRunner"),
                "默认 org.springframework.boot.CommandLineRunner 对不上 implements CommandLineRunner");
    }

    @Test
    @DisplayName("includeExtends 配简单名时可命中 CommandLineRunner")
    void includeExtends_simpleName_matches() {
        EntryPointConfig cfg = blankIncludes();
        TypeIncludeRules job = new TypeIncludeRules();
        job.setIncludeExtends(new ArrayList<>(List.of("CommandLineRunner")));
        cfg.getIncludesByType().put(EntryPointConfig.TYPE_SCHEDULED_JOB, job);

        Map<String, EntryPoint> byClass = indexByClass(discovery.discoverEntries(TASK_ID, projectDir, cfg));
        Assertions.assertTrue(byClass.containsKey("com.demo.job.BootInitRunner"));
        Assertions.assertEquals(EntryPointConfig.TYPE_SCHEDULED_JOB,
                byClass.get("com.demo.job.BootInitRunner").getEntryType());
    }

    @Test
    @DisplayName("误把业务包类路径挂在 CONTROLLER：无 HTTP 方法时试跑会丢弃")
    void classpathUnderController_dropsClassWithoutHttpMapping() {
        EntryPointConfig cfg = blankIncludes();
        TypeIncludeRules controller = new TypeIncludeRules();
        controller.setIncludeClasspaths(new ArrayList<>(List.of("com.paic.phcrs.core.service.phmq.**")));
        cfg.getIncludesByType().put(EntryPointConfig.TYPE_CONTROLLER, controller);

        List<EntryPoint> base = discovery.discoverEntries(TASK_ID, projectDir, cfg);
        Assertions.assertTrue(base.stream().anyMatch(e ->
                "com.paic.phcrs.core.service.phmq.CrsCoreWork".equals(e.getClassName())),
                "类级识别应先命中");

        List<DiscoveredEntrypoint> withMethods =
                discovery.discoverEntriesWithMethods(TASK_ID, projectDir, cfg);
        Assertions.assertTrue(withMethods.stream().noneMatch(e ->
                        "com.paic.phcrs.core.service.phmq.CrsCoreWork".equals(e.getBase().getClassName())),
                "CONTROLLER 只保留 HTTP 映射方法，CrsCoreWork 会被试跑路径过滤掉");
    }

    // ----------------- helpers -----------------

    /**
     * 四类先放「永不命中」的非空规则，避免 {@link EntryPointConfig#normalize} 把空规则回填成默认注解，
     * 干扰「只测某一类配置」的断言。调用方再覆盖目标类型即可。
     */
    private static EntryPointConfig blankIncludes() {
        EntryPointConfig cfg = new EntryPointConfig();
        Map<String, TypeIncludeRules> map = new LinkedHashMap<>();
        map.put(EntryPointConfig.TYPE_CONTROLLER, neverMatchRules());
        map.put(EntryPointConfig.TYPE_SCHEDULED_JOB, neverMatchRules());
        map.put(EntryPointConfig.TYPE_MQ_LISTENER, neverMatchRules());
        map.put(EntryPointConfig.TYPE_OTHER, new TypeIncludeRules());
        cfg.setIncludesByType(map);
        cfg.setExcludeClasspaths(new ArrayList<>(List.of("**/*Test", "**/*Tests", "**/*TestCase")));
        return cfg;
    }

    private static TypeIncludeRules neverMatchRules() {
        TypeIncludeRules never = new TypeIncludeRules();
        never.setIncludeClasspaths(new ArrayList<>(List.of("__never.matches.**")));
        return never;
    }

    private void writeJava(String relativeUnderMain, String source) throws IOException {
        Path file = tempRoot.resolve("src/main/java").resolve(relativeUnderMain);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static Map<String, EntryPoint> indexByClass(List<EntryPoint> entries) {
        return entries.stream().collect(Collectors.toMap(EntryPoint::getClassName, Function.identity(), (a, b) -> a));
    }
}
