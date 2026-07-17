package com.company.codeinsight.modules.callchain;

import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.callchain.service.MethodCallReverseGraphService;
import com.company.codeinsight.modules.callchain.model.EntryMethodHit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class MethodCallReverseGraphServiceTest {

    @Autowired
    private MethodCallReverseGraphService reverseGraphService;

    @Autowired
    private MethodCallMapper methodCallMapper;

    @Test
    public void testResolveCallingEntriesFromServiceToController() {
        Long taskId = 9201L;
        insertCall(taskId, "com.demo.UserController", "com.demo.UserController#listUsers()",
                "UserService", "save", "src/main/java/com/demo/UserController.java");
        Set<String> entries = new LinkedHashSet<>();
        entries.add("com.demo.UserController");

        List<EntryMethodHit> hits = reverseGraphService.resolveCallingEntries(
                taskId, "com.demo.UserService", entries, 15);

        Assertions.assertEquals(1, hits.size());
        Assertions.assertEquals("com.demo.UserController", hits.get(0).entryClassName());
    }

    @Test
    public void testDependencyNameMismatchDoesNotHit() {
        Long taskId = 9202L;
        insertCall(taskId, "com.demo.UserController", "com.demo.UserController#listUsers()",
                "OtherService", "save", "src/main/java/com/demo/UserController.java");
        Set<String> entries = new LinkedHashSet<>();
        entries.add("com.demo.UserController");

        List<EntryMethodHit> hits = reverseGraphService.resolveCallingEntries(
                taskId, "com.demo.UserService", entries, 15);

        Assertions.assertTrue(hits.isEmpty());
    }

    /**
     * Phase 3 wiring：多态反查。
     * 当 changedFqcn = "com.demo.EmailServiceImpl" 时，调用方 dep = "EmailService"
     * 且 dependency_candidates 含 "com.demo.EmailServiceImpl" 的行也应该被命中。
     * 这是 #9 反向 BFS 跨多态边命中入口的核心场景。
     */
    @Test
    public void testPolymorphicReverseBfsHitsCaller() {
        Long taskId = 9203L;
        // 模拟一条注入 EmailService 接口、调用 emailService.send(...) 的 controller call
        insertCall(taskId, "com.demo.NotifyController",
                "com.demo.NotifyController#sendEmail()",
                "EmailService", "send",
                "com.demo.EmailServiceImpl,com.demo.SmsServiceImpl",
                "src/main/java/com/demo/NotifyController.java");
        Set<String> entries = new LinkedHashSet<>();
        entries.add("com.demo.NotifyController");

        // 改的是具体实现 EmailServiceImpl，反向 BFS 应该通过 candidates 命中控制器
        List<EntryMethodHit> hits = reverseGraphService.resolveCallingEntries(
                taskId, "com.demo.EmailServiceImpl", entries, 15);

        Assertions.assertEquals(1, hits.size(),
                "Phase 3 polymorphic seed/expansion should hit NotifyController through candidate match");
        Assertions.assertEquals("com.demo.NotifyController", hits.get(0).entryClassName());
    }

    /**
     * Phase 3 wiring 反向验证：当 changedFqcn 不在 candidates 里时，
     * 反向 BFS 不应误命中（保留 regex 时代的精确性）。
     */
    @Test
    public void testPolymorphicNonMatchStaysEmpty() {
        Long taskId = 9204L;
        insertCall(taskId, "com.demo.OrderController",
                "com.demo.OrderController#place()",
                "OrderService", "place",
                "com.demo.OrderServiceImpl",
                "src/main/java/com/demo/OrderController.java");
        Set<String> entries = new LinkedHashSet<>();
        entries.add("com.demo.OrderController");

        // changedFqcn 与 candidates 完全无关
        List<EntryMethodHit> hits = reverseGraphService.resolveCallingEntries(
                taskId, "com.demo.ShippingServiceImpl", entries, 15);

        Assertions.assertTrue(hits.isEmpty(),
                "unrelated impl should not hit unrelated controllers");
    }

    private void insertCall(Long taskId, String callerClass, String callerSignature,
                            String dependencyName, String targetMethod, String filePath) {
        insertCall(taskId, callerClass, callerSignature, dependencyName, targetMethod, null, filePath);
    }

    private void insertCall(Long taskId, String callerClass, String callerSignature,
                            String dependencyName, String targetMethod, String dependencyCandidates,
                            String filePath) {
        MethodCall mc = new MethodCall();
        mc.setTaskId(taskId);
        mc.setFilePath(filePath);
        mc.setClassName(callerClass);
        mc.setCallerMethod(callerSignature.substring(callerSignature.indexOf('#') + 1,
                callerSignature.indexOf('(')));
        mc.setCallerSignature(callerSignature);
        mc.setDependencyName(dependencyName);
        mc.setTargetMethod(targetMethod);
        mc.setTargetSignature(targetMethod);
        mc.setExpression("dep." + targetMethod + "()");
        mc.setLineNumber(1);
        if (dependencyCandidates != null) {
            mc.setDependencyCandidates(dependencyCandidates);
        }
        mc.setCreatedDate(LocalDateTime.now());
        methodCallMapper.insert(mc);
    }
}
