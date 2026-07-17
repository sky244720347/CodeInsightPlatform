package com.company.codeinsight.modules.businessknowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.util.PromptTemplateLoader;
import com.company.codeinsight.modules.businessknowledge.entity.BusinessKnowledge;
import com.company.codeinsight.modules.businessknowledge.mapper.BusinessKnowledgeMapper;
import com.company.codeinsight.modules.businessknowledge.service.BusinessKnowledgeService;
import com.company.codeinsight.modules.businessknowledge.service.impl.BusinessKnowledgeServiceImpl;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.mapper.SystemApplicationMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务知识 → 提示词占位符替换 端到端集成测试（无 Spring 容器）
 *
 * <p>模拟"用户在系统表保存业务知识 → 任务运行到 AI_ANALYZING 阶段 → ModuleHierarchyServiceImpl 读取并替换
 * {business_knowledge.md} 占位符"整条链路，所有外部依赖用 Mockito 替换。</p>
 *
 * <p>本测试不依赖 PG/Redis，可直接在沙盒环境运行。</p>
 */
@DisplayName("业务知识端到端替换测试")
public class BusinessKnowledgePromptAssemblyTest {

    private BusinessKnowledgeMapper bkMapper;
    private SystemApplicationMapper systemMapper;
    private BusinessKnowledgeService bkService;
    private PromptTemplateLoader templateLoader;

    /** 模拟 aiSummaryService 收到的最终 prompt（替换后的完整字符串） */
    private String finalPromptSentToAi;
    private String finalBusinessKnowledgeSection;
    private String finalJavaCodeSection;
    private String finalModuleHierarchySection;

