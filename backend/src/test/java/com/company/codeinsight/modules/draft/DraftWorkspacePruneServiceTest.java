package com.company.codeinsight.modules.draft;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.mapper.DraftReviewCommentMapper;
import com.company.codeinsight.modules.draft.mapper.DraftRevisionMapper;
import com.company.codeinsight.modules.draft.mapper.DraftSourceReferenceMapper;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.draft.service.DraftWorkspacePruneService;
import com.company.codeinsight.modules.hierarchy.model.FunctionDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.model.SubModuleDto;
import com.company.codeinsight.modules.hierarchy.service.ModuleHierarchyService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DraftWorkspacePruneService：不依赖 Spring / PG / Redis。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("增量工作区失效草稿裁剪")
class DraftWorkspacePruneServiceTest {

    @Mock ModuleHierarchyService moduleHierarchyService;
    @Mock KnowledgeDraftMapper draftMapper;
    @Mock DraftWorkspaceMapper workspaceMapper;
    @Mock DraftSourceReferenceMapper sourceReferenceMapper;
    @Mock DraftRevisionMapper revisionMapper;
    @Mock DraftReviewCommentMapper commentMapper;
    @Mock EnvStorageResolver storageResolver;
    @Mock DecompileTaskMapper taskMapper;

    @TempDir Path tempDir;

    private DraftWorkspacePruneService service;

