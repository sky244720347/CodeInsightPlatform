package com.company.codeinsight.modules.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.modules.ai.service.AiSummaryService;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.draft.entity.DraftSourceReference;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.mapper.DraftSourceReferenceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.hierarchy.entity.ModuleHierarchyNode;
import com.company.codeinsight.modules.hierarchy.mapper.ModuleHierarchyNodeMapper;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.List;

/**
 * 项 3 整链路集成测试：基于 ModuleHierarchy DTO 整模块喂 AI 生成 md
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class AiSummaryModuleDocTest {

    @Autowired private AiSummaryService aiSummaryService;
    @Autowired private DecompileTaskMapper taskMapper;
    @Autowired private MethodCallMapper methodCallMapper;
    @Autowired private ModuleHierarchyNodeMapper nodeMapper;
    @Autowired private KnowledgeDraftMapper draftMapper;
    @Autowired private DraftSourceReferenceMapper refMapper;

    /**
     * DTO 路径：构造 ModuleHierarchyNode 树 + 1 个 Function.classPaths，
     * 验证 generateDraftDocument 走新路径、写出 KnowledgeDraft + source references
     */
    @Test
    public void testGenerateModuleDocFromHierarchy() throws Exception {
        Long taskId = 8101L;
        Long systemId = 1L;
        Long repositoryId = 1L;

        // 1. 建 Task
        DecompileTask task = new DecompileTask();
        task.setId(taskId);
        task.setSystemId(systemId);
        task.setRepositoryId(repositoryId);
        task.setStatus("DRAFT");
        task.setType("INITIAL");
        task.setProgress(0);
        task.setModelName("");
        taskMapper.insert(task);

        // 2. 建物理项目目录 + 入口类源码
        File projectDir = new File("temp_repos/task_" + taskId);
        File srcFile = new File(projectDir, "src/main/java/com/demo/WhiteListController.java");
        srcFile.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(srcFile)) {
            w.write("package com.demo;\npublic class WhiteListController {}\n");
        }

        // 3. 插入 MethodCall（让 collectReachableSource 能 BFS 到物理文件）
        MethodCall mc = new MethodCall();
        mc.setTaskId(taskId);
        mc.setFilePath("src/main/java/com/demo/WhiteListController.java");
        mc.setClassName("WhiteListController");
        mc.setCallerMethod("list");
        mc.setDependencyName("userService:UserService");
        mc.setTargetMethod("findById");
        mc.setCreatedDate(java.time.LocalDateTime.now());
        methodCallMapper.insert(mc);

        // 4. 插入 ModuleHierarchyNode（手动跳过 AI 提炼，直接构造 DTO）
        ModuleHierarchyNode modRow = new ModuleHierarchyNode();
        modRow.setTaskId(taskId);
        modRow.setSystemId(systemId);
        modRow.setLevel("MODULE");
        modRow.setParentId(null);
        modRow.setNodeId("m00001");
        modRow.setName("用户管理");
        modRow.setKeywords("[\"用户\",\"权限\"]");
        nodeMapper.insert(modRow);
        Long moduleRowId = modRow.getId();

        ModuleHierarchyNode subRow = new ModuleHierarchyNode();
        subRow.setTaskId(taskId);
        subRow.setSystemId(systemId);
        subRow.setLevel("SUB_MODULE");
        subRow.setParentId(moduleRowId);
        subRow.setNodeId("s00001");
        subRow.setName("白名单");
        subRow.setKeywords("[\"白名单\"]");
        nodeMapper.insert(subRow);
        Long subRowId = subRow.getId();

        ModuleHierarchyNode fnRow = new ModuleHierarchyNode();
        fnRow.setTaskId(taskId);
        fnRow.setSystemId(systemId);
        fnRow.setLevel("FUNCTION");
        fnRow.setParentId(subRowId);
        fnRow.setNodeId("f00001");
        fnRow.setName("白名单查询");
        fnRow.setKeywords(null);
        fnRow.setClassPaths("[\"com.demo.WhiteListController\"]");
        nodeMapper.insert(fnRow);

        // 5. 调 generateDraftDocument（Mock 模式：AI 返回 {} → PENDING_REVIEW 占位）
        aiSummaryService.generateDraftDocument(taskId, "test");

        // 6. 断言：KnowledgeDraft 落库
        List<KnowledgeDraft> drafts = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>().eq(KnowledgeDraft::getWorkspaceId,
                        draftMapper.selectList(null).get(0).getWorkspaceId())
        );
        Assertions.assertFalse(drafts.isEmpty(), "应至少写出一条 KnowledgeDraft");
        KnowledgeDraft draft = drafts.get(0);
        Assertions.assertEquals("用户管理", draft.getModuleName());
        Assertions.assertEquals("AI_GENERATED", draft.getStatus(), "Mock 模式 AI 也会返回有内容响应");
        Assertions.assertNotNull(draft.getContentUri());

        // 7. 断言：source references 写入
        List<DraftSourceReference> refs = refMapper.selectList(
                new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draft.getId())
        );
        Assertions.assertFalse(refs.isEmpty(), "应写出 source reference");
        Assertions.assertEquals(1, refs.size());
        Assertions.assertEquals("src/main/java/com/demo/WhiteListController.java", refs.get(0).getFilePath());
        Assertions.assertEquals(1, refs.get(0).getStartLine().intValue());
        Assertions.assertEquals(0, refs.get(0).getEndLine().intValue(), "整模块文档 end_line=0 表示整文件");
    }

    /**
     * DTO 为空时应明确失败，不再走 legacy 兜底。
     */
    @Test
    public void testGenerateModuleDocFailsWhenNoHierarchy() {
        Long taskId = 8102L;
        Long systemId = 1L;
        Long repositoryId = 1L;

        DecompileTask task = new DecompileTask();
        task.setId(taskId);
        task.setSystemId(systemId);
        task.setRepositoryId(repositoryId);
        task.setStatus("DRAFT");
        task.setType("INITIAL");
        task.setProgress(0);
        task.setModelName("");
        taskMapper.insert(task);

        Assertions.assertThrows(com.company.codeinsight.common.exception.BusinessException.class, () ->
                aiSummaryService.generateDraftDocument(taskId, "test"));
    }

    /**
     * 边界：DTO 有 Function 但 BFS 无可达源码 → 跳过该模块
     */
    @Test
    public void testGenerateModuleDocEmptySourceSkipped() {
        Long taskId = 8103L;
        Long systemId = 1L;
        Long repositoryId = 1L;

        DecompileTask task = new DecompileTask();
        task.setId(taskId);
        task.setSystemId(systemId);
        task.setRepositoryId(repositoryId);
        task.setStatus("DRAFT");
        task.setType("INITIAL");
        task.setProgress(0);
        task.setModelName("");
        taskMapper.insert(task);

        // 插入 ModuleHierarchyNode：function.classPaths 指向不存在的类
        ModuleHierarchyNode modRow = new ModuleHierarchyNode();
        modRow.setTaskId(taskId);
        modRow.setSystemId(systemId);
        modRow.setLevel("MODULE");
        modRow.setParentId(null);
        modRow.setNodeId("m00002");
        modRow.setName("无源码模块");
        nodeMapper.insert(modRow);
        Long moduleRowId = modRow.getId();

        ModuleHierarchyNode subRow = new ModuleHierarchyNode();
        subRow.setTaskId(taskId);
        subRow.setSystemId(systemId);
        subRow.setLevel("SUB_MODULE");
        subRow.setParentId(moduleRowId);
        subRow.setNodeId("s00002");
        subRow.setName("无源码子模块");
        nodeMapper.insert(subRow);
        Long subRowId = subRow.getId();

        ModuleHierarchyNode fnRow = new ModuleHierarchyNode();
        fnRow.setTaskId(taskId);
        fnRow.setSystemId(systemId);
        fnRow.setLevel("FUNCTION");
        fnRow.setParentId(subRowId);
        fnRow.setNodeId("f00002");
        fnRow.setName("无源码功能");
        fnRow.setClassPaths("[\"com.demo.NonExistent\"]");
        nodeMapper.insert(fnRow);

        // 不写任何 MethodCall + 不建物理文件 → BFS 返回空 → 模块被跳过
        Assertions.assertDoesNotThrow(() ->
                aiSummaryService.generateDraftDocument(taskId, "test"));

        // 验证无 KnowledgeDraft 落库（filterByTaskId 通过关联 workspace 间接验证）
        long count = draftMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDraft>().eq(KnowledgeDraft::getModuleName, "无源码模块")
        );
        Assertions.assertEquals(0, count);
    }

    /**
     * upsert 幂等：相同任务跑两次 → workspace_id+file_path 命中 → 覆盖而非新增
     */
    @Test
    public void testGenerateModuleDocUpsertIdempotent() throws Exception {
        Long taskId = 8104L;
        Long systemId = 1L;
        Long repositoryId = 1L;

        DecompileTask task = new DecompileTask();
        task.setId(taskId);
        task.setSystemId(systemId);
        task.setRepositoryId(repositoryId);
        task.setStatus("DRAFT");
        task.setType("INITIAL");
        task.setProgress(0);
        task.setModelName("");
        taskMapper.insert(task);

        File projectDir = new File("temp_repos/task_" + taskId);
        File srcFile = new File(projectDir, "src/main/java/com/demo/IdempotentController.java");
        srcFile.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(srcFile)) {
            w.write("package com.demo;\npublic class IdempotentController {}\n");
        }

        MethodCall mc = new MethodCall();
        mc.setTaskId(taskId);
        mc.setFilePath("src/main/java/com/demo/IdempotentController.java");
        mc.setClassName("IdempotentController");
        mc.setCallerMethod("run");
        mc.setDependencyName("x:X");
        mc.setTargetMethod("go");
        mc.setCreatedDate(java.time.LocalDateTime.now());
        methodCallMapper.insert(mc);

        ModuleHierarchyNode modRow = new ModuleHierarchyNode();
        modRow.setTaskId(taskId);
        modRow.setSystemId(systemId);
        modRow.setLevel("MODULE");
        modRow.setNodeId("m00003");
        modRow.setName("幂等模块");
        nodeMapper.insert(modRow);
        Long moduleRowId = modRow.getId();

        ModuleHierarchyNode subRow = new ModuleHierarchyNode();
        subRow.setTaskId(taskId);
        subRow.setSystemId(systemId);
        subRow.setLevel("SUB_MODULE");
        subRow.setParentId(moduleRowId);
        subRow.setNodeId("s00003");
        subRow.setName("子模块");
        nodeMapper.insert(subRow);
        Long subRowId = subRow.getId();

        ModuleHierarchyNode fnRow = new ModuleHierarchyNode();
        fnRow.setTaskId(taskId);
        fnRow.setSystemId(systemId);
        fnRow.setLevel("FUNCTION");
        fnRow.setParentId(subRowId);
        fnRow.setNodeId("f00003");
        fnRow.setName("幂等功能");
        fnRow.setClassPaths("[\"com.demo.IdempotentController\"]");
        nodeMapper.insert(fnRow);

        // 跑两次
        aiSummaryService.generateDraftDocument(taskId, "test");
        long firstCount = draftMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDraft>().eq(KnowledgeDraft::getModuleName, "幂等模块")
        );
        aiSummaryService.generateDraftDocument(taskId, "test");
        long secondCount = draftMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDraft>().eq(KnowledgeDraft::getModuleName, "幂等模块")
        );

        Assertions.assertEquals(1, firstCount, "首次跑应有 1 条");
        Assertions.assertEquals(1, secondCount, "二次跑仍 1 条（upsert 覆盖）");
    }
}