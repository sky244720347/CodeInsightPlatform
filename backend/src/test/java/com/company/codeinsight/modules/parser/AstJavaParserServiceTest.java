package com.company.codeinsight.modules.parser;

import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodCallInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.SqlReference;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.company.codeinsight.modules.parser.service.impl.AstJavaParserService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * AstJavaParserService 单测。重点覆盖 AST 相对正则的优势场景，以及与 Regex 版契约对齐的核心部分。
 *
 * <p>覆盖矩阵：</p>
 * <ul>
 *   <li>类级 + 方法级注解 + RequestMapping（基础契约）</li>
 *   <li>字段注入依赖</li>
 *   <li>构造器注入依赖（AST 原生支持，regex 上一版需要补丁）</li>
 *   <li>方法调用链（caller / scope / target 解析）</li>
 *   <li>Lambda / Stream / MethodReference（regex 几乎抓不到）</li>
 *   <li>多行方法签名（regex 边界 bug）</li>
 *   <li>SQL 字面量扫描 + 字段抽取</li>
 *   <li>类型兜底（按注解 + 类名后缀）</li>
 *   <li>extends / implements 提取</li>
 *   <li>main 方法检测</li>
 * </ul>
 */
public class AstJavaParserServiceTest {

    private final JavaParserService parserService = new AstJavaParserService();

    @Test
    public void testParseRestControllerClassLevelAndMethodLevelMapping() throws IOException {
        File f = writeTemp("MockController", ".java", """
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

        ParsedClassInfo info = parserService.parseFile(f);
        Assertions.assertNotNull(info);
        Assertions.assertEquals("MockController", info.getClassName());
        Assertions.assertEquals("com.example.demo", info.getPackageName());
        Assertions.assertEquals("CONTROLLER", info.getType());
        Assertions.assertEquals("/api/v1/mock", info.getRequestMapping());
        Assertions.assertEquals(2, info.getMethods().size());

        MethodInfo m0 = info.getMethods().get(0);
        Assertions.assertEquals("sayHello", m0.getName());
        Assertions.assertEquals("String", m0.getReturnType());
        Assertions.assertEquals("GET", m0.getHttpMethod());
        Assertions.assertEquals("/hello", m0.getRequestMapping());

        Assertions.assertTrue(info.getTables().contains("ci_mock_table"));
    }

    @Test
    public void testParseFieldInjectionAndCallChain() throws IOException {
        File f = writeTemp("UserController", ".java", """
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
        String selectSql = "SELECT u.id, u.name, r.role_name FROM ci_user u JOIN ci_role r ON u.role_id = r.id WHERE u.id = ? AND r.enabled = ?";
        String insertSql = "INSERT INTO ci_user (id, name, role_id) VALUES (?, ?, ?)";
        String updateSql = "UPDATE ci_user SET name = ?, role_id = ? WHERE id = ?";
    }
}
""");

        ParsedClassInfo info = parserService.parseFile(f);

        Assertions.assertEquals("CONTROLLER", info.getType());
        Assertions.assertTrue(info.getDependencies().contains("userService:UserService"),
                "field injection should record dependency, got: " + info.getDependencies());

        Assertions.assertEquals(1, info.getMethodCalls().size(),
                "should capture one userService method call");
        MethodCallInfo call = info.getMethodCalls().get(0);
        Assertions.assertEquals("detail", call.getCallerMethod());
        Assertions.assertEquals("UserService", call.getDependencyName());
        Assertions.assertEquals("findById", call.getTargetMethod());

        // SQL 校验
        Assertions.assertTrue(info.getTables().contains("ci_user"));
        Assertions.assertTrue(info.getTables().contains("ci_role"));

        SqlReference select = findSql(info, "SELECT");
        Assertions.assertNotNull(select);
        Assertions.assertTrue(select.getSelectedFields().contains("name"));
        Assertions.assertTrue(select.getConditionFields().contains("enabled"));
        Assertions.assertTrue(select.getJoinedTables().contains("ci_role"));

        SqlReference insert = findSql(info, "INSERT");
        Assertions.assertNotNull(insert);
        Assertions.assertTrue(insert.getInsertedFields().contains("role_id"));

        SqlReference update = findSql(info, "UPDATE");
        Assertions.assertNotNull(update);
        Assertions.assertTrue(update.getUpdatedFields().contains("name"));
        Assertions.assertTrue(update.getConditionFields().contains("id"));
    }

    /**
     * 核心优势场景 #1：构造器注入。
     * regex 版要单独打补丁，AST 由 JavaParser 原生解析 ConstructorDeclaration.getParameters() 直接拿到。
     */
    @Test
    public void testParseConstructorInjection() throws IOException {
        File f = writeTemp("ConstructorController", ".java", """
package com.example.demo;

@RestController
@RequestMapping("/ctor")
public class ConstructorController {

    private final SharedService sharedService;

    public ConstructorController(OtherService otherService,
                                  final Validator validator,
                                  @Autowired AuditService auditService) {
        this.otherService = otherService;
        this.validator = validator;
        this.auditService = auditService;
    }

