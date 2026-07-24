package com.company.codeinsight.modules.knowledge;

import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.hierarchy.model.FunctionDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.model.SubModuleDto;
import com.company.codeinsight.modules.knowledge.service.KnowledgeIndexService;
import com.company.codeinsight.modules.knowledge.service.impl.KnowledgeIndexServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 三级索引生成服务单元测试（不依赖 Spring / DB）
 */
public class KnowledgeIndexServiceTest {

    private KnowledgeIndexService knowledgeIndexService;

    @BeforeEach
    void setUp() {
        knowledgeIndexService = new KnowledgeIndexServiceImpl();
    }

    @Test
    public void testFlattenMatchesZipFileName() {
        // "模块 / 子模块 / 功能" → 空白与 / 均换成 _，与 createVersion 一致
        Assertions.assertEquals(
                "订单管理___订单报价___订单报价查询.md",
                KnowledgeIndexServiceImpl.flattenKnowledgeDocFileName("订单管理 / 订单报价 / 订单报价查询"));
        Assertions.assertEquals(
                "modules/订单管理___订单报价___订单报价查询.md",
                KnowledgeIndexServiceImpl.toZipRelativeDocPath("订单管理 / 订单报价 / 订单报价查询"));
    }

    @Test
    public void testGenerateThreeLevelIndex() throws Exception {
        Path tempDir = Files.createTempDirectory("idx-test");
        tempDir.toFile().deleteOnExit();
        Path docsPath = tempDir.resolve("docs/code-insight");
        Files.createDirectories(docsPath);

        ModuleHierarchy hierarchy = buildSampleHierarchy();
        List<KnowledgeDraft> drafts = new ArrayList<>();
        drafts.add(buildDraft(9901L, "用户管理 / 白名单 / 白名单注册",
                "task_9901/用户管理/白名单/白名单注册.md"));
        drafts.add(buildDraft(9901L, "用户管理 / 白名单 / 白名单重置",
                "task_9901/用户管理/白名单/白名单重置.md"));
        drafts.add(buildDraft(9901L, "订单管理", "task_9901/订单管理.md"));

        Path indexPath = knowledgeIndexService.generateModuleIndex(docsPath, hierarchy, drafts);
        Assertions.assertTrue(Files.exists(indexPath));

        String content = Files.readString(indexPath);
        Assertions.assertTrue(content.contains("# 模块知识归纳索引"));
        Assertions.assertTrue(content.contains("| 模块 | 子模块 | 功能 | 入口类 | md 链接 |"));
        Assertions.assertTrue(content.contains("用户管理"));
        Assertions.assertTrue(content.contains("白名单"));
        Assertions.assertTrue(content.contains("白名单注册"));
        Assertions.assertTrue(content.contains("白名单重置"));
        Assertions.assertTrue(content.contains("订单管理"));
        Assertions.assertTrue(content.contains("[用户管理](modules/用户管理___白名单___白名单注册.md)"));
        Assertions.assertTrue(content.contains("[知识文档索引](meta/document-index.md)"));
        Assertions.assertTrue(content.contains("com.demo.UserController"));
        Assertions.assertTrue(content.contains("[架构概览](architecture-overview.md)"));
    }

    @Test
    public void testGenerateDocumentIndex() throws Exception {
        Path tempDir = Files.createTempDirectory("doc-idx-test");
        tempDir.toFile().deleteOnExit();
        Path docsPath = tempDir.resolve("docs/code-insight");
        Files.createDirectories(docsPath);

        ModuleHierarchy hierarchy = buildSampleHierarchy();
        List<KnowledgeDraft> drafts = new ArrayList<>();
        drafts.add(buildDraft(9901L, "用户管理 / 白名单 / 白名单注册",
                "task_9901/用户管理/白名单/白名单注册.md"));

        Path indexPath = knowledgeIndexService.generateDocumentIndex(docsPath, hierarchy, drafts);
        Assertions.assertTrue(Files.exists(indexPath));
        Assertions.assertEquals(docsPath.resolve("meta/document-index.md"), indexPath);

        String content = Files.readString(indexPath);
        Assertions.assertTrue(content.contains("# 知识文档路径索引"));
        Assertions.assertTrue(content.contains("| 模块 | 子模块 | 功能 | 文档路径 |"));
        Assertions.assertTrue(content.contains("`modules/用户管理___白名单___白名单注册.md`"));
        Assertions.assertTrue(content.contains("`modules/用户管理___白名单___白名单重置.md`"));
        Assertions.assertTrue(content.contains("`modules/订单管理.md`"));
        // 不得出现斜线嵌套路径
        Assertions.assertFalse(content.contains("`modules/用户管理/白名单/"));
    }

