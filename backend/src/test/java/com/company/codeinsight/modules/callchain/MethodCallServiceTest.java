package com.company.codeinsight.modules.callchain;

import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.service.MethodCallService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 方法调用链路服务集成测试
 * 验证 persistAstForTask / listByTaskId / listByClass / deleteByTaskId 等核心方法的正确性。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class MethodCallServiceTest {

    @Autowired
    private MethodCallService methodCallService;

    @Test
    public void testPersistAstForTaskWritesMethodCalls() throws Exception {
        Long taskId = 901L;
        File root = Files.createTempDirectory("mc-test-901").toFile();
        root.deleteOnExit();

        File controller = new File(root, "src/main/java/com/demo/UserController.java");
        controller.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(controller)) {
            w.write("""
package com.demo;

import com.demo.service.UserService;

public class UserController {
    private final UserService userService;
    public String detail(Long id) { return userService.findById(id); }
    public String list() { return userService.listAll(); }
}
""");
        }

        int written = methodCallService.persistAstForTask(taskId, root);
        Assertions.assertTrue(written >= 2, "should record at least 2 method calls, but got " + written);

        List<MethodCall> all = methodCallService.listByTaskId(taskId);
        Assertions.assertEquals(written, all.size());
        Assertions.assertTrue(all.stream().anyMatch(m -> "findById".equals(m.getTargetMethod())));
        Assertions.assertTrue(all.stream().anyMatch(m -> "listAll".equals(m.getTargetMethod())));
        Assertions.assertTrue(all.stream().allMatch(m -> "UserController".equals(m.getClassName())));
        Assertions.assertTrue(all.stream().allMatch(m -> taskId.equals(m.getTaskId())));

        List<MethodCall> byClass = methodCallService.listByClass(taskId, "UserController");
        Assertions.assertEquals(written, byClass.size());
    }

    @Test
    public void testPersistAstForTaskIsIdempotent() throws Exception {
        Long taskId = 902L;
        File root = Files.createTempDirectory("mc-test-902").toFile();
        root.deleteOnExit();

        File f = new File(root, "Foo.java");
        try (FileWriter w = new FileWriter(f)) {
            w.write("""
package x;
public class Foo { private Bar b; void m(){ b.go(); } }
""");
        }

        int first = methodCallService.persistAstForTask(taskId, root);
        int second = methodCallService.persistAstForTask(taskId, root);
        Assertions.assertEquals(first, second);
        Assertions.assertEquals(first, methodCallService.listByTaskId(taskId).size());
    }

    @Test
    public void testPersistAstForTaskContinuesOnFileError() throws Exception {
        Long taskId = 903L;
        File root = Files.createTempDirectory("mc-test-903").toFile();
        root.deleteOnExit();

        // 故意写一个非法但后缀是 .java 的文件
        File bad = new File(root, "Bad.java");
        Files.writeString(bad.toPath(), "@@not java@@");

        // 一个合法文件
        File good = new File(root, "Good.java");
        try (FileWriter w = new FileWriter(good)) {
            w.write("""
package x;
public class Good { private Bar b; void m(){ b.go(); } }
""");
        }

        // 任务整体不应抛异常
        int written = methodCallService.persistAstForTask(taskId, root);
        Assertions.assertTrue(written >= 1, "good file should still produce at least 1 call record");
        Assertions.assertTrue(methodCallService.listByTaskId(taskId).stream()
                .anyMatch(m -> "Good".equals(m.getClassName())));
    }

    @Test
    public void testForEachEdgeLitePagesWithoutFullEntities() throws Exception {
        Long taskId = 905L;
        File root = Files.createTempDirectory("mc-test-905").toFile();
        root.deleteOnExit();
        File f = new File(root, "Lite.java");
        try (FileWriter w = new FileWriter(f)) {
            w.write("""
package x;
public class Lite { private Y y; void m(){ y.go(); } }
""");
        }

        int written = methodCallService.persistAstForTask(taskId, root);
        Assertions.assertTrue(written >= 1);

        List<com.company.codeinsight.modules.callchain.model.MethodCallEdgeLite> collected = new ArrayList<>();
        methodCallService.forEachEdgeLite(taskId, collected::addAll);
        Assertions.assertFalse(collected.isEmpty());
        Assertions.assertTrue(collected.stream().anyMatch(e -> "Lite".equals(e.getClassName())));
        Assertions.assertTrue(collected.stream().allMatch(e -> e.getId() != null));
    }

    @Test
    public void testDeleteByTaskId() throws Exception {
        Long taskId = 904L;
        File root = Files.createTempDirectory("mc-test-904").toFile();
        root.deleteOnExit();
        File f = new File(root, "X.java");
        try (FileWriter w = new FileWriter(f)) {
            w.write("""
package x;
public class X { private Y y; void m(){ y.go(); } }
""");
        }

        methodCallService.persistAstForTask(taskId, root);
        Assertions.assertFalse(methodCallService.listByTaskId(taskId).isEmpty());

        methodCallService.deleteByTaskId(taskId);
        Assertions.assertTrue(methodCallService.listByTaskId(taskId).isEmpty());
    }
}