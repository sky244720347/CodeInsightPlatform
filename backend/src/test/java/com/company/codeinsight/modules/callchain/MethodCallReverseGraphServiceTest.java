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

    private void insertCall(Long taskId, String callerClass, String callerSignature,
                            String dependencyName, String targetMethod, String filePath) {
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
        mc.setCreatedAt(LocalDateTime.now());
        methodCallMapper.insert(mc);
    }
}
