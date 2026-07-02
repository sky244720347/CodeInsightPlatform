package com.company.codeinsight.modules.entrypoint.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入口扫描配置（仓库级 / 任务级 JSON）。
 * <ul>
 *   <li>{@link #includesByType}：四类入口独立 include，按 Controller → Job → MQ → Other 优先级匹配</li>
 *   <li>全局 exclude 规则 + {@link #excludeTargets} 指定排除（类 / 类+方法）</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntryPointConfig implements Serializable {

    private static final long serialVersionUID = 2L;

    public static final String TYPE_CONTROLLER = "CONTROLLER";
    public static final String TYPE_SCHEDULED_JOB = "SCHEDULED_JOB";
    public static final String TYPE_MQ_LISTENER = "MQ_LISTENER";
    public static final String TYPE_OTHER = "OTHER";

    public static final List<String> TYPE_MATCH_ORDER = List.of(
            TYPE_CONTROLLER, TYPE_SCHEDULED_JOB, TYPE_MQ_LISTENER, TYPE_OTHER);

    /** 四类入口独立 include */
    private Map<String, TypeIncludeRules> includesByType = new LinkedHashMap<>();

    /** 全局排除 - 类路径 Ant */
    private List<String> excludeClasspaths = new ArrayList<>(Arrays.asList(
            "**/*Test", "**/*Tests", "**/*TestCase"));

    /** 全局排除 - 包前缀 */
    private List<String> excludePackages = new ArrayList<>();

    /** 全局排除 - 注解 */
    private List<String> excludeAnnotations = new ArrayList<>();

    /** 指定排除列表（类 / 类+方法）；任务快照内自洽，复核时可追加 */
    private List<ExcludeTarget> excludeTargets = new ArrayList<>();

    public static EntryPointConfig defaults() {
        EntryPointConfig cfg = new EntryPointConfig();
        cfg.setIncludesByType(defaultIncludesByType());
        return cfg;
    }

    public static Map<String, TypeIncludeRules> defaultIncludesByType() {
        Map<String, TypeIncludeRules> map = new LinkedHashMap<>();

        TypeIncludeRules controller = new TypeIncludeRules();
        controller.setIncludeAnnotations(new ArrayList<>(Arrays.asList(
                "RestController", "Controller", "RequestMapping")));
        map.put(TYPE_CONTROLLER, controller);

        TypeIncludeRules job = new TypeIncludeRules();
        job.setIncludeAnnotations(new ArrayList<>(Arrays.asList(
                "Scheduled", "EnableScheduling")));
        job.setIncludeExtends(new ArrayList<>(List.of(
                "org.springframework.boot.CommandLineRunner",
                "org.springframework.boot.ApplicationRunner")));
        map.put(TYPE_SCHEDULED_JOB, job);

        TypeIncludeRules mq = new TypeIncludeRules();
        mq.setIncludeAnnotations(new ArrayList<>(Arrays.asList(
                "RabbitListener", "KafkaListener", "JmsListener", "RocketMQMessageListener")));
        map.put(TYPE_MQ_LISTENER, mq);

        map.put(TYPE_OTHER, new TypeIncludeRules());
        return map;
    }

    /** 解码后补齐缺失类型与空 OTHER */
    public static EntryPointConfig normalize(EntryPointConfig cfg) {
        if (cfg == null) {
            return defaults();
        }
        Map<String, TypeIncludeRules> defaults = defaultIncludesByType();
        Map<String, TypeIncludeRules> merged = new LinkedHashMap<>();
        Map<String, TypeIncludeRules> src = cfg.getIncludesByType() == null ? Map.of() : cfg.getIncludesByType();
        for (String type : TYPE_MATCH_ORDER) {
            TypeIncludeRules fromCfg = src.get(type);
            if (fromCfg == null || fromCfg.isEmpty()) {
                if (TYPE_OTHER.equals(type)) {
                    merged.put(type, new TypeIncludeRules());
                } else {
                    merged.put(type, copyRules(defaults.get(type)));
                }
            } else {
                merged.put(type, fromCfg);
            }
        }
        cfg.setIncludesByType(merged);
        if (cfg.getExcludeClasspaths() == null || cfg.getExcludeClasspaths().isEmpty()) {
            cfg.setExcludeClasspaths(new ArrayList<>(Arrays.asList(
                    "**/*Test", "**/*Tests", "**/*TestCase")));
        }
        if (cfg.getExcludePackages() == null) {
            cfg.setExcludePackages(new ArrayList<>());
        }
        if (cfg.getExcludeAnnotations() == null) {
            cfg.setExcludeAnnotations(new ArrayList<>());
        }
        if (cfg.getExcludeTargets() == null) {
            cfg.setExcludeTargets(new ArrayList<>());
        }
        return cfg;
    }

    /**
     * 任务创建快照：先全量复制仓库配置，再用 override 中非 null 的顶层字段整段覆写。
     * override 为 null 时等价于完整复制仓库（含 excludeTargets）。
     */
    public static EntryPointConfig buildTaskSnapshot(EntryPointConfig repoCfg, EntryPointConfig override) {
        EntryPointConfig snapshot = deepCopy(normalize(repoCfg));
        if (override == null) {
            return snapshot;
        }
        if (override.getIncludesByType() != null) {
            snapshot.setIncludesByType(copyIncludesMap(override.getIncludesByType()));
        }
        if (override.getExcludeClasspaths() != null) {
            snapshot.setExcludeClasspaths(new ArrayList<>(override.getExcludeClasspaths()));
        }
        if (override.getExcludePackages() != null) {
            snapshot.setExcludePackages(new ArrayList<>(override.getExcludePackages()));
        }
        if (override.getExcludeAnnotations() != null) {
            snapshot.setExcludeAnnotations(new ArrayList<>(override.getExcludeAnnotations()));
        }
        if (override.getExcludeTargets() != null) {
            snapshot.setExcludeTargets(new ArrayList<>(override.getExcludeTargets()));
        }
        return normalize(snapshot);
    }

    /** @deprecated 仅保留兼容；请使用 {@link #buildTaskSnapshot} */
    @Deprecated
    public static EntryPointConfig mergeSnapshot(EntryPointConfig repoCfg, EntryPointConfig taskCfg) {
        return buildTaskSnapshot(repoCfg, taskCfg);
    }

    public TypeIncludeRules rulesFor(String type) {
        if (includesByType == null) {
            return new TypeIncludeRules();
        }
        TypeIncludeRules r = includesByType.get(type);
        return r != null ? r : new TypeIncludeRules();
    }

    public List<String> getEffectiveExcludeClasspaths() {
        return excludeClasspaths == null ? List.of() : excludeClasspaths;
    }

    public List<String> getEffectiveExcludePackages() {
        return excludePackages == null ? List.of() : excludePackages;
    }

    public List<String> getEffectiveExcludeAnnotations() {
        return excludeAnnotations == null ? List.of() : excludeAnnotations;
    }

    public List<ExcludeTarget> getEffectiveExcludeTargets() {
        return excludeTargets == null ? List.of() : excludeTargets;
    }

    public void appendExcludeTargets(List<ExcludeTarget> additional) {
        if (additional == null || additional.isEmpty()) {
            return;
        }
        if (excludeTargets == null) {
            excludeTargets = new ArrayList<>();
        }
        excludeTargets = mergeExcludeTargets(excludeTargets, additional);
    }

    private static List<ExcludeTarget> mergeExcludeTargets(List<ExcludeTarget> a, List<ExcludeTarget> b) {
        Set<String> seen = new LinkedHashSet<>();
        List<ExcludeTarget> out = new ArrayList<>();
        for (ExcludeTarget t : a == null ? List.<ExcludeTarget>of() : a) {
            if (t == null || t.getClassName() == null || t.getClassName().isBlank()) continue;
            String key = targetKey(t);
            if (seen.add(key)) out.add(t);
        }
        for (ExcludeTarget t : b == null ? List.<ExcludeTarget>of() : b) {
            if (t == null || t.getClassName() == null || t.getClassName().isBlank()) continue;
            String key = targetKey(t);
            if (seen.add(key)) out.add(t);
        }
        return out;
    }

    public static String targetKey(ExcludeTarget t) {
        String sig = t.getMethodSignature() == null ? "" : t.getMethodSignature().trim();
        return t.getClassName().trim() + "#" + sig;
    }

    private static EntryPointConfig deepCopy(EntryPointConfig src) {
        EntryPointConfig copy = new EntryPointConfig();
        copy.setIncludesByType(copyIncludesMap(src.getIncludesByType()));
        copy.setExcludeClasspaths(new ArrayList<>(src.getEffectiveExcludeClasspaths()));
        copy.setExcludePackages(new ArrayList<>(src.getEffectiveExcludePackages()));
        copy.setExcludeAnnotations(new ArrayList<>(src.getEffectiveExcludeAnnotations()));
        copy.setExcludeTargets(new ArrayList<>(src.getEffectiveExcludeTargets()));
        return copy;
    }

    private static Map<String, TypeIncludeRules> copyIncludesMap(Map<String, TypeIncludeRules> src) {
        Map<String, TypeIncludeRules> out = new LinkedHashMap<>();
        Map<String, TypeIncludeRules> from = src == null ? Map.of() : src;
        for (String type : TYPE_MATCH_ORDER) {
            TypeIncludeRules rules = from.get(type);
            out.put(type, rules != null ? copyRules(rules) : new TypeIncludeRules());
        }
        return out;
    }

    private static TypeIncludeRules copyRules(TypeIncludeRules src) {
        if (src == null) return new TypeIncludeRules();
        TypeIncludeRules copy = new TypeIncludeRules();
        copy.setIncludeAnnotations(new ArrayList<>(src.getEffectiveIncludeAnnotations()));
        copy.setIncludeClasspaths(new ArrayList<>(src.getEffectiveIncludeClasspaths()));
        copy.setIncludeExtends(new ArrayList<>(src.getEffectiveIncludeExtends()));
        return copy;
    }
}
