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
 * 方法调用链图 BFS 单测
 * 测试场景：直接调用 / 直连 / 传递 / 循环 / 空入参
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
        // A.method1 → B.method2（直接调用）
        insertCall(taskId, "com.demo.A#method1()", "method2", 1);

        Set<String> roots = new LinkedHashSet<>();
        roots.add("com.demo.A#method1()");
        Set<String> visited = methodCallGraphService.resolveReachableMethods(taskId, roots);

        Assertions.assertTrue(visited.contains("com.demo.A#method1()"));
        Assertions.assertTrue(visited.contains("method2"));
        Assertions.assertEquals(2, visited.size());
    }

    @Test
    public void testResolveReachableMethodsTransitive() {
        Long taskId = 9102L;
        // A.method1 → B.method2 → C.method3（链式调用）
        insertCall(taskId, "com.demo.A#method1()", "method2", 1);
        insertCall(taskId, "com.demo.B#method2()", "method3", 1);

        Set<String> roots = new LinkedHashSet<>();
        roots.add("com.demo.A#method1()");
        Set<String> visited = methodCallGraphService.resolveReachableMethods(taskId, roots);

        Assertions.assertTrue(visited.contains("com.demo.A#method1()"));
        Assertions.assertTrue(visited.contains("method2"));
        Assertions.assertTrue(visited.contains("method3"));
        Assertions.assertEquals(3, visited.size());
    }

    @Test
    public void testResolveReachableMethodsWithCycles() {
        Long taskId = 9103L;
        // A → B → A（循环调用）：BFS 必须去重，不能无限循环
        insertCall(taskId, "com.demo.A#method1()", "method2", 1);
        insertCall(taskId, "com.demo.B#method2()", "method1", 1);

        Set<String> roots = new LinkedHashSet<>();
        roots.add("com.demo.A#method1()");
        Set<String> visited = methodCallGraphService.resolveReachableMethods(taskId, roots);

        // 包含 A + B，不重复
        Assertions.assertEquals(2, visited.size());
        Assertions.assertTrue(visited.contains("com.demo.A#method1()"));
        Assertions.assertTrue(visited.contains("method2"));
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
        roots.add("com.demo.A#method1()");
        Set<String> visited = methodCallGraphService.resolveReachableMethods(null, roots);
        Assertions.assertTrue(visited.isEmpty());
    }

    private void insertCall(Long taskId, String callerSignature, String targetMethod, int lines) {
        MethodCall mc = new MethodCall();
        mc.setTaskId(taskId);
        mc.setFilePath("src/main/java/com/demo/Test.java");
        mc.setClassName("com.demo.Test");
        mc.setCallerMethod(callerSignature.substring(callerSignature.indexOf('#') + 1, callerSignature.indexOf('(')));
        mc.setCallerSignature(callerSignature);
        mc.setDependencyName("dep:Demo");
        mc.setTargetMethod(targetMethod);
        mc.setTargetSignature(targetMethod);
        mc.setExpression("dep." + targetMethod + "()");
        mc.setLineNumber(lines);
        mc.setCreatedDate(LocalDateTime.now());
        methodCallMapper.insert(mc);
    }
}