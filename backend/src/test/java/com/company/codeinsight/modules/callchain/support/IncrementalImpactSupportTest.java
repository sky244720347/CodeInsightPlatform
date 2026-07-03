package com.company.codeinsight.modules.callchain.support;

import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
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
 * Phase 3 多态扩展 helper 单测。
 * 验证 {@link IncrementalImpactSupport#expandChangedFqSetWithPolymorphicAncestors}
 * 能把"具象实现"展开为"declared 父类型 ∪ 具象实现"——这样 moduleTouchedByChange
 * 才能命中那些只引接口不引具象的 function。
 *
 * <p>本测试是 SpringBootTest（依赖真实 PG），跟仓库既有的 callchain 测试套路一致。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class IncrementalImpactSupportTest {

    @Autowired
    private MethodCallMapper methodCallMapper;

    @Test
    public void expandAddsDeclaredInterfaceFromCandidates() {
        Long taskId = 9301L;
        insertCall(taskId, "com.demo.NotifyController",
                "com.demo.NotifyController#send()",
                "com.example.Notifier", "send",
                "com.example.EmailNotifierImpl,com.example.SmsNotifierImpl",
                "src/main/java/com/demo/NotifyController.java");

        Set<String> changed = new LinkedHashSet<>();
        changed.add("com.example.EmailNotifierImpl");

        Set<String> expanded = IncrementalImpactSupport.expandChangedFqSetWithPolymorphicAncestors(
                taskId, changed, methodCallMapper);

        Assertions.assertTrue(expanded.contains("com.example.EmailNotifierImpl"),
                "原始 impl FQ 应保留: " + expanded);
        Assertions.assertTrue(expanded.contains("com.example.Notifier"),
                "应展开出 declared 接口: " + expanded);
    }

    @Test
    public void expandIgnoresUnrelatedNames() {
        Long taskId = 9302L;
        insertCall(taskId, "com.demo.OrderController",
                "com.demo.OrderController#place()",
                "com.example.OrderService", "place",
                "com.example.OrderServiceImpl",
                "src/main/java/com/demo/OrderController.java");

        Set<String> changed = new LinkedHashSet<>();
        changed.add("com.example.ShippingServiceImpl");

        Set<String> expanded = IncrementalImpactSupport.expandChangedFqSetWithPolymorphicAncestors(
                taskId, changed, methodCallMapper);

        Assertions.assertEquals(1, expanded.size(),
                "无关 FQ 应只保留自身，不应展开: " + expanded);
        Assertions.assertTrue(expanded.contains("com.example.ShippingServiceImpl"));
    }

    @Test
    public void expandRejectsLongNameFalsePositive() {
        // 验证 LIKE '%EmailNotifierImpl%' 即使误命中 EmailNotifierImplHelper，
        // 也会被 candidatesContainExact 拒掉，不会把那条行的 declared 接口错误拉进来。
        Long taskId = 9303L;
        insertCall(taskId, "com.demo.Aux",
                "com.demo.Aux#go()",
                "com.example.AuxService", "aux",
                "com.example.EmailNotifierImplHelper",  // 长尾同名，但不是 EmailNotifierImpl
                "src/main/java/com/demo/Aux.java");
        insertCall(taskId, "com.demo.NotifyController",
                "com.demo.NotifyController#send()",
                "com.example.Notifier", "send",
                "com.example.EmailNotifierImpl,com.example.SmsNotifierImpl",
                "src/main/java/com/demo/NotifyController.java");

        Set<String> changed = new LinkedHashSet<>();
        changed.add("com.example.EmailNotifierImpl");

        Set<String> expanded = IncrementalImpactSupport.expandChangedFqSetWithPolymorphicAncestors(
                taskId, changed, methodCallMapper);

        Assertions.assertFalse(expanded.contains("com.example.AuxService"),
                "同名前缀误匹配必须被 candidatesContainExact 拦下: " + expanded);
    }

    private void insertCall(Long taskId, String callerClass, String callerSignature,
                            String depName, String targetMethod,
                            String depCandidates, String filePath) {
        MethodCall mc = new MethodCall();
        mc.setTaskId(taskId);
        mc.setFilePath(filePath);
        mc.setClassName(callerClass);
        String sig = callerSignature.substring(callerSignature.indexOf('#') + 1);
        mc.setCallerMethod(sig.substring(0, sig.indexOf('(')));
        mc.setCallerSignature(callerSignature);
        mc.setDependencyName(depName);
        mc.setTargetMethod(targetMethod);
        mc.setTargetSignature(targetMethod);
        mc.setExpression("dep." + targetMethod + "()");
        mc.setLineNumber(1);
        mc.setDependencyCandidates(depCandidates);
        mc.setCreatedAt(LocalDateTime.now());
        methodCallMapper.insert(mc);
    }
}
