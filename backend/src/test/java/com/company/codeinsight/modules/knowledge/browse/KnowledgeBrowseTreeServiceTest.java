package com.company.codeinsight.modules.knowledge.browse;

import com.company.codeinsight.modules.hierarchy.model.FunctionDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.SubModuleDto;
import com.company.codeinsight.modules.knowledge.browse.ReleaseKnowledgeBrowseHelper.ReleaseDocumentIndex;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KnowledgeBrowseTreeServiceTest {

    @Test
    void flattenBreadcrumb_matchesNasPushNaming() {
        Assertions.assertEquals(
                "成员管理___账号管理___账号注册",
                KnowledgeBrowseTreeService.flattenBreadcrumb("成员管理 / 账号管理 / 账号注册"));
    }

    @Test
    void resolveFunctionDoc_prefersNestedLayout() {
        ReleaseDocumentIndex index = new ReleaseDocumentIndex();
        String nested = "modules/成员管理/账号管理/账号注册.md";
        index.byRelativePath.put(nested, nested);

        ModuleDto module = module("成员管理");
        SubModuleDto sub = subModule("账号管理");
        FunctionDto fn = function("账号注册");

        Assertions.assertEquals(nested,
                KnowledgeBrowseTreeService.resolvePublishedFunctionDocPath(module, sub, fn, index));
    }

    @Test
    void resolveFunctionDoc_fallsBackToFlatNasLayout() {
        ReleaseDocumentIndex index = new ReleaseDocumentIndex();
        String flat = "modules/成员管理___账号管理___账号注册.md";
        index.byRelativePath.put(flat, flat);
        index.byBasename.put("成员管理___账号管理___账号注册", flat);

        ModuleDto module = module("成员管理");
        SubModuleDto sub = subModule("账号管理");
        FunctionDto fn = function("账号注册");

        Assertions.assertEquals(flat,
                KnowledgeBrowseTreeService.resolvePublishedFunctionDocPath(module, sub, fn, index));
    }

    @Test
    void resolveModuleDoc_fallsBackToFlatStem() {
        ReleaseDocumentIndex index = new ReleaseDocumentIndex();
        String flat = "modules/用户管理.md";
        index.byRelativePath.put(flat, flat);
        index.byBasename.put("用户管理", flat);

        Assertions.assertEquals(flat,
                KnowledgeBrowseTreeService.resolvePublishedModuleDocPath(module("用户管理"), index));
    }

    private static ModuleDto module(String name) {
        ModuleDto m = new ModuleDto();
        m.setModuleName(name);
        return m;
    }

    private static SubModuleDto subModule(String name) {
        SubModuleDto sm = new SubModuleDto();
        sm.setSubModuleName(name);
        return sm;
    }

    private static FunctionDto function(String name) {
        FunctionDto fn = new FunctionDto();
        fn.setFunctionName(name);
        return fn;
    }
}