    public void handle() {
        otherService.doA();
        validator.check();
        auditService.audit();
        sharedService.doB();
    }
}
""");

        ParsedClassInfo info = parserService.parseFile(f);

        // 三个构造器参数 + 一个字段都应该被识别为依赖
        Assertions.assertTrue(info.getDependencies().contains("otherService:OtherService"),
                "should record ctor param as dependency, got: " + info.getDependencies());
        Assertions.assertTrue(info.getDependencies().contains("validator:Validator"),
                "should record final ctor param as dependency, got: " + info.getDependencies());
        Assertions.assertTrue(info.getDependencies().contains("auditService:AuditService"),
                "should record @Autowired ctor param as dependency, got: " + info.getDependencies());
        Assertions.assertTrue(info.getDependencies().contains("sharedService:SharedService"),
                "should record field as dependency, got: " + info.getDependencies());

        // 四次方法调用都应该入 methodCalls
        Assertions.assertEquals(4, info.getMethodCalls().size(),
                "expected 4 method calls captured (3 ctor-injected + 1 field), got: " + info.getMethodCalls());
    }

    /**
     * 核心优势场景 #2：Lambda / Stream / MethodReference。
     * regex 版基本识别不到 lambda 体内的方法调用，AST 用 findAll(MethodCallExpr.class) 直接拿到。
     */
    @Test
    public void testParseLambdaAndStream() throws IOException {
        File f = writeTemp("LambdaController", ".java", """
package com.example.demo;

import java.util.*;
import java.util.stream.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class LambdaController {
    private final UserService userService;

    public List<String> activeNames() {
        return userService.listAll().stream()
            .filter(u -> u.isActive())
            .map(u -> u.getName())
            .collect(Collectors.toList());
    }
}
""");

        ParsedClassInfo info = parserService.parseFile(f);
        Assertions.assertTrue(info.getDependencies().contains("userService:UserService"));

        // 至少应该抓到 userService.listAll() 这一调用
        long serviceCalls = info.getMethodCalls().stream()
                .filter(c -> "UserService".equals(c.getDependencyName()))
                .count();
        Assertions.assertTrue(serviceCalls >= 1,
                "expected at least one UserService call captured via AST, got: " + info.getMethodCalls());
    }

    /**
     * 核心优势场景 #3：多行方法签名。
     * regex 版的 METHOD_PATTERN 要求类型与名称间有空格，遇到换行就丢整段方法。
     * AST 由 JavaParser 直接给出 Range，自动吃多行。
     */
    @Test
    public void testParseMultilineMethodSignature() throws IOException {
        File f = writeTemp("MultilineController", ".java", """
package com.example.demo;

import org.springframework.web.bind.annotation.*;

@RestController
public class MultilineController {

    public <T extends Object> Response<T> getById(
            Long id,
            boolean includeDeleted,
            String traceId) {
        return userService.find(id, includeDeleted, traceId);
    }
}
""");

        ParsedClassInfo info = parserService.parseFile(f);
        Assertions.assertEquals(1, info.getMethods().size(),
                "should capture the multi-line method");
        MethodInfo m = info.getMethods().get(0);
        Assertions.assertEquals("getById", m.getName());
        Assertions.assertNotNull(m.getStartLine());
        Assertions.assertNotNull(m.getEndLine());
        Assertions.assertTrue(m.getEndLine() > m.getStartLine(),
                "endLine should be after startLine, got " + m.getStartLine() + ".." + m.getEndLine());
        Assertions.assertTrue(m.getEndLine() - m.getStartLine() >= 5,
                "expected method body to span at least 5 lines, got " + m.getStartLine() + ".." + m.getEndLine());
    }

    @Test
    public void testParseExtendsImplementsAndMain() throws IOException {
        File f = writeTemp("Application", ".java", """
package com.example.demo;

public class Application extends BaseApp implements Bootstrap, AutoCloseable {

    public static void main(String[] args) {
        System.out.println("boot");
    }
}
""");

        ParsedClassInfo info = parserService.parseFile(f);
        Assertions.assertEquals("Application", info.getClassName());
        Assertions.assertEquals("BaseApp", info.getExtendsClass());
        Assertions.assertTrue(info.getImplementsList().contains("Bootstrap"));
        Assertions.assertTrue(info.getImplementsList().contains("AutoCloseable"));
        Assertions.assertTrue(info.isHasMainMethod());
        Assertions.assertEquals("APPLICATION", info.getType());
    }

    @Test
    public void testParseClassTypeByAnnotationAndSuffix() throws IOException {
        Assertions.assertEquals("ENUM", parseClassType("UserStatus", "public enum UserStatus { ACTIVE }"));
        Assertions.assertEquals("ANNOTATION", parseClassType("Traceable", "public @interface Traceable {}"));
        Assertions.assertEquals("CONFIG", parseClassType("CacheConfig", "@org.springframework.context.annotation.Configuration\npublic class CacheConfig {}"));
        Assertions.assertEquals("DTO", parseClassType("UserDTO", "public class UserDTO {}"));
        Assertions.assertEquals("VO", parseClassType("UserVO", "public class UserVO {}"));
        Assertions.assertEquals("SERVICE", parseClassType("OrderServiceImpl", "public class OrderServiceImpl {}"));
    }

    /**
     * 异常路径：不可解析的"Java 文件"应抛 ParseProblemException，由 Fallback 承接。
     */
    @Test
    public void testParseBrokenFileThrowsParseProblem() throws IOException {
        File f = writeTemp("Broken", ".java", """
package com.example.demo;

public class Broken {
    public void foo(@@@ invalid syntax here
        return;
}
""");
        Assertions.assertThrows(com.github.javaparser.ParseProblemException.class,
                () -> parserService.parseFile(f));
    }

    // ---------- helpers ----------

    private File writeTemp(String prefix, String suffix, String content) throws IOException {
        File f = File.createTempFile(prefix, suffix);
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write(content);
        }
        return f;
    }

    private SqlReference findSql(ParsedClassInfo info, String op) {
        List<SqlReference> all = info.getSqlReferences();
        for (SqlReference s : all) {
            if (op.equals(s.getOperation())) return s;
        }
        return null;
    }

    private String parseClassType(String name, String src) throws IOException {
        File f = writeTemp(name, ".java", "package com.example.demo;\n\n" + src);
        return parserService.parseFile(f).getType();
    }
}
