package com.company.codeinsight.modules.scanner;

import com.company.codeinsight.modules.scanner.service.BaselineInheritanceService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * module-map.yaml 解析（含 generatedAt）与时间解析单测。
 */
class ModuleMapGeneratedAtParseTest {

    @Test
    void parseGeneratedAt_acceptsLocalDateTimeAndInstant() throws Exception {
        Method m = BaselineInheritanceService.class.getDeclaredMethod("parseGeneratedAt", String.class);
        m.setAccessible(true);
        Assertions.assertEquals(
                LocalDateTime.of(2026, 8, 3, 17, 0, 0),
                m.invoke(null, "2026-08-03T17:00:00"));
        Assertions.assertNull(m.invoke(null, ""));
        Assertions.assertNull(m.invoke(null, (Object) null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseModuleMapYaml_readsGeneratedAt() throws Exception {
        Path yaml = Files.createTempFile("module-map-", ".yaml");
        Files.writeString(yaml, """
                modules:
                  - name: "订单 / 下单 / 创建订单"
                    path: "docs/code-insight/modules/创建订单.md"
                    generatedAt: "2026-07-01T10:15:30"
                  - name: "旧模块"
                    path: "docs/code-insight/modules/old.md"
                """);

        BaselineInheritanceService svc = new BaselineInheritanceService();
        Method parse = BaselineInheritanceService.class.getDeclaredMethod(
                "parseModuleMapYaml", Path.class);
        parse.setAccessible(true);
        List<?> entries = (List<?>) parse.invoke(svc, yaml);

        Assertions.assertEquals(2, entries.size());
        Object first = entries.get(0);
        Object second = entries.get(1);
        Method moduleName = first.getClass().getDeclaredMethod("moduleName");
        Method generatedAt = first.getClass().getDeclaredMethod("generatedAt");
        moduleName.setAccessible(true);
        generatedAt.setAccessible(true);
        Assertions.assertEquals("订单 / 下单 / 创建订单", moduleName.invoke(first));
        Assertions.assertEquals(LocalDateTime.of(2026, 7, 1, 10, 15, 30), generatedAt.invoke(first));
        Assertions.assertNull(generatedAt.invoke(second));

        Files.deleteIfExists(yaml);
    }
}