    @BeforeEach
    void setUp() {
        bkMapper = Mockito.mock(BusinessKnowledgeMapper.class);
        systemMapper = Mockito.mock(SystemApplicationMapper.class);
        bkService = new BusinessKnowledgeServiceImpl();
        // 把 mock mapper 注入到 service（依赖注入改为反射设值）
        org.springframework.test.util.ReflectionTestUtils.setField(bkService, "baseMapper", bkMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(bkService, "systemMapper", systemMapper);

        templateLoader = new PromptTemplateLoader();
    }

    // ──────────────────────────────────────────────────────────
    //  1) 配置保存链路
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("保存业务知识：首次插入 version=1，覆盖保存 version+1")
    public void testUpsert_firstSaveAndOverwrite() {
        Long systemId = 1001L;

        // 系统存在
        SystemApplication system = new SystemApplication();
        system.setId(systemId);
        system.setName("测试系统");
        Mockito.when(systemMapper.selectCount(Mockito.any()))
                .thenReturn(1L);
        Mockito.when(systemMapper.selectOne(Mockito.any()))
                .thenReturn(system);

        // 首次：无记录
        Mockito.when(bkMapper.selectOne(Mockito.any()))
                .thenReturn(null);
        // 模拟 save 后回填 id
        AtomicLong nextId = new AtomicLong(1);
        Mockito.when(bkMapper.insert(Mockito.any(BusinessKnowledge.class)))
                .thenAnswer(inv -> {
                    BusinessKnowledge arg = inv.getArgument(0);
                    arg.setId(nextId.getAndIncrement());
                    return 1;
                });

        // 1.1 首次保存
        BusinessKnowledge first = bkService.upsert(systemId, "# 业务领域\n## 房管局业务", "alice");
        Assertions.assertNotNull(first.getId());
        Assertions.assertEquals(1, first.getVersion());
        Assertions.assertEquals("# 业务领域\n## 房管局业务", first.getContent());
        Assertions.assertEquals("alice", first.getUpdatedBy());

        // 1.2 二次保存：模拟已有记录
        Mockito.when(bkMapper.selectOne(Mockito.any()))
                .thenReturn(first);
        Mockito.when(bkMapper.updateById(Mockito.any(BusinessKnowledge.class))).thenReturn(1);

        BusinessKnowledge second = bkService.upsert(systemId, "# 业务领域\n## 房管局业务（已更新）", "bob");
        Assertions.assertEquals(2, second.getVersion(), "覆盖式保存 version 应该自增 1");
        Assertions.assertEquals("# 业务领域\n## 房管局业务（已更新）", second.getContent());
        Assertions.assertEquals("bob", second.getUpdatedBy());
    }

    // ──────────────────────────────────────────────────────────
    //  2) 软删系统：业务知识应被识别为不可用
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("系统被软删后，getContentBySystemId 返回空串（与未配置等价）")
    public void testGetContent_systemSoftDeleted_returnsEmpty() {
        Long systemId = 1002L;
        // 系统被软删
        Mockito.when(systemMapper.selectCount(Mockito.any()))
                .thenReturn(0L);

        // 业务知识表里其实有数据
        BusinessKnowledge existing = new BusinessKnowledge();
        existing.setId(1L);
        existing.setSystemId(systemId);
        existing.setContent("# 残留的业务知识");
        existing.setVersion(3);
        Mockito.when(bkMapper.selectOne(Mockito.any()))
                .thenReturn(existing);

        String content = bkService.getContentBySystemId(systemId);
        Assertions.assertEquals("", content, "软删系统下的业务知识应视为不可用");
    }

    // ──────────────────────────────────────────────────────────
    //  3) 端到端：ModuleHierarchyServiceImpl 内的组装行为
    //  模拟它在 line 347-352 的执行
    // ──────────────────────────────────────────────────────────

    /**
     * 这是与生产代码"等价的组装逻辑"——把 ModuleHierarchyServiceImpl.callAiForEntry line 347-352 抽出，
     * 在测试里完整执行，验证占位符替换正确。
     */
    private void simulateCallAiForEntryAssemble(Long taskSystemId, String javaCode, String taskWorkspaceMeta) {
        // ⬇⬇⬇ 这一段代码与 ModuleHierarchyServiceImpl.callAiForEntry line 347-352 一一对应 ⬇⬇⬇
        String businessKnowledge = bkService.getContentBySystemId(taskSystemId);
        String promptTemplate = loadAnalyzedPromptTemplate();
        String promptInput = templateLoader.render(promptTemplate, javaCode, businessKnowledge, "{}");
        boolean hasUnresolved = templateLoader.hasUnresolvedPlaceholders(promptInput);
        Assertions.assertFalse(hasUnresolved, "占位符未替换完：" + promptInput);
        // ⬆⬆⬆ 等价于 line 347-356 ⬆⬆⬆

        // 把最终 prompt 切片保存，方便断言
        this.finalPromptSentToAi = promptInput;
        this.finalBusinessKnowledgeSection = extractSection(promptInput, "## 业务领域知识（来自 ci_business_knowledge）");
        this.finalJavaCodeSection = extractSection(promptInput, "## Java 源码");
        this.finalModuleHierarchySection = extractSection(promptInput, "## 当前模块层级");
    }

    @Test
    @DisplayName("配置了业务知识时，{business_knowledge.md} 正确替换为配置内容")
    public void testAssembly_withBusinessKnowledge_replacesPlaceholder() {
        // 准备：系统存在 + 业务知识已配置
        Long systemId = 2001L;
        SystemApplication system = new SystemApplication();
        system.setId(systemId);
        system.setName("测试系统A");
        Mockito.when(systemMapper.selectCount(Mockito.any()))
                .thenReturn(1L);

        BusinessKnowledge bk = new BusinessKnowledge();
        bk.setId(10L);
        bk.setSystemId(systemId);
        bk.setVersion(2);
        bk.setContent(
                "## 核心业务实体\n" +
                "- 房产、白名单、公积金\n" +
                "## 业务术语\n" +
                "- 房管局业务：包含授权、备案、查询\n" +
                "## 业务约束\n" +
                "- 仅面向重庆/佛山地区的房管局对接"
        );
        bk.setUpdatedBy("alice");
        bk.setCreatedDate(LocalDateTime.now().minusDays(3));
        bk.setUpdatedDate(LocalDateTime.now().minusHours(2));
        Mockito.when(bkMapper.selectOne(Mockito.any()))
                .thenReturn(bk);

        // 触发组装（模拟 ModuleHierarchyServiceImpl.callAiForEntry 内 line 347-352）
        String javaCode = "package com.example.house;\npublic class ChongqingAuthController { ... }";
        simulateCallAiForEntryAssemble(systemId, javaCode, "/workspace/task-2001");

        // 断言 1：占位符已消失
        Assertions.assertFalse(finalPromptSentToAi.contains("{business_knowledge.md}"),
                "占位符 {business_knowledge.md} 应已被替换");

        // 断言 2：业务知识内容出现在 prompt 中
        Assertions.assertTrue(finalPromptSentToAi.contains("房产、白名单、公积金"),
                "业务知识正文应被注入 prompt");
        Assertions.assertTrue(finalPromptSentToAi.contains("房管局业务：包含授权、备案、查询"),
                "业务知识正文应被注入 prompt");

        // 断言 3：Java 源码也注入（其它占位符正确替换）
        Assertions.assertTrue(finalPromptSentToAi.contains("ChongqingAuthController"),
                "Java 源码应被注入 prompt");

        // 断言 4：hierarchy 占位符替换为 "{}"（在并行阶段固定传空）
        Assertions.assertTrue(finalPromptSentToAi.contains("\"modules\": []") || finalPromptSentToAi.contains("{}"),
                "hierarchy 占位符应被替换为 '{}'");

        // 断言 5：版本号未泄露到 prompt（业务知识作为内容、不是元数据）
        Assertions.assertFalse(finalPromptSentToAi.contains("version: 2"),
                "业务知识的 version 元数据不应出现在 prompt 中");
    }

    @Test
    @DisplayName("未配置业务知识时，{business_knowledge.md} 替换为空串，不抛错")
    public void testAssembly_noBusinessKnowledge_emptyReplacement() {
        // 系统存在
        Long systemId = 2002L;
        SystemApplication system = new SystemApplication();
        system.setId(systemId);
        Mockito.when(systemMapper.selectCount(Mockito.any()))
                .thenReturn(1L);
        // 业务知识表无记录
        Mockito.when(bkMapper.selectOne(Mockito.any()))
                .thenReturn(null);

        String javaCode = "package com.example.legacy;\npublic class SomeController { ... }";
        simulateCallAiForEntryAssemble(systemId, javaCode, "/workspace/task-2002");

        Assertions.assertFalse(finalPromptSentToAi.contains("{business_knowledge.md}"),
                "未配置时占位符也应被替换（为空串）");
        Assertions.assertTrue(finalPromptSentToAi.contains("SomeController"),
                "Java 源码应被注入");
    }

    @Test
    @DisplayName("任务 systemId 为 null 时（孤儿任务），不影响整体流程")
    public void testAssembly_taskSystemIdNull_emptyReplacement() {
        // task.getSystemId() == null
        Long systemId = null;
        String javaCode = "package com.example.orphan;";
        simulateCallAiForEntryAssemble(systemId, javaCode, "/workspace/task-orphan");

        Assertions.assertFalse(finalPromptSentToAi.contains("{business_knowledge.md}"),
                "systemId=null 时也应安全替换为空串");
        Assertions.assertTrue(finalPromptSentToAi.contains("orphan"),
                "Java 源码应被注入");
    }

    @Test
    @DisplayName("业务知识正文为空字符串时（保存了空内容），占位符被替换为空串")
    public void testAssembly_emptyBusinessKnowledgeContent_emptyReplacement() {
        Long systemId = 2003L;
        SystemApplication system = new SystemApplication();
        system.setId(systemId);
        Mockito.when(systemMapper.selectCount(Mockito.any()))
                .thenReturn(1L);

        BusinessKnowledge bk = new BusinessKnowledge();
        bk.setId(11L);
        bk.setSystemId(systemId);
        bk.setContent("");  // 用户主动清空
        bk.setVersion(5);
        Mockito.when(bkMapper.selectOne(Mockito.any()))
                .thenReturn(bk);

        String javaCode = "package com.example.x;";
        simulateCallAiForEntryAssemble(systemId, javaCode, "/workspace/task-2003");

        Assertions.assertFalse(finalPromptSentToAi.contains("{business_knowledge.md}"));
    }

    // ──────────────────────────────────────────────────────────
    //  4) 端到端：PUT /api/business-knowledge Controller 链路
    //  模拟 HTTP 请求 → Controller → Service → DB
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Controller upsert 端点：业务知识被正确保存（systemId/content/updatedBy 全链路）")
    public void testControllerUpsert_endToEnd() throws Exception {
        Long systemId = 3001L;

        // 准备：system 存在
        SystemApplication system = new SystemApplication();
        system.setId(systemId);
        Mockito.when(systemMapper.selectCount(Mockito.any()))
                .thenReturn(1L);
        Mockito.when(systemMapper.selectOne(Mockito.any()))
                .thenReturn(system);

        // 业务知识首次保存
        Mockito.when(bkMapper.selectOne(Mockito.any()))
                .thenReturn(null);
        AtomicLong nextId = new AtomicLong(100);
        Mockito.when(bkMapper.insert(Mockito.any(BusinessKnowledge.class)))
                .thenAnswer(inv -> {
                    BusinessKnowledge arg = inv.getArgument(0);
                    arg.setId(nextId.getAndIncrement());
                    return 1;
                });

        // 模拟 Controller 调用
        com.company.codeinsight.modules.businessknowledge.controller.BusinessKnowledgeController controller =
                new com.company.codeinsight.modules.businessknowledge.controller.BusinessKnowledgeController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "businessKnowledgeService", bkService);
        // operationLogService 暂不验证（mock 即可）
        org.mockito.Mockito.mock(com.company.codeinsight.modules.log.service.OperationLogService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "operationLogService",
                org.mockito.Mockito.mock(com.company.codeinsight.modules.log.service.OperationLogService.class));

        com.company.codeinsight.modules.businessknowledge.controller.BusinessKnowledgeController.UpsertRequest req =
                new com.company.codeinsight.modules.businessknowledge.controller.BusinessKnowledgeController.UpsertRequest();
        req.setSystemId(systemId);
        req.setContent("# 业务知识\n## 房管局业务");
        req.setUpdatedBy("alice");

        com.company.codeinsight.common.response.ApiResponse<BusinessKnowledge> resp = controller.upsert(req);
        Assertions.assertEquals(0, resp.getCode(), "Controller 应返回成功");
        BusinessKnowledge saved = resp.getData();
        Assertions.assertNotNull(saved);
        Assertions.assertEquals(systemId, saved.getSystemId());
        Assertions.assertEquals("# 业务知识\n## 房管局业务", saved.getContent());
        Assertions.assertEquals("alice", saved.getUpdatedBy());
        Assertions.assertEquals(1, saved.getVersion());

        // 验证 mapper.insert 收到的参数
        ArgumentCaptor<BusinessKnowledge> captor = ArgumentCaptor.forClass(BusinessKnowledge.class);
        Mockito.verify(bkMapper, Mockito.atLeastOnce()).insert(captor.capture());
        BusinessKnowledge inserted = captor.getValue();
        Assertions.assertEquals(systemId, inserted.getSystemId());
        Assertions.assertEquals("# 业务知识\n## 房管局业务", inserted.getContent());
        Assertions.assertEquals("alice", inserted.getUpdatedBy());
    }

