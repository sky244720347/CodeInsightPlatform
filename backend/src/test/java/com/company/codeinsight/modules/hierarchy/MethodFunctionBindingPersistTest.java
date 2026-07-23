package com.company.codeinsight.modules.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.entrypoint.model.EntrypointMethodView;
import com.company.codeinsight.modules.entrypoint.model.EntryPoint;
import com.company.codeinsight.modules.hierarchy.entity.MethodFunctionBinding;
import com.company.codeinsight.modules.hierarchy.mapper.MethodFunctionBindingMapper;
import com.company.codeinsight.modules.hierarchy.service.impl.ModuleHierarchyServiceImpl;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 方法→功能 反向绑定持久化的集成测试（Plan B）。
 *
 * <p>关键场景：</p>
 * <ul>
 *   <li><b>Tier 1（保留）</b>：AI 输出的 (class, sig) 与 ci_method_call.caller_signature 严格匹配 → 落 binding</li>
 *   <li><b>Tier 2 兜底（新）</b>：AI 输出 callee 类（Service/Repository）的 (class, sig)，该类在 ci_method_call
 *       只作为 dependencyName（callee）出现过，从未作为 caller —— 仍应落 binding</li>
 *   <li><b>AI 幻觉拦截</b>：AI 输出的 class 既不作为 caller 也不作为 callee 出现在调用图 → 拒绝落 binding</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
@Transactional
public class MethodFunctionBindingPersistTest {

    @Autowired
    private ModuleHierarchyServiceImpl hierarchyService;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired
    private MethodCallMapper methodCallMapper;

    @Autowired
    private MethodFunctionBindingMapper bindingMapper;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TASK_ID = 8701L;
    private static final long SYS_ID = 8701L;

    @BeforeEach
    public void setUp() {
        clearAll();
        seedTask();
    }

    @AfterEach
    public void tearDown() {
        clearAll();
    }

    private void clearAll() {
        bindingMapper.delete(new LambdaQueryWrapper<>());
        methodCallMapper.delete(new LambdaQueryWrapper<>());
        taskMapper.delete(new LambdaQueryWrapper<>());
    }

    private void seedTask() {
        DecompileTask task = new DecompileTask();
        task.setId(TASK_ID);
        task.setSystemId(SYS_ID);
        task.setRepositoryId(8701L);
        task.setStatus(TaskStatus.AI_ANALYZING.name());
        task.setType("INITIAL");
        task.setProgress(0);
        task.setDurationMs(0L);
        task.setPriority(50);
        task.setRequireHierarchyReview(Boolean.TRUE);
        task.setRequireEntrypointReview(Boolean.TRUE);
        task.setTriggerSource("MANUAL");
        taskMapper.insert(task);
    }

    /**
     * 在 ci_method_call 写入一条 OrderController → UserService 的调用记录。
     * OrderController 是 caller（有 caller_signature），
     * UserService 是 callee（只通过 dependencyName 出现，没有自己的 caller_signature）。
     */
    private void seedCallGraph() {
        MethodCall mc = new MethodCall();
        mc.setTaskId(TASK_ID);
        mc.setFilePath("src/main/java/com/demo/OrderController.java");
        mc.setClassName("OrderController");
        mc.setCallerMethod("createOrder");
        // caller_signature 格式："短类名#methodName(ParamTypes)"
        mc.setCallerSignature("OrderController#createOrder(OrderDTO)");
        // dependencyName 格式："variable:Type"，callee 用 FQ
        mc.setDependencyName("userService:com.demo.UserService");
        mc.setTargetMethod("findById");
        // MVP 阶段 targetSignature 仅方法名（不带参数也不带类名）
        mc.setTargetSignature("findById");
        mc.setExpression("userService.findById(id)");
        mc.setLineNumber(20);
        mc.setCreatedDate(LocalDateTime.now());
        methodCallMapper.insert(mc);
    }

    /**
     * 反射触发私有 persistMethodBindingsFromIncrement
     */
    private void invokePersist(Long taskId, Long systemId, EntryPoint entry,
                               JsonNode increment) throws Exception {
        Method m = ModuleHierarchyServiceImpl.class.getDeclaredMethod(
                "persistMethodBindingsFromIncrement",
                Long.class, Long.class, EntryPoint.class, JsonNode.class, Map.class);
        m.setAccessible(true);
        m.invoke(hierarchyService, taskId, systemId, entry, increment, new HashMap<>());
    }

