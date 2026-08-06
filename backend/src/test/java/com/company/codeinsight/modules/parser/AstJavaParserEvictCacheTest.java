package com.company.codeinsight.modules.parser;

import com.company.codeinsight.modules.parser.service.impl.AstJavaParserService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析缓存按 taskId 驱逐的单元测试（不依赖 Spring / DB）。
 */
public class AstJavaParserEvictCacheTest {

    @Test
    void matchesTaskWorkspacePath_acceptsTaskRoots() {
        Assertions.assertTrue(AstJavaParserService.matchesTaskWorkspacePath(
                "C:/data/workspaces/task_42/src/main/java", 42L));
        Assertions.assertTrue(AstJavaParserService.matchesTaskWorkspacePath(
                "/var/workspaces/task_42", 42L));
        Assertions.assertTrue(AstJavaParserService.matchesTaskWorkspacePath(
                "task_42/a/B.java", 42L));
        Assertions.assertFalse(AstJavaParserService.matchesTaskWorkspacePath(
                "C:/data/workspaces/task_420/src", 42L));
        Assertions.assertFalse(AstJavaParserService.matchesTaskWorkspacePath(
                "C:/data/workspaces/task_41/src", 42L));
        Assertions.assertFalse(AstJavaParserService.matchesTaskWorkspacePath(
                "C:/data/foo_task_42/src", 42L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictTaskCaches_removesParseAndSolverEntries() throws Exception {
        AstJavaParserService svc = new AstJavaParserService();
        Field parseField = AstJavaParserService.class.getDeclaredField("parseCache");
        parseField.setAccessible(true);
        ConcurrentHashMap<String, Object> parseCache =
                (ConcurrentHashMap<String, Object>) parseField.get(svc);
        parseCache.put("task_7/a/B.java", new Object());
        parseCache.put("task_8/a/B.java", new Object());

        Field solverField = AstJavaParserService.class.getDeclaredField("SYMBOL_SOLVER_CACHE");
        solverField.setAccessible(true);
        ConcurrentHashMap<String, Object> solverCache =
                (ConcurrentHashMap<String, Object>) solverField.get(null);
        String root7 = new File("workspaces/task_7").getAbsolutePath();
        String root8 = new File("workspaces/task_8").getAbsolutePath();
        solverCache.put(root7, new Object());
        solverCache.put(root8, new Object());

        Field subtypeField = AstJavaParserService.class.getDeclaredField("SUBTYPE_INDEX_CACHE");
        subtypeField.setAccessible(true);
        ConcurrentHashMap<String, Map<String, Object>> subtypeCache =
                (ConcurrentHashMap<String, Map<String, Object>>) subtypeField.get(null);
        subtypeCache.put(root7, Map.of());
        subtypeCache.put(root8, Map.of());

        svc.evictTaskCaches(7L);

        Assertions.assertFalse(parseCache.containsKey("task_7/a/B.java"));
        Assertions.assertTrue(parseCache.containsKey("task_8/a/B.java"));
        Assertions.assertFalse(solverCache.containsKey(root7));
        Assertions.assertTrue(solverCache.containsKey(root8));
        Assertions.assertFalse(subtypeCache.containsKey(root7));
        Assertions.assertTrue(subtypeCache.containsKey(root8));

        // cleanup static pollution for other tests
        svc.evictTaskCaches(8L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictTaskCaches_removesAbsolutePathParseKeys() throws Exception {
        AstJavaParserService svc = new AstJavaParserService();
        Field parseField = AstJavaParserService.class.getDeclaredField("parseCache");
        parseField.setAccessible(true);
        ConcurrentHashMap<String, Object> parseCache =
                (ConcurrentHashMap<String, Object>) parseField.get(svc);

        String abs7 = new File("workspaces/task_7/mod/Foo.java").getAbsolutePath().replace('\\', '/');
        String abs8 = new File("workspaces/task_8/mod/Foo.java").getAbsolutePath().replace('\\', '/');
        parseCache.put(abs7, new Object());
        parseCache.put(abs8, new Object());

        Field solverField = AstJavaParserService.class.getDeclaredField("SYMBOL_SOLVER_CACHE");
        solverField.setAccessible(true);
        ConcurrentHashMap<String, Object> solverCache =
                (ConcurrentHashMap<String, Object>) solverField.get(null);
        String moduleRoot7 = new File("workspaces/task_7/mod-a").getAbsolutePath();
        String moduleRoot7b = new File("workspaces/task_7/mod-b").getAbsolutePath();
        solverCache.put(moduleRoot7, new Object());
        solverCache.put(moduleRoot7b, new Object());

        Field subtypeField = AstJavaParserService.class.getDeclaredField("SUBTYPE_INDEX_CACHE");
        subtypeField.setAccessible(true);
        ConcurrentHashMap<String, Map<String, Object>> subtypeCache =
                (ConcurrentHashMap<String, Map<String, Object>>) subtypeField.get(null);
        subtypeCache.put(moduleRoot7, Map.of());
        subtypeCache.put(moduleRoot7b, Map.of());

        svc.evictTaskCaches(7L);

        Assertions.assertFalse(parseCache.containsKey(abs7));
        Assertions.assertTrue(parseCache.containsKey(abs8));
        Assertions.assertFalse(solverCache.containsKey(moduleRoot7));
        Assertions.assertFalse(solverCache.containsKey(moduleRoot7b));
        Assertions.assertFalse(subtypeCache.containsKey(moduleRoot7));
        Assertions.assertFalse(subtypeCache.containsKey(moduleRoot7b));

        parseCache.remove(abs8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearAllCaches_emptiesParseAndStaticMaps() throws Exception {
        AstJavaParserService svc = new AstJavaParserService();
        Field parseField = AstJavaParserService.class.getDeclaredField("parseCache");
        parseField.setAccessible(true);
        ConcurrentHashMap<String, Object> parseCache =
                (ConcurrentHashMap<String, Object>) parseField.get(svc);
        parseCache.put("task_9/a/B.java", new Object());

        Field solverField = AstJavaParserService.class.getDeclaredField("SYMBOL_SOLVER_CACHE");
        solverField.setAccessible(true);
        ConcurrentHashMap<String, Object> solverCache =
                (ConcurrentHashMap<String, Object>) solverField.get(null);
        String root = new File("workspaces/task_9").getAbsolutePath();
        solverCache.put(root, new Object());

        Field subtypeField = AstJavaParserService.class.getDeclaredField("SUBTYPE_INDEX_CACHE");
        subtypeField.setAccessible(true);
        ConcurrentHashMap<String, Map<String, Object>> subtypeCache =
                (ConcurrentHashMap<String, Map<String, Object>>) subtypeField.get(null);
        subtypeCache.put(root, Map.of());

        Assertions.assertTrue(svc.cacheStatsSummary().contains("parse=1"));
        svc.clearAllCaches();
        Assertions.assertTrue(parseCache.isEmpty());
        Assertions.assertTrue(solverCache.isEmpty());
        Assertions.assertTrue(subtypeCache.isEmpty());
        Assertions.assertEquals("parse=0 symbolSolver=0 subtypeIndex=0", svc.cacheStatsSummary());
    }
}
