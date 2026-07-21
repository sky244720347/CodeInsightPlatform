package com.company.codeinsight.modules.hierarchy;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.util.Base62Generator;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.service.impl.ModuleHierarchyServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * 空 module_name：跳过（无 keywords fallback）；camelCase 可读；落库前校验。
 */
@DisplayName("模块层级空名称合并")
public class ModuleHierarchyEmptyNameMergeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ModuleHierarchyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ModuleHierarchyServiceImpl();
        ReflectionTestUtils.setField(service, "base62Generator", new Base62Generator());
    }

    @Test
    @DisplayName("readAiName：优先 snake，其次 camel")
    void readAiNamePrefersSnakeThenCamel() throws Exception {
        JsonNode snake = objectMapper.readTree("{\"module_name\":\"预落单\",\"moduleName\":\"忽略\"}");
        Assertions.assertEquals("预落单",
                ModuleHierarchyServiceImpl.readAiName(snake, "module_name", "moduleName", "name"));

        JsonNode camel = objectMapper.readTree("{\"moduleName\":\"订单管理\"}");
        Assertions.assertEquals("订单管理",
                ModuleHierarchyServiceImpl.readAiName(camel, "module_name", "moduleName", "name"));

        JsonNode empty = objectMapper.readTree("{\"keywords\":[\"预落单\"]}");
        Assertions.assertEquals("",
                ModuleHierarchyServiceImpl.readAiName(empty, "module_name", "moduleName", "name"));
    }

    @Test
    @DisplayName("camelCase moduleName 可合并")
    void mergeAcceptsCamelCaseModuleName() throws Exception {
        ModuleHierarchy hierarchy = new ModuleHierarchy();
        JsonNode increment = objectMapper.readTree("""
                {
                  "modules": [{
                    "id": "mAb12",
                    "moduleName": "预落单管理",
                    "keywords": ["预落单", "订单"],
                    "sub_modules": [{
                      "id": "sCd34",
                      "subModuleName": "预落单查询",
                      "functions": [{
                        "id": "fEf56",
                        "functionName": "查询预落单",
                        "class_paths": ["com.demo.OrderController"],
                        "method_signatures": ["list()"]
                      }]
                    }]
                  }]
                }
                """);
        Set<String> newly = new HashSet<>();
        ReflectionTestUtils.invokeMethod(service, "mergeIncrementIntoHierarchy",
                hierarchy, increment, newly, null, null);

        Assertions.assertEquals(1, hierarchy.getModules().size());
        ModuleDto m = hierarchy.getModules().values().iterator().next();
        Assertions.assertEquals("预落单管理", m.getModuleName());
        Assertions.assertFalse(m.getSubModules().isEmpty());
    }

    @Test
    @DisplayName("无 module_name（即使有 keywords）→ 整模块跳过")
    void mergeSkipsModuleWhenNameEmptyEvenWithKeywords() throws Exception {
        ModuleHierarchy hierarchy = new ModuleHierarchy();
        JsonNode increment = objectMapper.readTree("""
                {
                  "modules": [{
                    "id": "mAb12",
                    "keywords": ["预落单", "订单", "案件"],
                    "sub_modules": [{
                      "id": "sCd34",
                      "sub_module_name": "查询",
                      "functions": [{
                        "id": "fEf56",
                        "function_name": "list",
                        "class_paths": ["com.demo.X"],
                        "method_signatures": ["list()"]
                      }]
                    }]
                  }]
                }
                """);
        Set<String> newly = new HashSet<>();
        ReflectionTestUtils.invokeMethod(service, "mergeIncrementIntoHierarchy",
                hierarchy, increment, newly, null, null);

        Assertions.assertTrue(hierarchy.getModules().isEmpty(), "空名模块不得入库到内存树");
    }

    @Test
    @DisplayName("persist 前空名 → BusinessException")
    void assertHierarchyNamesPresentThrows() {
        ModuleHierarchy hierarchy = new ModuleHierarchy();
        ModuleDto m = new ModuleDto();
        m.setId("mAb12");
        m.setModuleName(null);
        hierarchy.getModules().put(m.getId(), m);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> service.assertHierarchyNamesPresent(hierarchy));
        Assertions.assertTrue(ex.getMessage().contains("模块名称不能为空"));
    }
}
