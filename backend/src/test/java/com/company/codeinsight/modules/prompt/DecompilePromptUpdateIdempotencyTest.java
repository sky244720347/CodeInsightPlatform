package com.company.codeinsight.modules.prompt;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.prompt.controller.DecompilePromptController;
import com.company.codeinsight.modules.prompt.entity.DecompilePrompt;
import com.company.codeinsight.modules.prompt.service.DecompilePromptService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * 提示词 updatePrompt 端点幂等保护测试（无 Spring 容器）
 *
 * <p>验证：name + content 完全未变时，后端不会写 DB，也不会让 version+1；</p>
 * <p>反之，任意字段变化都会触发正常写入与 version 递增。</p>
 */
@DisplayName("提示词 updatePrompt 幂等保护")
public class DecompilePromptUpdateIdempotencyTest {

    private DecompilePromptService promptService;
    private OperationLogService operationLogService;
    private DecompilePromptController controller;

    @BeforeEach
    void setUp() {
        promptService = Mockito.mock(DecompilePromptService.class);
        operationLogService = Mockito.mock(OperationLogService.class);
        controller = new DecompilePromptController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "decompilePromptService", promptService);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "operationLogService", operationLogService);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "systemMapper",
                org.mockito.Mockito.mock(com.company.codeinsight.modules.system.mapper.SystemApplicationMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "repoMapper",
                org.mockito.Mockito.mock(com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper.class));
    }

    @Test
    @DisplayName("name + content 完全一致 → 不写 DB、version 不 +1、返回原对象")
    public void testUpdate_noChange_skipsWriteAndVersionBump() {
        // 准备：DB 里有 v3 草稿
        DecompilePrompt existing = new DecompilePrompt();
        existing.setId(100L);
        existing.setName("模块提取默认提示词");
        existing.setContent("# Java 分析\n请分析以下 Java 源码");
        existing.setVersion(3);
        existing.setIsDefault(1);
        existing.setPromptType("MODULARIZE");
        existing.setLifecycle("DRAFT");
        existing.setCategory("DEFAULT");
        Mockito.when(promptService.getById(100L)).thenReturn(existing);

        // 客户端提交同样的 name + content
        DecompilePrompt payload = new DecompilePrompt();
        payload.setId(100L);
        payload.setName("模块提取默认提示词");
        payload.setContent("# Java 分析\n请分析以下 Java 源码");
        payload.setPromptType("MODULARIZE");
        payload.setIsDefault(1);

        ApiResponse<DecompilePrompt> resp = controller.updatePrompt(100L, payload);

        // 断言 1：返回成功
        Assertions.assertEquals(0, resp.getCode());
        // 断言 2：返回的对象 version 仍是 3（没有 +1）
        Assertions.assertEquals(3, resp.getData().getVersion(), "无变化时 version 不应 +1");
        // 断言 3：返回的对象内容不变
        Assertions.assertEquals("# Java 分析\n请分析以下 Java 源码", resp.getData().getContent());
        Assertions.assertEquals("模块提取默认提示词", resp.getData().getName());
        // 断言 4：从未调过 updateById
        Mockito.verify(promptService, Mockito.never()).updateById(Mockito.any(DecompilePrompt.class));
        // 断言 5：从未写过操作日志
        Mockito.verify(operationLogService, Mockito.never()).logOperation(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean()
        );
    }

    @Test
    @DisplayName("content 改了 → 正常写 DB、version +1、记录操作日志")
    public void testUpdate_contentChanged_writesAndBumpsVersion() {
        DecompilePrompt existing = new DecompilePrompt();
        existing.setId(101L);
        existing.setName("文档生成提示词");
        existing.setContent("旧内容");
        existing.setVersion(7);
        existing.setIsDefault(0);
        existing.setPromptType("DOCUMENT_GENERATION");
        existing.setLifecycle("DRAFT");
        existing.setCategory("DEFAULT");
        Mockito.when(promptService.getById(101L)).thenReturn(existing);

        DecompilePrompt payload = new DecompilePrompt();
        payload.setId(101L);
        payload.setName("文档生成提示词");
        payload.setContent("新内容 — 改了一行");
        payload.setPromptType("DOCUMENT_GENERATION");
        payload.setIsDefault(0);

        ApiResponse<DecompilePrompt> resp = controller.updatePrompt(101L, payload);

        Assertions.assertEquals(0, resp.getCode());
        Assertions.assertEquals(8, resp.getData().getVersion(), "有变化时 version 应该 +1（7 → 8）");
        Assertions.assertEquals("新内容 — 改了一行", resp.getData().getContent());

        // 验证 updateById 收到的参数
        ArgumentCaptor<DecompilePrompt> captor = ArgumentCaptor.forClass(DecompilePrompt.class);
        Mockito.verify(promptService, Mockito.times(1)).updateById(captor.capture());
        DecompilePrompt written = captor.getValue();
        Assertions.assertEquals(101L, written.getId());
        Assertions.assertEquals(8, written.getVersion());
        Assertions.assertEquals("DRAFT", written.getLifecycle(), "lifecycle 必须保持 DRAFT（不可在 update 中改）");
        Assertions.assertEquals("新内容 — 改了一行", written.getContent());

        // 验证写了操作日志
        Mockito.verify(operationLogService, Mockito.times(1)).logOperation(
                Mockito.isNull(), Mockito.isNull(),
                Mockito.eq("UPDATE_PROMPT"),
                Mockito.contains("新版本号: 8"),
                Mockito.isNull(), Mockito.eq(true)
        );
    }

    @Test
    @DisplayName("仅 name 改了（content 没变）→ 写 DB、version +1")
    public void testUpdate_nameOnlyChanged_writesAndBumpsVersion() {
        DecompilePrompt existing = new DecompilePrompt();
        existing.setId(102L);
        existing.setName("旧名字");
        existing.setContent("内容不变");
        existing.setVersion(2);
        existing.setIsDefault(0);
        existing.setPromptType("MODULARIZE");
        existing.setLifecycle("DRAFT");
        existing.setCategory("DEFAULT");
        Mockito.when(promptService.getById(102L)).thenReturn(existing);

        DecompilePrompt payload = new DecompilePrompt();
        payload.setId(102L);
        payload.setName("新名字");
        payload.setContent("内容不变");
        payload.setPromptType("MODULARIZE");
        payload.setIsDefault(0);

        ApiResponse<DecompilePrompt> resp = controller.updatePrompt(102L, payload);

        Assertions.assertEquals(0, resp.getCode());
        Assertions.assertEquals(3, resp.getData().getVersion(), "name 改了也应 +1（2 → 3）");
        Assertions.assertEquals("新名字", resp.getData().getName());
        Mockito.verify(promptService, Mockito.times(1)).updateById(Mockito.any(DecompilePrompt.class));
    }

    @Test
    @DisplayName("RELEASED 状态 + 无变化 → 仍抛'已发布不可编辑'（生命周期校验优先于幂等检查）")
    public void testUpdate_releasedLifecycle_rejectedBeforeIdempotency() {
        DecompilePrompt existing = new DecompilePrompt();
        existing.setId(103L);
        existing.setName("已发布");
        existing.setContent("内容");
        existing.setVersion(5);
        existing.setLifecycle("RELEASED");  // 已发布
        Mockito.when(promptService.getById(103L)).thenReturn(existing);

        DecompilePrompt payload = new DecompilePrompt();
        payload.setName("已发布");
        payload.setContent("内容");

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> controller.updatePrompt(103L, payload));
        Assertions.assertTrue(ex.getMessage().contains("已发布"));
        // RELEASED 校验通过后根本不会进幂等分支
        Mockito.verify(promptService, Mockito.never()).updateById(Mockito.any(DecompilePrompt.class));
    }

    @Test
    @DisplayName("id 不存在 → 抛'提示词模板不存在'")
    public void testUpdate_notFound_throws() {
        Mockito.when(promptService.getById(999L)).thenReturn(null);

        DecompilePrompt payload = new DecompilePrompt();
        payload.setName("x");
        payload.setContent("y");

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> controller.updatePrompt(999L, payload));
        Assertions.assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("name 改成 null + content 改成 null 都仍等于已存的 null → 视为无变化（null 安全）")
    public void testUpdate_nullEqualsNull_treatedAsNoChange() {
        DecompilePrompt existing = new DecompilePrompt();
        existing.setId(104L);
        existing.setName(null);
        existing.setContent(null);
        existing.setVersion(1);
        existing.setLifecycle("DRAFT");
        Mockito.when(promptService.getById(104L)).thenReturn(existing);

        DecompilePrompt payload = new DecompilePrompt();
        payload.setName(null);
        payload.setContent(null);

        ApiResponse<DecompilePrompt> resp = controller.updatePrompt(104L, payload);
        Assertions.assertEquals(0, resp.getCode());
        Assertions.assertEquals(1, resp.getData().getVersion(), "null == null 不应 +1");
        Mockito.verify(promptService, Mockito.never()).updateById(Mockito.any(DecompilePrompt.class));
    }
}