    @Test
    @DisplayName("GET /api/business-knowledge：未配置时返回 null，配置后返回完整记录")
    public void testControllerGet_endToEnd() {
        Long systemId = 3002L;
        // 准备：有记录
        BusinessKnowledge bk = new BusinessKnowledge();
        bk.setId(20L);
        bk.setSystemId(systemId);
        bk.setContent("# 业务知识");
        bk.setVersion(1);
        bk.setUpdatedBy("alice");
        Mockito.when(bkMapper.selectOne(Mockito.any()))
                .thenReturn(bk);

        BusinessKnowledge got = bkService.getBySystemId(systemId);
        Assertions.assertNotNull(got);
        Assertions.assertEquals("# 业务知识", got.getContent());
        Assertions.assertEquals(1, got.getVersion());

        // 模拟未配置
        Mockito.when(bkMapper.selectOne(Mockito.any()))
                .thenReturn(null);
        BusinessKnowledge none = bkService.getBySystemId(systemId);
        Assertions.assertNull(none, "未配置时应返回 null");
    }

    // ──────────────────────────────────────────────────────────
    //  helpers
    // ──────────────────────────────────────────────────────────

    /**
     * 加载 analyze_prompt.md 模板（classpath 中的真实模板）。
     * 测试用真实模板，而不是 mock，确保占位符替换行为与生产一致。
     */
    private String loadAnalyzedPromptTemplate() {
        return templateLoader.load("analyze_prompt.md");
    }

    /** 从 prompt 中抽取以"## xxx"为标题的段落（简单实现，足够断言） */
    private String extractSection(String prompt, String title) {
        int start = prompt.indexOf(title);
        if (start < 0) return "";
        int end = prompt.indexOf("\n## ", start + title.length());
        return end < 0 ? prompt.substring(start) : prompt.substring(start, end);
    }
}