    @BeforeEach
    void setUp() {
        service = new DraftWorkspacePruneService();
        ReflectionTestUtils.setField(service, "moduleHierarchyService", moduleHierarchyService);
        ReflectionTestUtils.setField(service, "draftMapper", draftMapper);
        ReflectionTestUtils.setField(service, "workspaceMapper", workspaceMapper);
        ReflectionTestUtils.setField(service, "sourceReferenceMapper", sourceReferenceMapper);
        ReflectionTestUtils.setField(service, "revisionMapper", revisionMapper);
        ReflectionTestUtils.setField(service, "commentMapper", commentMapper);
        ReflectionTestUtils.setField(service, "storageResolver", storageResolver);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "docGenerationGranularity", "function");
    }

    @Test
    @DisplayName("collectExpectedModuleNames：function 粒度拼三级名")
    void collectExpectedFunctionGranularity() {
        ModuleHierarchy h = sampleHierarchy();
        Set<String> names = DraftWorkspacePruneService.collectExpectedModuleNames(h, "function");
        assertEquals(Set.of(
                "商品管理 / 商品查询 / 商品列表查询",
                "系统管理 / 系统监控 / 欢迎接口"
        ), names);
    }

    @Test
    @DisplayName("collectExpectedModuleNames：module 粒度仅模块名")
    void collectExpectedModuleGranularity() {
        ModuleHierarchy h = sampleHierarchy();
        Set<String> names = DraftWorkspacePruneService.collectExpectedModuleNames(h, "module");
        assertEquals(Set.of("商品管理", "系统管理"), names);
    }

    @Test
    @DisplayName("prune：删除不在 hierarchy 的孤儿继承草稿，保留合法篇")
    void pruneRemovesOrphansKeepsExpected() throws Exception {
        when(moduleHierarchyService.loadByTaskId(6L)).thenReturn(sampleHierarchy());

        KnowledgeDraft keep = draft(43L, "商品管理 / 商品查询 / 商品列表查询", "task_6/a.md");
        KnowledgeDraft orphan = draft(51L, "订单管理 / 订单报价 / 订单报价查询", "task_6/orphan.md");
        Path orphanFile = tempDir.resolve("orphan.md");
        Files.writeString(orphanFile, "stale");
        orphan.setContentUri(orphanFile.toUri().toString());

        when(draftMapper.selectList(any())).thenReturn(List.of(keep, orphan));
        when(draftMapper.deleteById(51L)).thenReturn(1);
        when(storageResolver.draftsRoot()).thenReturn(tempDir);

        int removed = service.pruneStaleDrafts(6L, 5L);
        assertEquals(1, removed);
        verify(draftMapper).deleteById(51L);
        verify(draftMapper, never()).deleteById(43L);
        verify(sourceReferenceMapper).delete(any());
        verify(revisionMapper).delete(any());
        verify(commentMapper).delete(any());
        assertFalse(Files.exists(orphanFile));
    }

    @Test
    @DisplayName("prune：幂等 — 无多余草稿时 removed=0")
    void pruneIdempotentWhenAligned() {
        when(moduleHierarchyService.loadByTaskId(6L)).thenReturn(sampleHierarchy());
        KnowledgeDraft keep = draft(43L, "商品管理 / 商品查询 / 商品列表查询", "task_6/a.md");
        KnowledgeDraft keep2 = draft(50L, "系统管理 / 系统监控 / 欢迎接口", "task_6/b.md");
        when(draftMapper.selectList(any())).thenReturn(List.of(keep, keep2));

        assertEquals(0, service.pruneStaleDrafts(6L, 5L));
        verify(draftMapper, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("assertWorkspaceMatchesHierarchy：多余草稿抛错")
    void assertRejectsExtra() {
        DecompileTask task = new DecompileTask();
        task.setId(6L);
        task.setType("INCREMENTAL");
        when(taskMapper.selectById(6L)).thenReturn(task);

        DraftWorkspace ws = new DraftWorkspace();
        ws.setId(5L);
        when(workspaceMapper.selectOne(any())).thenReturn(ws);
        when(moduleHierarchyService.loadByTaskId(6L)).thenReturn(sampleHierarchy());
        when(draftMapper.selectList(any())).thenReturn(List.of(
                draft(43L, "商品管理 / 商品查询 / 商品列表查询", "a.md"),
                draft(50L, "系统管理 / 系统监控 / 欢迎接口", "b.md"),
                draft(51L, "订单管理 / 订单报价 / 订单报价查询", "c.md")
        ));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertWorkspaceMatchesHierarchy(6L));
        assertTrue(ex.getMessage().contains("多余草稿"));
        assertTrue(ex.getMessage().contains("订单管理"));
    }

    @Test
    @DisplayName("assertWorkspaceMatchesHierarchy：INITIAL 跳过")
    void assertSkipsInitial() {
        DecompileTask task = new DecompileTask();
        task.setId(1L);
        task.setType("INITIAL");
        when(taskMapper.selectById(1L)).thenReturn(task);

        service.assertWorkspaceMatchesHierarchy(1L);
        verify(moduleHierarchyService, never()).loadByTaskId(any());
    }

    @Test
    @DisplayName("assertWorkspaceMatchesHierarchy：对齐时通过")
    void assertPassesWhenAligned() {
        DecompileTask task = new DecompileTask();
        task.setId(6L);
        task.setType("INCREMENTAL");
        when(taskMapper.selectById(6L)).thenReturn(task);

        DraftWorkspace ws = new DraftWorkspace();
        ws.setId(5L);
        when(workspaceMapper.selectOne(any())).thenReturn(ws);
        when(moduleHierarchyService.loadByTaskId(6L)).thenReturn(sampleHierarchy());
        when(draftMapper.selectList(any())).thenReturn(List.of(
                draft(43L, "商品管理 / 商品查询 / 商品列表查询", "a.md"),
                draft(50L, "系统管理 / 系统监控 / 欢迎接口", "b.md")
        ));

        service.assertWorkspaceMatchesHierarchy(6L);
    }

    private static KnowledgeDraft draft(Long id, String moduleName, String filePath) {
        KnowledgeDraft d = new KnowledgeDraft();
        d.setId(id);
        d.setModuleName(moduleName);
        d.setFilePath(filePath);
        d.setWorkspaceId(5L);
        d.setStatus("CONFIRMED");
        return d;
    }

    private static ModuleHierarchy sampleHierarchy() {
        ModuleHierarchy h = new ModuleHierarchy();
        h.setTaskId(6L);

        ModuleDto product = new ModuleDto();
        product.setId("m1");
        product.setModuleName("商品管理");
        SubModuleDto query = new SubModuleDto();
        query.setId("s1");
        query.setSubModuleName("商品查询");
        FunctionDto list = new FunctionDto();
        list.setId("f1");
        list.setFunctionName("商品列表查询");
        query.getFunctions().put(list.getId(), list);
        product.getSubModules().put(query.getId(), query);

        ModuleDto system = new ModuleDto();
        system.setId("m2");
        system.setModuleName("系统管理");
        SubModuleDto mon = new SubModuleDto();
        mon.setId("s2");
        mon.setSubModuleName("系统监控");
        FunctionDto hello = new FunctionDto();
        hello.setId("f2");
        hello.setFunctionName("欢迎接口");
        mon.getFunctions().put(hello.getId(), hello);
        system.getSubModules().put(mon.getId(), mon);

        h.getModules().put(product.getId(), product);
        h.getModules().put(system.getId(), system);
        return h;
    }
}