    /**
     * 构造 AI 增量输出 JSON：function_node 引用一个 (module/sub/function) 三层结构
     */
    private JsonNode buildIncrementJson(List<String> classPaths, List<String> methodSignatures) throws Exception {
        String json = "{\n" +
                "  \"modules\": [\n" +
                "    {\n" +
                "      \"id\": \"m00001\",\n" +
                "      \"module_name\": \"订单模块\",\n" +
                "      \"sub_modules\": [\n" +
                "        {\n" +
                "          \"id\": \"s00001\",\n" +
                "          \"sub_module_name\": \"下单\",\n" +
                "          \"functions\": [\n" +
                "            {\n" +
                "              \"id\": \"f00001\",\n" +
                "              \"function_name\": \"创建订单\",\n" +
                "              \"class_paths\": " + MAPPER.writeValueAsString(classPaths) + ",\n" +
                "              \"method_signatures\": " + MAPPER.writeValueAsString(methodSignatures) + "\n" +
                "            }\n" +
                "          ]\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        return MAPPER.readTree(json);
    }

    private List<MethodFunctionBinding> queryBindings(String className) {
        return bindingMapper.selectList(
                new LambdaQueryWrapper<MethodFunctionBinding>()
                        .eq(MethodFunctionBinding::getTaskId, TASK_ID)
                        .eq(MethodFunctionBinding::getClassName, className)
        );
    }

    private EntryPoint buildEntry(String fqClassName) {
        EntryPoint e = new EntryPoint();
        e.setClassName(fqClassName);
        e.setEntryType("CONTROLLER");
        return e;
    }

    // =================== 测试用例 ===================

    /**
     * Tier 1：AI 输出的 caller 类 + caller 边方法 → 落 binding（保留原行为）
     */
    @Test
    public void testTier1AcceptsCallerMethod() throws Exception {
        seedCallGraph();

        EntryPoint entry = buildEntry("com.demo.OrderController");
        JsonNode increment = buildIncrementJson(
                List.of("com.demo.OrderController"),
                List.of("createOrder(OrderDTO)")
        );
        invokePersist(TASK_ID, SYS_ID, entry, increment);

        List<MethodFunctionBinding> rows = queryBindings("com.demo.OrderController");
        Assertions.assertEquals(1, rows.size(),
                "Tier 1：caller_signature 严格命中应落 binding");
        Assertions.assertEquals("createOrder(OrderDTO)", rows.get(0).getMethodSignature());
    }

    /**
     * Tier 2 兜底：AI 输出 callee 类 + 该类方法（caller_signature 里没有这个 class#sig，
     * 但 class 在 dependencyName 里出现过）→ 应落 binding（关键修复）
     */
    @Test
    public void testTier2FallbackAcceptsCalleeMethod() throws Exception {
        seedCallGraph();

        EntryPoint entry = buildEntry("com.demo.OrderController");
        // AI 输出 callee 类（UserService 是被 OrderController 调用的，从未作为 caller）
        JsonNode increment = buildIncrementJson(
                List.of("com.demo.UserService"),
                List.of("findById(Long)")
        );
        invokePersist(TASK_ID, SYS_ID, entry, increment);

        List<MethodFunctionBinding> rows = queryBindings("com.demo.UserService");
        Assertions.assertEquals(1, rows.size(),
                "Tier 2：callee 类（仅在 dependencyName 出现）应通过兜底落 binding（关键 bug 修复）");
        Assertions.assertEquals("findById(Long)", rows.get(0).getMethodSignature());
    }

    /**
     * 混合格式：AI 同时输出 caller 类 + callee 类的方法 → 两个都落 binding
     */
    @Test
    public void testTier2AcceptsMixedCallerAndCallee() throws Exception {
        seedCallGraph();

        EntryPoint entry = buildEntry("com.demo.OrderController");
        JsonNode increment = buildIncrementJson(
                List.of("com.demo.OrderController", "com.demo.UserService"),
                List.of("createOrder(OrderDTO)", "findById(Long)")
        );
        invokePersist(TASK_ID, SYS_ID, entry, increment);

        Assertions.assertEquals(1, queryBindings("com.demo.OrderController").size(),
                "caller 类的 createOrder 方法应落 binding");
        Assertions.assertEquals(1, queryBindings("com.demo.UserService").size(),
                "callee 类的 findById 方法应通过 Tier 2 落 binding");
    }

