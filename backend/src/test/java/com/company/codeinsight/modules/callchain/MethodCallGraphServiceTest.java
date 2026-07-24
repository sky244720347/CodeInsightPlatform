package com.company.codeinsight.modules.callchain;

import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.callchain.service.MethodCallGraphService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 方法调用链图 BFS 单测：完整 target 签名 / 旧数据兜底 / 多态候选。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class MethodCallGraphServiceTest {

    @Autowired
    private MethodCallGraphService methodCallGraphService;

    @Autowired
    private MethodCallMapper methodCallMapper;

    @Test
    public void testResolveReachableMethodsDirect() {
        Long taskId = 9101L;
        insertCall(taskId, "A#method1()", "B", "method2", "B#method2()", null, 1);

        Set<String> roots = new LinkedHashSet<>();
        roots.add("A#method1()");
        Set<String> visited = methodCallGraphService.resolveReachableMethods(taskId, roots);

        Assertions.assertTrue(visited.contains("A#method1()"));
        Assertions.assertTrue(visited.contains("B#method2()"));
        Assertions.assertFalse(visited.contains("method2"));
    }

    @Test
    public void testResolveReachableMethodsTransitive() {
        Long taskId = 9102L;
        insertCall(taskId, "A#method1()", "B", "method2", "B#method2()", null, 1);
        insertCall(taskId, "B#method2()", "C", "method3", "C#method3()", null, 1);

        Set<String> roots = new LinkedHashSet<>();
        roots.add("A#method1()");
        Set<String> visited = methodCallGraphService.resolveReachableMethods(taskId, roots);

        Assertions.assertTrue(visited.contains("A#method1()"));
        Assertions.assertTrue(visited.contains("B#method2()"));
        Assertions.assertTrue(visited.contains("C#method3()"));
    }

    @Test
    public void testResolveReachableMethodsWithCycles() {
        Long taskId = 9103L;
        insertCall(taskId, "A#method1()", "B", "method2", "B#method2()", null, 1);
        insertCall(taskId, "B#method2()", "A", "method1", "A#method1()", null, 1);

        Set<String> roots = new LinkedHashSet<>();
        roots.add("A#method1()");
        Set<String> visited = methodCallGraphService.resolveReachableMethods(taskId, roots);

        Assertions.assertTrue(visited.contains("A#method1()"));
        Assertions.assertTrue(visited.contains("B#method2()"));
        Assertions.assertEquals(2, visited.size());
    }

    @Test
    public void testLegacyBareTargetUsesDependencyName() {
        Long taskId = 9105L;
        // 旧数据：target_signature 仅为方法名
        insertCall(taskId, "ProductController#listProducts()", "ProductService",
                "listProducts", "listProducts", null, 1);
        insertCall(taskId, "ProductService#listProducts()", "Repo", "findAll", "Repo#findAll()", null, 2);

        Set<String> roots = new LinkedHashSet<>();
        roots.add("ProductController#listProducts()");
        Set<String> visited = methodCallGraphService.resolveReachableMethods(taskId, roots);

        Assertions.assertTrue(visited.contains("ProductService#listProducts()"));
        Assertions.assertTrue(visited.contains("Repo#findAll()"));
        Assertions.assertFalse(visited.contains("listProducts"));
    }

    @Test
    public void testDependencyCandidatesEnqueueImpl() {
        Long taskId = 9106L;
        insertCall(taskId, "OrderController#quote()", "OrderService",
                "quote", "OrderService#quote()", "OrderServiceImpl", 1);
        insertCall(taskId, "OrderServiceImpl#quote()", "Pricing", "calc", "Pricing#calc()", null, 2);

        Set<String> roots = new LinkedHashSet<>();
        roots.add("OrderController#quote()");
        Set<String> visited = methodCallGraphService.resolveReachableMethods(taskId, roots);

        Assertions.assertTrue(visited.contains("OrderService#quote()")
                || visited.stream().anyMatch(s -> s.startsWith("OrderService#quote")));
        Assertions.assertTrue(visited.contains("OrderServiceImpl#quote()")
                || visited.stream().anyMatch(s -> s.startsWith("OrderServiceImpl#quote")));
        Assertions.assertTrue(visited.contains("Pricing#calc()"));
    }

    @Test
    public void testFullParamExactOverload() {
        Long taskId = 9107L;
        insertCall(taskId, "ProductController#getProduct(Long)", "ProductService",
                "getProduct", "ProductService#getProduct(Long)", null, 1);
        insertCall(taskId, "ProductService#getProduct(Long)", "Dao", "load", "Dao#load(Long)", null, 2);
        insertCall(taskId, "ProductService#getProduct(String)", "Dao", "loadByName", "Dao#loadByName(String)", null, 3);

        Set<String> roots = new LinkedHashSet<>();
        roots.add("ProductController#getProduct(Long)");
        Set<String> visited = methodCallGraphService.resolveReachableMethods(taskId, roots);

        Assertions.assertTrue(visited.stream().anyMatch(s -> s.startsWith("ProductService#getProduct")));
        Assertions.assertTrue(visited.contains("Dao#load(Long)") || visited.stream().anyMatch(s -> s.startsWith("Dao#load(")));
        Assertions.assertFalse(visited.contains("Dao#loadByName(String)"));
    }

    @Test
    public void testResolveReachableMethodsEmptyRoots() {
        Long taskId = 9104L;
        Set<String> roots = new LinkedHashSet<>();
        Set<String> visited = methodCallGraphService.resolveReachableMethods(taskId, roots);
        Assertions.assertNotNull(visited);
        Assertions.assertTrue(visited.isEmpty());
    }

    @Test
    public void testResolveReachableMethodsNullTaskId() {
        Set<String> roots = new LinkedHashSet<>();
        roots.add("A#method1()");
        Set<String> visited = methodCallGraphService.resolveReachableMethods(null, roots);
        Assertions.assertTrue(visited.isEmpty());
    }

    private void insertCall(Long taskId, String callerSignature, String dependencyName,
                            String targetMethod, String targetSignature, String candidates, int line) {
        methodCallMapper.insert(baseCall(taskId, callerSignature, dependencyName, targetMethod,
                targetSignature, candidates, line));
    }

    private MethodCall baseCall(Long taskId, String callerSignature, String dependencyName,
                                String targetMethod, String targetSignature, String candidates, int line) {
        MethodCall mc = new MethodCall();
        mc.setTaskId(taskId);
        mc.setFilePath("src/main/java/com/demo/Test.java");
        String className = "Test";
        if (callerSignature != null && callerSignature.contains("#")) {
            className = callerSignature.substring(0, callerSignature.indexOf('#'));
            int dot = className.lastIndexOf('.');
            if (dot >= 0) {
                className = className.substring(dot + 1);
            }
        }
        mc.setClassName(className);
        if (callerSignature != null && callerSignature.contains("#")) {
            String methodPart = callerSignature.substring(callerSignature.indexOf('#') + 1);
            int paren = methodPart.indexOf('(');
            mc.setCallerMethod(paren >= 0 ? methodPart.substring(0, paren) : methodPart);
        } else {
            mc.setCallerMethod("unknown");
        }
        mc.setCallerSignature(callerSignature);
        mc.setDependencyName(dependencyName);
        mc.setTargetMethod(targetMethod);
        mc.setTargetSignature(targetSignature);
        mc.setDependencyCandidates(candidates);
        mc.setExpression((dependencyName == null ? "this" : "dep") + "."
                + (targetMethod == null ? "x" : targetMethod) + "()");
        mc.setLineNumber(line);
        mc.setCreatedDate(LocalDateTime.now());
        return mc;
    }
}
