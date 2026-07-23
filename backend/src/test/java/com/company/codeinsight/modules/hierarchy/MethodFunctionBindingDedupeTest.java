package com.company.codeinsight.modules.hierarchy;

import com.company.codeinsight.modules.hierarchy.entity.MethodFunctionBinding;
import com.company.codeinsight.modules.hierarchy.service.impl.ModuleHierarchyServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * binding 落库前按 (class_name, method_signature) 去重（Phase 0 止血）。
 */
@DisplayName("MethodFunctionBinding 去重")
public class MethodFunctionBindingDedupeTest {

    @Test
    @DisplayName("同键多行：last-wins，只保留后者")
    @SuppressWarnings("unchecked")
    void dedupeKeepsLastFunction() {
        ModuleHierarchyServiceImpl service = new ModuleHierarchyServiceImpl();

        MethodFunctionBinding first = binding("com.demo.Foo", "bar()", "f00001");
        MethodFunctionBinding second = binding("com.demo.Foo", "bar()", "f00002");
        MethodFunctionBinding other = binding("com.demo.Foo", "baz()", "f00001");

        List<MethodFunctionBinding> rows = new ArrayList<>();
        rows.add(first);
        rows.add(other);
        rows.add(second);

        List<MethodFunctionBinding> out = (List<MethodFunctionBinding>) ReflectionTestUtils.invokeMethod(
                service, "dedupeBindingsByClassMethod", rows, "com.demo.Entry");

        Assertions.assertEquals(2, out.size());
        MethodFunctionBinding keptBar = out.stream()
                .filter(r -> "bar()".equals(r.getMethodSignature()))
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals("f00002", keptBar.getFunctionNodeId());
        Assertions.assertTrue(out.stream().anyMatch(r -> "baz()".equals(r.getMethodSignature())));
    }

    @Test
    @DisplayName("无重复：原样保留")
    @SuppressWarnings("unchecked")
    void dedupeNoOpWhenUnique() {
        ModuleHierarchyServiceImpl service = new ModuleHierarchyServiceImpl();
        List<MethodFunctionBinding> rows = List.of(
                binding("com.demo.A", "a()", "f1"),
                binding("com.demo.B", "b()", "f2")
        );
        List<MethodFunctionBinding> out = (List<MethodFunctionBinding>) ReflectionTestUtils.invokeMethod(
                service, "dedupeBindingsByClassMethod", rows, "entry");
        Assertions.assertEquals(2, out.size());
    }

    private static MethodFunctionBinding binding(String className, String sig, String functionId) {
        MethodFunctionBinding b = new MethodFunctionBinding();
        b.setClassName(className);
        b.setMethodSignature(sig);
        b.setFunctionNodeId(functionId);
        b.setModuleNodeId("m1");
        b.setSubModuleNodeId("s1");
        return b;
    }
}