    /**
     * 幻觉拦截：AI 输出与调用图无关的类 → 拒绝落 binding
     */
    @Test
    public void testRejectsHallucinatedClass() throws Exception {
        seedCallGraph();

        EntryPoint entry = buildEntry("com.demo.OrderController");
        // 任意一个调用图里没有出现过的类
        JsonNode increment = buildIncrementJson(
                List.of("com.fake.NonExistentService"),
                List.of("doSomething()")
        );
        invokePersist(TASK_ID, SYS_ID, entry, increment);

        Assertions.assertTrue(queryBindings("com.fake.NonExistentService").isEmpty(),
                "调用图里既不作为 caller 也不作为 callee 的类应被拒绝（防 AI 幻觉）");
        // 整个 task 也不应有 binding
        Assertions.assertEquals(0, bindingMapper.selectCount(
                new LambdaQueryWrapper<MethodFunctionBinding>().eq(MethodFunctionBinding::getTaskId, TASK_ID)
        ));
    }

    /**
     * 空调用图：method_call 表为空 → 跳过交叉校验（不阻塞，避免回退路径锁死）
     */
    @Test
    public void testEmptyCallGraphSkipsCrossCheck() throws Exception {
        // 不 seed call graph
        EntryPoint entry = buildEntry("com.demo.OrderController");
        JsonNode increment = buildIncrementJson(
                List.of("com.demo.AnyService"),
                List.of("anyMethod()")
        );
        invokePersist(TASK_ID, SYS_ID, entry, increment);

        // 空集合视为"跳过交叉校验"，AI 输出应被接受（保留原回退行为）
        Assertions.assertEquals(1, queryBindings("com.demo.AnyService").size(),
                "调用图为空时应跳过交叉校验，AI 输出直接落 binding");
    }

    /**
     * 同 (class, sig) 被两个 function 声明 → 去重后只落 1 行（last-wins），不撞 UK。
     */
    @Test
    public void testDedupeSameClassMethodAcrossFunctions() throws Exception {
        // 空调用图 → 跳过交叉校验，两份声明都会进入 rows，再由去重收成 1 行
        EntryPoint entry = buildEntry("com.demo.OrderController");
        String json = "{\n" +
                "  \"modules\": [{\n" +
                "    \"id\": \"m00001\",\n" +
                "    \"module_name\": \"订单模块\",\n" +
                "    \"sub_modules\": [{\n" +
                "      \"id\": \"s00001\",\n" +
                "      \"sub_module_name\": \"下单\",\n" +
                "      \"functions\": [\n" +
                "        {\n" +
                "          \"id\": \"f00001\",\n" +
                "          \"function_name\": \"创建订单\",\n" +
                "          \"class_paths\": [\"com.demo.OrderController\"],\n" +
                "          \"method_signatures\": [\"createOrder(OrderDTO)\"]\n" +
                "        },\n" +
                "        {\n" +
                "          \"id\": \"f00002\",\n" +
                "          \"function_name\": \"提交订单\",\n" +
                "          \"class_paths\": [\"com.demo.OrderController\"],\n" +
                "          \"method_signatures\": [\"createOrder(OrderDTO)\"]\n" +
                "        }\n" +
                "      ]\n" +
                "    }]\n" +
                "  }]\n" +
                "}";
        invokePersist(TASK_ID, SYS_ID, entry, MAPPER.readTree(json));

        List<MethodFunctionBinding> rows = queryBindings("com.demo.OrderController");
        Assertions.assertEquals(1, rows.size(),
                "同 (class,sig) 多功能声明应去重为 1 行，避免 uk_mfb_task_class_method_active");
        Assertions.assertEquals("createOrder(OrderDTO)", rows.get(0).getMethodSignature());
        Assertions.assertEquals("f00002", rows.get(0).getFunctionNodeId(),
                "last-wins：保留后出现的 function");
    }
}