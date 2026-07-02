package com.company.codeinsight.modules.entrypoint;

import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import com.company.codeinsight.modules.entrypoint.model.ExcludeTarget;
import com.company.codeinsight.modules.entrypoint.model.TypeIncludeRules;
import com.company.codeinsight.modules.entrypoint.service.EntrypointReviewService;
import com.company.codeinsight.modules.entrypoint.service.impl.EntrypointReviewServiceImpl;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务入口扫描快照：创建时仓库复制 + 覆写；运行时只读任务快照。
 */
public class EntryPointConfigSnapshotTest {

    private EntryPointConfig repoWithExcludeTarget() {
        EntryPointConfig repo = EntryPointConfig.defaults();
        repo.setExcludeTargets(new ArrayList<>(List.of(
                new ExcludeTarget("com.demo.LegacyController", null))));
        return repo;
    }

    @Test
    void buildTaskSnapshot_nullOverride_copiesRepoIncludingExcludeTargets() {
        EntryPointConfig repo = repoWithExcludeTarget();
        EntryPointConfig snapshot = EntryPointConfig.buildTaskSnapshot(repo, null);
        Assertions.assertEquals(1, snapshot.getEffectiveExcludeTargets().size());
        Assertions.assertEquals("com.demo.LegacyController",
                snapshot.getEffectiveExcludeTargets().get(0).getClassName());
    }

    @Test
    void buildTaskSnapshot_overrideReplaceExcludeTargets_notUnionMerge() {
        EntryPointConfig repo = repoWithExcludeTarget();
        EntryPointConfig override = EntryPointConfig.defaults();
        override.setExcludeTargets(new ArrayList<>(List.of(
                new ExcludeTarget("com.demo.TaskOnly", "run()"))));

        EntryPointConfig snapshot = EntryPointConfig.buildTaskSnapshot(repo, override);
        Assertions.assertEquals(1, snapshot.getEffectiveExcludeTargets().size());
        Assertions.assertEquals("com.demo.TaskOnly",
                snapshot.getEffectiveExcludeTargets().get(0).getClassName());
    }

    @Test
    void buildTaskSnapshot_overrideClearsExcludeTargets() {
        EntryPointConfig repo = repoWithExcludeTarget();
        EntryPointConfig override = EntryPointConfig.defaults();
        override.setExcludeTargets(new ArrayList<>());

        EntryPointConfig snapshot = EntryPointConfig.buildTaskSnapshot(repo, override);
        Assertions.assertTrue(snapshot.getEffectiveExcludeTargets().isEmpty());
    }

    @Test
    void buildTaskSnapshot_overrideReplacesIncludesByType() {
        EntryPointConfig repo = EntryPointConfig.defaults();
        EntryPointConfig override = EntryPointConfig.defaults();
        TypeIncludeRules other = new TypeIncludeRules();
        other.setIncludeClasspaths(new ArrayList<>(List.of("com.custom.*")));
        override.getIncludesByType().put(EntryPointConfig.TYPE_OTHER, other);

        EntryPointConfig snapshot = EntryPointConfig.buildTaskSnapshot(repo, override);
        Assertions.assertEquals(List.of("com.custom.*"),
                snapshot.rulesFor(EntryPointConfig.TYPE_OTHER).getEffectiveIncludeClasspaths());
    }

    @Test
    void resolveConfig_usesTaskSnapshotOnly_notLiveRepo() {
        EntryPointConfig repo = EntryPointConfig.defaults();
        repo.setExcludeTargets(new ArrayList<>(List.of(
                new ExcludeTarget("com.demo.RepoOnly", null))));

        EntryPointConfig taskSnapshot = EntryPointConfig.buildTaskSnapshot(repo, null);
        taskSnapshot.setExcludeTargets(new ArrayList<>());

        DecompileTask task = new DecompileTask();
        task.setId(99L);
        task.setRepositoryId(1L);
        task.setEntryScanConfig(com.company.codeinsight.modules.entrypoint.model.EntryPointConfigCodec.encode(taskSnapshot));

        EntrypointReviewService service = new EntrypointReviewServiceImpl();
        EntryPointConfig resolved = service.resolveConfig(task);
        Assertions.assertTrue(resolved.getEffectiveExcludeTargets().isEmpty());
    }

    @Test
    void resolveConfig_nullTaskConfig_fallsBackToDefaults() {
        DecompileTask task = new DecompileTask();
        task.setId(100L);
        task.setEntryScanConfig(null);

        EntrypointReviewService service = new EntrypointReviewServiceImpl();
        EntryPointConfig resolved = service.resolveConfig(task);
        Assertions.assertFalse(resolved.rulesFor(EntryPointConfig.TYPE_CONTROLLER).isEmpty());
    }
}