    @Test
    public void testFallbackWhenHierarchyEmpty() throws Exception {
        Path tempDir = Files.createTempDirectory("idx-fallback-test");
        tempDir.toFile().deleteOnExit();
        Path docsPath = tempDir.resolve("docs/code-insight");
        Files.createDirectories(docsPath);

        ModuleHierarchy hierarchy = new ModuleHierarchy();

        List<KnowledgeDraft> drafts = new ArrayList<>();
        drafts.add(buildDraft(9902L, "模块A", "task_9902/A.md"));
        drafts.add(buildDraft(9902L, "模块B", "task_9902/B.md"));

        Path indexPath = knowledgeIndexService.generateModuleIndex(docsPath, hierarchy, drafts);
        String content = Files.readString(indexPath);

        Assertions.assertTrue(content.contains("| 模块 | md 链接 |"));
        Assertions.assertTrue(content.contains("[模块A](modules/模块A.md)"));
        Assertions.assertTrue(content.contains("[模块B](modules/模块B.md)"));

        Path docIndex = knowledgeIndexService.generateDocumentIndex(docsPath, hierarchy, drafts);
        String docContent = Files.readString(docIndex);
        Assertions.assertTrue(docContent.contains("`modules/模块A.md`"));
        Assertions.assertTrue(docContent.contains("`modules/模块B.md`"));
    }

    @Test
    public void testEscapeMdForSpecialCharacters() throws Exception {
        Path tempDir = Files.createTempDirectory("idx-escape-test");
        tempDir.toFile().deleteOnExit();
        Path docsPath = tempDir.resolve("docs/code-insight");
        Files.createDirectories(docsPath);

        ModuleHierarchy hierarchy = new ModuleHierarchy();
        ModuleDto m = new ModuleDto();
        m.setId("m00001");
        m.setModuleName("用户|管理");
        hierarchy.getModules().put(m.getId(), m);

        List<KnowledgeDraft> drafts = new ArrayList<>();
        drafts.add(buildDraft(9903L, "用户|管理", "task_9903/UserManagement.md"));

        Path indexPath = knowledgeIndexService.generateModuleIndex(docsPath, hierarchy, drafts);
        String content = Files.readString(indexPath);

        Assertions.assertTrue(content.contains("用户\\|管理"));
        Assertions.assertFalse(content.contains("用户|管理\n| ---"));
    }

    private ModuleHierarchy buildSampleHierarchy() {
        ModuleHierarchy hierarchy = new ModuleHierarchy();
        hierarchy.setTaskId(9901L);
        hierarchy.setSystemId(1L);

        ModuleDto userMod = new ModuleDto();
        userMod.setId("m00001");
        userMod.setModuleName("用户管理");
        hierarchy.getModules().put(userMod.getId(), userMod);

        SubModuleDto whiteListSub = new SubModuleDto();
        whiteListSub.setId("s00001");
        whiteListSub.setSubModuleName("白名单");
        userMod.getSubModules().put(whiteListSub.getId(), whiteListSub);

        FunctionDto registerFn = new FunctionDto();
        registerFn.setId("f00001");
        registerFn.setFunctionName("白名单注册");
        registerFn.setClassPaths(new LinkedHashSet<>(List.of("com.demo.UserController")));
        whiteListSub.getFunctions().put(registerFn.getId(), registerFn);

        FunctionDto resetFn = new FunctionDto();
        resetFn.setId("f00002");
        resetFn.setFunctionName("白名单重置");
        resetFn.setClassPaths(new LinkedHashSet<>(List.of("com.demo.UserController", "com.demo.UserService")));
        whiteListSub.getFunctions().put(resetFn.getId(), resetFn);

        ModuleDto orderMod = new ModuleDto();
        orderMod.setId("m00002");
        orderMod.setModuleName("订单管理");
        hierarchy.getModules().put(orderMod.getId(), orderMod);

        return hierarchy;
    }

    private KnowledgeDraft buildDraft(Long workspaceId, String moduleName, String filePath) {
        KnowledgeDraft draft = new KnowledgeDraft();
        draft.setWorkspaceId(workspaceId);
        draft.setModuleName(moduleName);
        draft.setFilePath(filePath);
        draft.setStatus("AI_GENERATED");
        return draft;
    }
}
