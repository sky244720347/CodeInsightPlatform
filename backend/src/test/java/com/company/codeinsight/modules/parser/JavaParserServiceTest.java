package com.company.codeinsight.modules.parser;

import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.company.codeinsight.modules.parser.service.impl.RegexJavaParserService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class JavaParserServiceTest {

    private final JavaParserService parserService = new RegexJavaParserService();

    @Test
    public void testParseJavaFile() throws IOException {
        File tempFile = File.createTempFile("MockController", ".java");
        tempFile.deleteOnExit();

        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("""
package com.example.demo;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/mock")
public class MockController {

    @GetMapping("/hello")
    public String sayHello(@RequestParam String name) {
        return "Hello " + name;
    }

    @PostMapping("/create")
    public void createItem() {
        String sql = "SELECT * FROM ci_mock_table";
    }
}
""");
        }

        ParsedClassInfo info = parserService.parseFile(tempFile);
        Assertions.assertNotNull(info);
        Assertions.assertEquals("MockController", info.getClassName());
        Assertions.assertEquals("com.example.demo", info.getPackageName());
        Assertions.assertEquals("CONTROLLER", info.getType());
        Assertions.assertEquals("/api/v1/mock", info.getRequestMapping());
        Assertions.assertEquals(2, info.getMethods().size());

        ParsedClassInfo.MethodInfo getMethod = info.getMethods().get(0);
        Assertions.assertEquals("sayHello", getMethod.getName());
        Assertions.assertEquals("String", getMethod.getReturnType());
        Assertions.assertEquals("GET", getMethod.getHttpMethod());
        Assertions.assertEquals("/hello", getMethod.getRequestMapping());
        Assertions.assertTrue(info.getTables().contains("ci_mock_table"));
    }

    @Test
    public void testParseDeepClassTypesCallChainAndSqlFields() throws IOException {
        File controllerFile = File.createTempFile("UserController", ".java");
        controllerFile.deleteOnExit();

        try (FileWriter writer = new FileWriter(controllerFile)) {
            writer.write("""
package com.example.demo.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService userService;

    @GetMapping("/{id}")
    public UserVO detail(@PathVariable Long id) {
        return userService.findById(id);
    }

    public void sqlSamples() {
        String querySql = "SELECT u.id, u.name, r.role_name FROM ci_user u JOIN ci_role r ON u.role_id = r.id WHERE u.id = ? AND r.enabled = ?";
        String insertSql = "INSERT INTO ci_user (id, name, role_id) VALUES (?, ?, ?)";
        String updateSql = "UPDATE ci_user SET name = ?, role_id = ? WHERE id = ?";
    }
}
""");
        }

        ParsedClassInfo info = parserService.parseFile(controllerFile);

        Assertions.assertEquals("CONTROLLER", info.getType());
        Assertions.assertTrue(info.getDependencies().contains("userService:UserService"));
        Assertions.assertEquals(1, info.getMethodCalls().size());
        Assertions.assertEquals("detail", info.getMethodCalls().get(0).getCallerMethod());
        Assertions.assertEquals("UserService", info.getMethodCalls().get(0).getDependencyName());
        Assertions.assertEquals("findById", info.getMethodCalls().get(0).getTargetMethod());

        Assertions.assertTrue(info.getTables().contains("ci_user"));
        Assertions.assertTrue(info.getTables().contains("ci_role"));

        ParsedClassInfo.SqlReference select = info.getSqlReferences().stream()
                .filter(sql -> "SELECT".equals(sql.getOperation()))
                .findFirst()
                .orElseThrow();
        Assertions.assertTrue(select.getSelectedFields().contains("id"));
        Assertions.assertTrue(select.getSelectedFields().contains("name"));
        Assertions.assertTrue(select.getSelectedFields().contains("role_name"));
        Assertions.assertTrue(select.getConditionFields().contains("enabled"));
        Assertions.assertTrue(select.getJoinedTables().contains("ci_role"));

        ParsedClassInfo.SqlReference insert = info.getSqlReferences().stream()
                .filter(sql -> "INSERT".equals(sql.getOperation()))
                .findFirst()
                .orElseThrow();
        Assertions.assertTrue(insert.getInsertedFields().contains("role_id"));

        ParsedClassInfo.SqlReference update = info.getSqlReferences().stream()
                .filter(sql -> "UPDATE".equals(sql.getOperation()))
                .findFirst()
                .orElseThrow();
        Assertions.assertTrue(update.getUpdatedFields().contains("name"));
        Assertions.assertTrue(update.getConditionFields().contains("id"));
    }

    @Test
    public void testParseAdditionalClassTypes() throws IOException {
        Assertions.assertEquals("DTO", parseClassType("UserDTO", "public class UserDTO {}"));
        Assertions.assertEquals("VO", parseClassType("UserVO", "public class UserVO {}"));
        Assertions.assertEquals("ENUM", parseClassType("UserStatus", "public enum UserStatus { ACTIVE }"));
        Assertions.assertEquals("CONFIG", parseClassType("CacheConfig", "@Configuration\npublic class CacheConfig {}"));
        Assertions.assertEquals("ANNOTATION", parseClassType("Traceable", "public @interface Traceable {}"));
        Assertions.assertEquals("JOB", parseClassType("DailySyncJob", "public class DailySyncJob {}"));
    }

    private String parseClassType(String name, String source) throws IOException {
        File tempFile = File.createTempFile(name, ".java");
        tempFile.deleteOnExit();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("""
package com.example.demo;

""" + source);
        }
        return parserService.parseFile(tempFile).getType();
    }
}
