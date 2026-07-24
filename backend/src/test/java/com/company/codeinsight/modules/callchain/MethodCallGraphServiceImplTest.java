package com.company.codeinsight.modules.callchain;

import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.service.impl.MethodCallGraphServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 正向 BFS 纯单测（覆盖 findOutgoing，不依赖 PG / MyBatis）。
 */
public class MethodCallGraphServiceImplTest {

    private final List<MethodCall> store = new ArrayList<>();
    private MethodCallGraphServiceImpl service;

    @BeforeEach
    void setUp() {
        store.clear();
        service = new MethodCallGraphServiceImpl() {
            @Override
            protected List<MethodCall> findOutgoing(Long taskId, String callerSignature) {
                if (taskId == null || callerSignature == null) {
                    return List.of();
                }
                List<MethodCall> exact = store.stream()
                        .filter(mc -> taskId.equals(mc.getTaskId()))
                        .filter(mc -> callerSignature.equals(mc.getCallerSignature()))
                        .filter(mc -> mc.getTargetSignature() != null)
                        .collect(Collectors.toList());
                if (!exact.isEmpty()) {
                    return exact;
                }
                String prefix = callerSignature.contains("(")
                        ? callerSignature.substring(0, callerSignature.indexOf('('))
                        : callerSignature;
                if (!prefix.contains("#")) {
                    return List.of();
                }
                return store.stream()
                        .filter(mc -> taskId.equals(mc.getTaskId()))
                        .filter(mc -> mc.getCallerSignature() != null)
                        .filter(mc -> mc.getCallerSignature().startsWith(prefix + "(")
                                || mc.getCallerSignature().equals(prefix + "()"))
                        .filter(mc -> mc.getTargetSignature() != null)
                        .collect(Collectors.toList());
            }

            @Override
            protected List<String> findMatchingCallerSignatures(Long taskId, String classMethodPrefix, String paramSuffix) {
                if (taskId == null || classMethodPrefix == null) {
                    return List.of();
                }
                LinkedHashSet<String> matched = new LinkedHashSet<>();
                if (paramSuffix != null && paramSuffix.startsWith("(")) {
                    String exact = classMethodPrefix + paramSuffix;
                    store.stream()
                            .filter(mc -> taskId.equals(mc.getTaskId()))
                            .map(MethodCall::getCallerSignature)
                            .filter(exact::equals)
                            .forEach(matched::add);
                    if (!matched.isEmpty()) {
                        return new ArrayList<>(matched);
                    }
                }
                store.stream()
                        .filter(mc -> taskId.equals(mc.getTaskId()))
                        .map(MethodCall::getCallerSignature)
                        .filter(cs -> cs != null && (cs.startsWith(classMethodPrefix + "(")
                                || cs.equals(classMethodPrefix + "()")))
                        .forEach(matched::add);
                return new ArrayList<>(matched);
            }
        };
    }

    @Test
    void directCallReachesService() {
        store.add(edge(9L, "ProductController#listProducts()", "ProductService",
                "listProducts", "ProductService#listProducts()", null));
        Set<String> visited = service.resolveReachableMethods(9L, Set.of("ProductController#listProducts()"));
        Assertions.assertTrue(visited.contains("ProductController#listProducts()"));
        Assertions.assertTrue(visited.contains("ProductService#listProducts()"));
        Assertions.assertFalse(visited.contains("listProducts"));
    }

    @Test
    void legacyBareTargetUsesDependencyName() {
        store.add(edge(9L, "ProductController#listProducts()", "ProductService",
                "listProducts", "listProducts", null));
        store.add(edge(9L, "ProductService#listProducts()", "Repo",
                "findAll", "Repo#findAll()", null));
        Set<String> visited = service.resolveReachableMethods(9L, Set.of("ProductController#listProducts()"));
        Assertions.assertTrue(visited.contains("ProductService#listProducts()")
                || visited.contains("ProductService#listProducts"));
        Assertions.assertTrue(visited.contains("Repo#findAll()"));
    }

    @Test
    void candidatesReachImpl() {
        store.add(edge(9L, "OrderController#quote()", "OrderService",
                "quote", "OrderService#quote()", "OrderServiceImpl"));
        store.add(edge(9L, "OrderServiceImpl#quote()", "Pricing",
                "calc", "Pricing#calc()", null));
        Set<String> visited = service.resolveReachableMethods(9L, Set.of("OrderController#quote()"));
        Assertions.assertTrue(visited.stream().anyMatch(s -> s.startsWith("OrderServiceImpl#quote")));
        Assertions.assertTrue(visited.contains("Pricing#calc()"));
    }

    @Test
    void transitiveChain() {
        store.add(edge(9L, "A#method1()", "B", "method2", "B#method2()", null));
        store.add(edge(9L, "B#method2()", "C", "method3", "C#method3()", null));
        Set<String> visited = service.resolveReachableMethods(9L, Set.of("A#method1()"));
        Assertions.assertTrue(visited.contains("B#method2()"));
        Assertions.assertTrue(visited.contains("C#method3()"));
    }

    @Test
    void emptyRoots() {
        Assertions.assertTrue(service.resolveReachableMethods(9L, new LinkedHashSet<>()).isEmpty());
        Assertions.assertTrue(service.resolveReachableMethods(null, Set.of("A#a()")).isEmpty());
    }

    private static MethodCall edge(Long taskId, String caller, String dep, String targetMethod,
                                   String targetSig, String candidates) {
        MethodCall mc = new MethodCall();
        mc.setTaskId(taskId);
        mc.setCallerSignature(caller);
        mc.setDependencyName(dep);
        mc.setTargetMethod(targetMethod);
        mc.setTargetSignature(targetSig);
        mc.setDependencyCandidates(candidates);
        mc.setClassName(caller.contains("#") ? caller.substring(0, caller.indexOf('#')) : "X");
        return mc;
    }
}
