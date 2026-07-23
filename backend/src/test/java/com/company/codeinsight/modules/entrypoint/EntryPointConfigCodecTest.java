package com.company.codeinsight.modules.entrypoint;

import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfigCodec;
import com.company.codeinsight.modules.entrypoint.model.TypeIncludeRules;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回归：encode 不得把 isEmpty/getEffective* 写入 JSON，否则 decode 失败并静默回退默认配置。
 */
@DisplayName("EntryPointConfigCodec 编解码")
public class EntryPointConfigCodecTest {

    @Test
    @DisplayName("round-trip 保留自定义 Controller/Job 规则，不丢成默认")
    void roundTrip_preservesCustomIncludes() {
        EntryPointConfig cfg = new EntryPointConfig();
        Map<String, TypeIncludeRules> map = new LinkedHashMap<>();

        TypeIncludeRules controller = new TypeIncludeRules();
        controller.setIncludeAnnotations(new ArrayList<>(List.of("aaa")));
        controller.setIncludeClasspaths(new ArrayList<>(List.of("bbb")));
        map.put(EntryPointConfig.TYPE_CONTROLLER, controller);

        TypeIncludeRules job = new TypeIncludeRules();
        job.setIncludeClasspaths(new ArrayList<>(List.of("com.codeinsight.demo.controller.**")));
        map.put(EntryPointConfig.TYPE_SCHEDULED_JOB, job);

        map.put(EntryPointConfig.TYPE_MQ_LISTENER, neverMatch());
        map.put(EntryPointConfig.TYPE_OTHER, new TypeIncludeRules());
        cfg.setIncludesByType(map);

        String json = EntryPointConfigCodec.encode(cfg);
        Assertions.assertNotNull(json);
        Assertions.assertFalse(json.contains("\"empty\""), "不得序列化 isEmpty→empty");
        Assertions.assertFalse(json.contains("effectiveInclude"), "不得序列化 getEffective*");

        EntryPointConfig decoded = EntryPointConfigCodec.decode(json);
        Assertions.assertEquals(List.of("aaa"),
                decoded.rulesFor(EntryPointConfig.TYPE_CONTROLLER).getEffectiveIncludeAnnotations());
        Assertions.assertEquals(List.of("bbb"),
                decoded.rulesFor(EntryPointConfig.TYPE_CONTROLLER).getEffectiveIncludeClasspaths());
        Assertions.assertEquals(List.of("com.codeinsight.demo.controller.**"),
                decoded.rulesFor(EntryPointConfig.TYPE_SCHEDULED_JOB).getEffectiveIncludeClasspaths());
    }

    @Test
    @DisplayName("兼容历史快照：含 empty/effective* 字段仍能 decode")
    void decode_legacySnapshotWithEmptyField_keepsUserRules() {
        // 修复前 encode 误写入的派生字段；库内已有试跑/任务快照需能读回
        String legacy = "{"
                + "\"includesByType\":{"
                + "\"CONTROLLER\":{\"empty\":false,\"includeAnnotations\":[\"aaa\"],"
                + "\"includeClasspaths\":[\"bbb\"],\"includeExtends\":[],"
                + "\"effectiveIncludeAnnotations\":[\"aaa\"],\"effectiveIncludeClasspaths\":[\"bbb\"],"
                + "\"effectiveIncludeExtends\":[]},"
                + "\"SCHEDULED_JOB\":{\"empty\":false,\"includeAnnotations\":[],"
                + "\"includeClasspaths\":[\"com.codeinsight.demo.controller.**\"],\"includeExtends\":[],"
                + "\"effectiveIncludeAnnotations\":[],"
                + "\"effectiveIncludeClasspaths\":[\"com.codeinsight.demo.controller.**\"],"
                + "\"effectiveIncludeExtends\":[]},"
                + "\"MQ_LISTENER\":{\"empty\":true,\"includeAnnotations\":[],\"includeClasspaths\":[],\"includeExtends\":[]},"
                + "\"OTHER\":{\"empty\":true,\"includeAnnotations\":[],\"includeClasspaths\":[],\"includeExtends\":[]}"
                + "},"
                + "\"excludeClasspaths\":[\"**/*Test\",\"**/*Tests\",\"**/*TestCase\"],"
                + "\"excludePackages\":[],\"excludeAnnotations\":[],\"excludeTargets\":[]"
                + "}";

        EntryPointConfig decoded = EntryPointConfigCodec.decode(legacy);
        Assertions.assertEquals(List.of("aaa"),
                decoded.rulesFor(EntryPointConfig.TYPE_CONTROLLER).getEffectiveIncludeAnnotations());
        Assertions.assertEquals(List.of("com.codeinsight.demo.controller.**"),
                decoded.rulesFor(EntryPointConfig.TYPE_SCHEDULED_JOB).getEffectiveIncludeClasspaths());
    }

    private static TypeIncludeRules neverMatch() {
        TypeIncludeRules never = new TypeIncludeRules();
        never.setIncludeClasspaths(new ArrayList<>(List.of("__never.matches.**")));
        return never;
    }
}
