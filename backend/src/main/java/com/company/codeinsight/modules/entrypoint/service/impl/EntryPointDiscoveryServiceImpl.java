package com.company.codeinsight.modules.entrypoint.service.impl;

import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.service.MethodCallService;
import com.company.codeinsight.modules.entrypoint.model.DiscoveredEntrypoint;
import com.company.codeinsight.modules.entrypoint.model.DiscoveredMethod;
import com.company.codeinsight.modules.entrypoint.model.EntryPoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import com.company.codeinsight.modules.entrypoint.model.ExcludeTarget;
import com.company.codeinsight.modules.entrypoint.model.TypeIncludeRules;
import com.company.codeinsight.modules.entrypoint.service.EntryPointDiscoveryService;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入口识别服务实现
 * - 默认行为（config 为 null 或三个 include 都空）：沿用 Controller/JOB/MQ/main 兜底
 * - 配置驱动：按 EntryPointConfig 的 6 类规则"或"逻辑匹配 + 排除传染
 */
@Slf4j
@Service
public class EntryPointDiscoveryServiceImpl implements EntryPointDiscoveryService {

    @Autowired
    private JavaParserService javaParserService;

    @Autowired
    private MethodCallService methodCallService;

    /** Ant 路径匹配器（无状态，可复用） */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public List<EntryPoint> discoverEntries(Long taskId, File projectDir) {
        return discoverEntries(taskId, projectDir, null);
    }

    @Override
    public List<EntryPoint> discoverEntries(Long taskId, File projectDir, EntryPointConfig config) {
        return discoverEntriesInternal(taskId, projectDir, config);
    }

    @Override
    public List<DiscoveredEntrypoint> discoverEntriesWithMethods(Long taskId, File projectDir, EntryPointConfig config) {
        EntryPointConfig effective = normalizeConfig(config);
        List<EntryPoint> baseEntries = discoverEntriesInternal(taskId, projectDir, effective);
        if (baseEntries == null || baseEntries.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<DiscoveredEntrypoint> result = new java.util.ArrayList<>(baseEntries.size());
        for (EntryPoint ep : baseEntries) {
            List<DiscoveredMethod> methods = filterMethodsByExcludeTargets(
                    extractMethodsForEntry(projectDir, ep), ep.getClassName(), effective);
            if (methods.isEmpty()) {
                continue;
            }
            DiscoveredEntrypoint dep = new DiscoveredEntrypoint();
            dep.setBase(ep);
            dep.setMethods(methods);
            result.add(dep);
        }
        log.info("EntryPointDiscoveryService.discoverEntriesWithMethods done. taskId={} entries={}", taskId, result.size());
        return result;
    }

    @Override
    public List<DiscoveredEntrypoint> discoverEntriesInFiles(Long taskId, File projectDir, EntryPointConfig config,
                                                             Set<String> relativePaths) {
        if (relativePaths == null || relativePaths.isEmpty()) {
            log.info("discoverEntriesInFiles: relativePaths 为空，taskId={} 不需要识别", taskId);
            return Collections.emptyList();
        }
        if (projectDir == null || !projectDir.exists() || !projectDir.isDirectory()) {
            log.warn("discoverEntriesInFiles: projectDir 无效 taskId={}", taskId);
            return Collections.emptyList();
        }
        EntryPointConfig cfg = normalizeConfig(config);

        // 1) 加载本任务的调用链（基线继承后的版本），构建 className -> file_path 索引
        //    用于入口识别时定位类路径（特别是多模块项目）
        List<MethodCall> calls = methodCallService.listByTaskId(taskId);
        Map<String, String> shortNameToFilePath = new HashMap<>();
        for (MethodCall mc : calls) {
            if (StringUtils.hasText(mc.getClassName()) && StringUtils.hasText(mc.getFilePath())) {
                shortNameToFilePath.putIfAbsent(mc.getClassName(), mc.getFilePath());
            }
        }

        // 2) 对 relativePaths 内的每个 .java 文件做单文件识别
        Map<String, EntryPoint> entries = new LinkedHashMap<>();
        int parsedFiles = 0;
        int parseFailures = 0;
        for (String relativePath : relativePaths) {
            if (!StringUtils.hasText(relativePath) || !relativePath.endsWith(".java")) {
                continue;
            }
            File file = new File(projectDir, relativePath.replace('/', File.separatorChar));
            if (!file.exists() || !file.isFile()) {
                log.warn("discoverEntriesInFiles: 变更文件不存在 taskId={} path={}", taskId, relativePath);
                continue;
            }
            ParsedClassInfo info;
            try {
                info = javaParserService.parseFile(file);
                parsedFiles++;
            } catch (Exception e) {
                parseFailures++;
                log.warn("discoverEntriesInFiles: parseFile 失败 taskId={} path={} err={}",
                        taskId, relativePath, e.getMessage());
                continue;
            }
            if (info == null || !StringUtils.hasText(info.getClassName())) {
                continue;
            }
            String fq = fqName(info);

            if (isExcluded(fq, info, cfg)) continue;
            if (isClassExcludedByTarget(fq, cfg)) continue;

            String matchedType = matchEntryType(fq, info, cfg);
            if (matchedType == null) continue;

            String annotation = extractTriggerAnnotation(info.getAnnotations(), matchedType);
            if (annotation == null) {
                annotation = firstHitAnnotation(info, cfg.rulesFor(matchedType));
            }
            // 用 relativePath 强制覆盖（确保 filePath 是相对路径格式，不是绝对路径）
            EntryPoint ep = new EntryPoint();
            ep.setClassName(fq);
            ep.setFilePath(relativePath);
            ep.setEntryType(matchedType);
            ep.setAnnotation(annotation);
            // remark 字段全量识别会调 buildRemark；这里简化：增量识别不生成 remark
            ep.setRemark(null);
            if (!entries.containsKey(fq)) {
                entries.put(fq, ep);
            }
        }

        // 3) 为每个识别出的入口抽取方法列表
        List<DiscoveredEntrypoint> result = new ArrayList<>(entries.size());
        for (EntryPoint ep : entries.values()) {
            List<DiscoveredMethod> methods = filterMethodsByExcludeTargets(
                    extractMethodsForEntry(projectDir, ep), ep.getClassName(), cfg);
            if (methods.isEmpty()) {
                continue;
            }
            DiscoveredEntrypoint dep = new DiscoveredEntrypoint();
            dep.setBase(ep);
            dep.setMethods(methods);
            result.add(dep);
        }

        log.info("EntryPointDiscoveryService.discoverEntriesInFiles done. taskId={} parsedFiles={} parseFailures={} entries={}",
                taskId, parsedFiles, parseFailures, result.size());
        return result;
    }

    @Override
    public String collectReachableSource(Long taskId, String entryClassName, File projectDir) {
        return collectReachableSource(taskId, entryClassName, projectDir, null);
    }

    @Override
    public String collectReachableSource(Long taskId, String entryClassName, File projectDir, EntryPointConfig config) {
        return collectReachableSourceInternal(taskId, entryClassName, projectDir, config);
    }

    @Override
    public String readEntrySource(File projectDir, EntryPoint entry, EntryPointConfig config) {
        if (projectDir == null || entry == null) {
            return "";
        }
        String fqcn = entry.getClassName();
        if (!StringUtils.hasText(fqcn)) return "";

        try {
            // 1. 定位源文件
            File sourceFile = resolveEntryFile(projectDir, entry);
            if (sourceFile == null || !sourceFile.exists()) {
                log.warn("readEntrySource: 找不到入口类 {} 的源文件 (filePath={})", fqcn, entry.getFilePath());
                return "";
            }

            // 2. 排除规则检查（二次保障，discoverEntries 已过滤，但可能传了不同的 config）
            if (config != null) {
                try {
                    ParsedClassInfo info = javaParserService.parseFile(sourceFile);
                    if (info != null && isExcluded(fqcn, info, config)) {
                        log.debug("入口类 {} 命中排除规则，跳过", fqcn);
                        return "";
                    }
                } catch (Exception e) {
                    log.warn("解析入口类 {} 失败，仍尝试读取源文件", fqcn, e);
                }
            }

            return Files.readString(sourceFile.toPath());
        } catch (Exception e) {
            log.warn("readEntrySource 读取失败: {}", fqcn, e);
            return "";
        }
    }

    /**
     * 根据 EntryPoint 信息在 projectDir 下定位源文件
     */
    private File resolveEntryFile(File projectDir, EntryPoint entry) {
        // 优先使用 entry.filePath（来自 discoverEntries 的落表结果）
        String filePath = entry.getFilePath();
        if (StringUtils.hasText(filePath)) {
            File f = new File(projectDir, filePath.replace('/', File.separatorChar));
            if (f.exists()) {
                return f;
            }
        }
        return findJavaFileByFqcn(projectDir, entry.getClassName());
    }

    // ============================ core impl ============================

    private List<EntryPoint> discoverEntriesInternal(Long taskId, File projectDir, EntryPointConfig config) {
        if (taskId == null || projectDir == null || !projectDir.exists()) {
            return Collections.emptyList();
        }
        EntryPointConfig cfg = normalizeConfig(config);

        // 1. 解析全项目
        List<ParsedClassInfo> parsed;
        try {
            parsed = javaParserService.parseDirectory(projectDir);
        } catch (Exception e) {
            log.error("parseDirectory failed for task {}", taskId, e);
            return Collections.emptyList();
        }

        // 2. 加载调用链，构建 className -> file_path 索引（支持多模块路径）
        List<MethodCall> calls = methodCallService.listByTaskId(taskId);
        Map<String, String> depNameToFilePath = new HashMap<>();
        Map<String, String> shortNameToFilePath = new HashMap<>();
        for (MethodCall mc : calls) {
            if (StringUtils.hasText(mc.getDependencyName()) && StringUtils.hasText(mc.getFilePath())) {
                depNameToFilePath.putIfAbsent(mc.getDependencyName(), mc.getFilePath());
            }
            if (StringUtils.hasText(mc.getClassName()) && StringUtils.hasText(mc.getFilePath())) {
                shortNameToFilePath.putIfAbsent(mc.getClassName(), mc.getFilePath());
            }
        }

        // 3. 按四类 include 规则 + 优先级识别
        Map<String, EntryPoint> entries = new LinkedHashMap<>();
        for (ParsedClassInfo info : parsed) {
            if (info == null || !StringUtils.hasText(info.getClassName())) {
                continue;
            }
            String fq = fqName(info);

            if (isExcluded(fq, info, cfg)) {
                continue;
            }
            if (isClassExcludedByTarget(fq, cfg)) {
                continue;
            }

            String matchedType = matchEntryType(fq, info, cfg);
            if (matchedType == null) {
                continue;
            }
            String annotation = extractTriggerAnnotation(info.getAnnotations(), matchedType);
            if (annotation == null) {
                annotation = firstHitAnnotation(info, cfg.rulesFor(matchedType));
            }
            addEntry(entries, fq, info, matchedType, annotation, shortNameToFilePath);
        }

        log.info("Entry point discovery done. taskId={}, entriesFound={}", taskId, entries.size());
        return new ArrayList<>(entries.values());
    }

    private EntryPointConfig normalizeConfig(EntryPointConfig config) {
        return EntryPointConfig.normalize(config == null ? EntryPointConfig.defaults() : config);
    }

    /** Controller → Job → MQ → Other，首个命中类型生效 */
    private String matchEntryType(String fq, ParsedClassInfo info, EntryPointConfig cfg) {
        for (String type : EntryPointConfig.TYPE_MATCH_ORDER) {
            TypeIncludeRules rules = cfg.rulesFor(type);
            if (rules.isEmpty()) {
                continue;
            }
            if (matchesTypeRules(fq, info, rules)) {
                return type;
            }
        }
        return null;
    }

    private boolean matchesTypeRules(String fq, ParsedClassInfo info, TypeIncludeRules rules) {
        return matchesIncludeAnnotation(info, rules)
                || matchesIncludeClasspath(fq, rules)
                || matchesIncludeExtends(info, rules);
    }

    private boolean isClassExcludedByTarget(String fq, EntryPointConfig cfg) {
        for (ExcludeTarget t : cfg.getEffectiveExcludeTargets()) {
            if (t == null || !StringUtils.hasText(t.getClassName())) {
                continue;
            }
            if (t.isClassLevel() && fq.equals(t.getClassName().trim())) {
                return true;
            }
        }
        return false;
    }

    private List<DiscoveredMethod> filterMethodsByExcludeTargets(
            List<DiscoveredMethod> methods, String className, EntryPointConfig cfg) {
        if (methods == null || methods.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<DiscoveredMethod> out = new java.util.ArrayList<>(methods.size());
        for (DiscoveredMethod m : methods) {
            if (isMethodExcludedByTarget(className, m.getMethodSignature(), cfg)) {
                continue;
            }
            out.add(m);
        }
        return out;
    }

    private boolean isMethodExcludedByTarget(String className, String methodSignature, EntryPointConfig cfg) {
        if (!StringUtils.hasText(className)) {
            return false;
        }
        for (ExcludeTarget t : cfg.getEffectiveExcludeTargets()) {
            if (t == null || !StringUtils.hasText(t.getClassName())) {
                continue;
            }
            if (!className.equals(t.getClassName().trim())) {
                continue;
            }
            if (t.isClassLevel()) {
                return true;
            }
            if (StringUtils.hasText(methodSignature)
                    && methodSignature.equals(t.getMethodSignature().trim())) {
                return true;
            }
        }
        return false;
    }

    private String collectReachableSourceInternal(Long taskId, String entryClassName, File projectDir, EntryPointConfig config) {
        if (taskId == null || !StringUtils.hasText(entryClassName) || projectDir == null || !projectDir.exists()) {
            return "";
        }

        // 入口自身若命中排除规则 → 直接返回空
        ParsedClassInfo entryInfo = null;
        try {
            List<ParsedClassInfo> all = javaParserService.parseDirectory(projectDir);
            for (ParsedClassInfo p : all) {
                if (entryClassName.endsWith("." + p.getClassName())
                        || entryClassName.equals(p.getClassName())
                        || entryClassName.equals(fqName(p))) {
                    entryInfo = p;
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        if (entryInfo != null && isExcluded(entryClassName, entryInfo, config)) {
            return "";
        }

        List<MethodCall> calls = methodCallService.listByTaskId(taskId);
        if (calls == null || calls.isEmpty()) {
            return readSourceFile(projectDir, lookupFilePathByClass(calls, entryClassName), entryClassName);
        }

        // BFS：从入口类出发，遍历 dependencyName 找到可达类集合
        Map<String, Set<String>> classToDeps = new HashMap<>();
        Map<String, String> classToFilePath = new HashMap<>();
        for (MethodCall mc : calls) {
            if (!StringUtils.hasText(mc.getClassName())) {
                continue;
            }
            String depType = stripVariableFromDependencyName(mc.getDependencyName());
            if (StringUtils.hasText(depType)) {
                classToDeps.computeIfAbsent(mc.getClassName(), k -> new LinkedHashSet<>()).add(depType);
            }
            if (StringUtils.hasText(mc.getFilePath())) {
                classToFilePath.putIfAbsent(mc.getClassName(), mc.getFilePath());
            }
        }
        String entryShortName = entryClassName.contains(".") ? entryClassName.substring(entryClassName.lastIndexOf('.') + 1) : entryClassName;
        classToFilePath.putIfAbsent(entryShortName, lookupFilePathByClass(calls, entryClassName));

        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(entryShortName);
        reachable.add(entryShortName);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            Set<String> deps = classToDeps.get(cur);
            if (deps == null) continue;
            for (String dep : deps) {
                if (reachable.contains(dep)) continue;
                // 排除传染：邻居依赖命中排除规则则不展开
                ParsedClassInfo depInfo = null;
                try {
                    List<ParsedClassInfo> all = javaParserService.parseDirectory(projectDir);
                    for (ParsedClassInfo p : all) {
                        if (p.getClassName() != null && p.getClassName().equals(dep)) {
                            depInfo = p;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
                if (depInfo != null) {
                    String depFq = fqName(depInfo);
                    if (isExcluded(depFq, depInfo, config)) {
                        continue;
                    }
                }
                reachable.add(dep);
                queue.add(dep);
            }
        }

        // 拼接源码
        StringBuilder sb = new StringBuilder();
        for (String className : reachable) {
            String relPath = classToFilePath.get(className);
            if (relPath == null) {
                relPath = lookupFilePathByClass(calls, className);
            }
            String content = readSourceFile(projectDir, relPath, className);
            sb.append("// === Class: ").append(className).append(" ===\n");
            sb.append(content).append("\n\n");
        }
        return sb.toString();
    }

    // ============================ method extract helpers ============================

    /**
     * 根据入口类型从 ParsedClassInfo 中抽取关键方法列表
     * <ul>
     *   <li>CONTROLLER：仅 {@code requestMapping != null} 的方法（即带 HTTP mapping 注解）</li>
     *   <li>SCHEDULED_JOB / MQ_LISTENER / COMPONENT：当前 parser 不存方法级注解，回退为所有非 private 方法，
     *       annotation 字段填 "class-level: @xxx" 标识</li>
     *   <li>APPLICATION / MAIN：{@code main(String[])} 方法（hasMainMethod 已识别）</li>
     * </ul>
     */
    private List<DiscoveredMethod> extractMethodsForEntry(File projectDir, EntryPoint entry) {
        if (entry == null) return java.util.Collections.emptyList();
        ParsedClassInfo info = null;
        try {
            File sourceFile = resolveEntryFile(projectDir, entry);
            if (sourceFile != null && sourceFile.exists()) {
                info = javaParserService.parseFile(sourceFile);
            }
        } catch (Exception e) {
            log.warn("extractMethodsForEntry: 解析 {} 失败", entry.getClassName(), e);
        }
        if (info == null || info.getMethods() == null || info.getMethods().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        String entryType = entry.getEntryType();
        List<DiscoveredMethod> out = new java.util.ArrayList<>();
        if ("CONTROLLER".equals(entryType)) {
            // 仅保留带 RequestMapping 注解的方法
            for (ParsedClassInfo.MethodInfo m : info.getMethods()) {
                if (m.getRequestMapping() == null) continue;
                DiscoveredMethod dm = new DiscoveredMethod();
                dm.setMethodName(m.getName());
                dm.setMethodSignature(buildSignature(entry.getClassName(), m));
                dm.setAnnotation(extractHttpMappingAnnotation(info, m));
                dm.setHttpPath(m.getRequestMapping());
                dm.setHttpMethod(m.getHttpMethod());
                dm.setBodyHash(m.getBodyHash());
                out.add(dm);
            }
        } else if ("APPLICATION".equals(entryType) || "MAIN".equals(entryType)) {
            for (ParsedClassInfo.MethodInfo m : info.getMethods()) {
                if ("main".equals(m.getName())) {
                    DiscoveredMethod dm = new DiscoveredMethod();
                    dm.setMethodName(m.getName());
                    dm.setMethodSignature(buildSignature(entry.getClassName(), m));
                    dm.setAnnotation("main");
                    dm.setBodyHash(m.getBodyHash());
                    out.add(dm);
                    break;
                }
            }
        } else if ("SCHEDULED_JOB".equals(entryType) || "MQ_LISTENER".equals(entryType)
                || "COMPONENT".equals(entryType) || EntryPointConfig.TYPE_OTHER.equals(entryType)) {
            // parser 当前不抽方法级注解，回退为所有非 private 方法 + class-level annotation 标记
            String classLevelAnn = pickClassLevelAnnotation(info, entryType);
            for (ParsedClassInfo.MethodInfo m : info.getMethods()) {
                if (m.getName() == null) continue;
                if (m.getName().startsWith("lambda$") || m.getName().startsWith("$")) continue;
                // 过滤 setter/getter 与 private/同步等明显非业务方法
                if (isLikelyNonBusinessMethod(m)) continue;
                DiscoveredMethod dm = new DiscoveredMethod();
                dm.setMethodName(m.getName());
                dm.setMethodSignature(buildSignature(entry.getClassName(), m));
                dm.setAnnotation("class-level: " + classLevelAnn);
                dm.setBodyHash(m.getBodyHash());
                out.add(dm);
            }
        } else {
            // CUSTOM / 其它：返回所有 public 方法
            for (ParsedClassInfo.MethodInfo m : info.getMethods()) {
                if (isLikelyNonBusinessMethod(m)) continue;
                DiscoveredMethod dm = new DiscoveredMethod();
                dm.setMethodName(m.getName());
                dm.setMethodSignature(buildSignature(entry.getClassName(), m));
                dm.setBodyHash(m.getBodyHash());
                out.add(dm);
            }
        }
        return out;
    }

    private String buildSignature(String fqClassName, ParsedClassInfo.MethodInfo m) {
        String args = m.getArguments() == null ? "" : m.getArguments();
        String shortName = fqClassName == null ? "" : (fqClassName.contains(".")
                ? fqClassName.substring(fqClassName.lastIndexOf('.') + 1) : fqClassName);
        return (shortName.isEmpty() ? "" : shortName + "#") + m.getName() + "(" + args + ")";
    }

    private String extractHttpMappingAnnotation(ParsedClassInfo info, ParsedClassInfo.MethodInfo m) {
        if (m.getHttpMethod() == null) return "RequestMapping";
        switch (m.getHttpMethod()) {
            case "GET":    return "GetMapping";
            case "POST":   return "PostMapping";
            case "PUT":    return "PutMapping";
            case "DELETE": return "DeleteMapping";
            case "PATCH":  return "PatchMapping";
            default:       return "RequestMapping";
        }
    }

    private String pickClassLevelAnnotation(ParsedClassInfo info, String entryType) {
        if (info == null || info.getAnnotations() == null) return entryType;
        String prefix = switch (entryType) {
            case "SCHEDULED_JOB" -> "Scheduled";
            case "MQ_LISTENER"   -> "Listener";
            case "COMPONENT"     -> "Component";
            default              -> null;
        };
        if (prefix == null) return entryType;
        for (String ann : info.getAnnotations()) {
            if (ann != null && ann.contains(prefix)) return "@" + ann;
        }
        return entryType;
    }

    private boolean isLikelyNonBusinessMethod(ParsedClassInfo.MethodInfo m) {
        // 过滤标准 getter/setter（无参数且返回类型非 void、名为 getXxx/setXxx/isXxx）
        String name = m.getName();
        if (name == null) return true;
        String lower = name.toLowerCase();
        if (lower.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
            // 过滤简单 getter；带参的 getXxx 不在此列
            return (m.getArguments() == null || m.getArguments().isEmpty());
        }
        if (lower.startsWith("set") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
            return (m.getArguments() == null || m.getArguments().isEmpty());
        }
        if (lower.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
            return (m.getArguments() == null || m.getArguments().isEmpty());
        }
        return false;
    }

    // ============================ rule helpers ============================

    private boolean matchesIncludeAnnotation(ParsedClassInfo info, TypeIncludeRules rules) {
        List<String> ruleList = rules.getEffectiveIncludeAnnotations();
        if (ruleList.isEmpty() || info.getAnnotations() == null) return false;
        for (String ann : info.getAnnotations()) {
            for (String rule : ruleList) {
                if (ann != null && rule != null && ann.contains(rule)) return true;
            }
        }
        return false;
    }

    private boolean matchesIncludeClasspath(String fq, TypeIncludeRules rules) {
        for (String pattern : rules.getEffectiveIncludeClasspaths()) {
            if (StringUtils.hasText(pattern) && pathMatcher.match(pattern, fq)) return true;
        }
        return false;
    }

    private boolean matchesIncludeExtends(ParsedClassInfo info, TypeIncludeRules rules) {
        List<String> ruleList = rules.getEffectiveIncludeExtends();
        if (ruleList.isEmpty()) return false;
        if (StringUtils.hasText(info.getExtendsClass())) {
            for (String r : ruleList) if (r != null && r.equals(info.getExtendsClass())) return true;
        }
        if (info.getImplementsList() != null) {
            for (String impl : info.getImplementsList()) {
                for (String r : ruleList) if (r != null && r.equals(impl)) return true;
            }
        }
        return false;
    }

    /**
     * 三类排除规则"或"逻辑：任一命中即排除
     */
    private boolean isExcluded(String fq, ParsedClassInfo info, EntryPointConfig cfg) {
        if (cfg == null) return false;

        for (String p : cfg.getEffectiveExcludeClasspaths()) {
            if (StringUtils.hasText(p) && pathMatcher.match(p, fq)) return true;
        }
        for (String pkg : cfg.getEffectiveExcludePackages()) {
            if (StringUtils.hasText(pkg)) {
                String prefix = pkg.endsWith(".") ? pkg : pkg + ".";
                if (fq.startsWith(prefix) || fq.equals(pkg)) return true;
            }
        }
        if (info != null && info.getAnnotations() != null) {
            for (String ann : info.getAnnotations()) {
                for (String rule : cfg.getEffectiveExcludeAnnotations()) {
                    if (ann != null && rule != null && ann.contains(rule)) return true;
                }
            }
        }
        return false;
    }

    private String firstHitAnnotation(ParsedClassInfo info, TypeIncludeRules rules) {
        if (info.getAnnotations() == null) return null;
        for (String ann : info.getAnnotations()) {
            for (String rule : rules.getEffectiveIncludeAnnotations()) {
                if (ann != null && rule != null && ann.contains(rule)) return ann;
            }
        }
        return null;
    }

    // ============================ entry builder ============================

    private void addEntry(Map<String, EntryPoint> entries, String fq, ParsedClassInfo info, String entryType,
                        String annotation, Map<String, String> shortNameToFilePath) {
        if (entries.containsKey(fq)) return;
        EntryPoint ep = new EntryPoint();
        ep.setClassName(fq);
        ep.setFilePath(resolveFilePathForClass(fq, info, shortNameToFilePath));
        ep.setEntryType(entryType);
        ep.setAnnotation(annotation);
        ep.setRemark(info.getRequestMapping());
        entries.put(fq, ep);
    }

    private String extractTriggerAnnotation(List<String> annotations, String entryType) {
        if (annotations == null || annotations.isEmpty()) return null;
        return switch (entryType) {
            case "CONTROLLER" -> annotations.stream().filter(a -> a.contains("Controller")).findFirst().orElse(null);
            case "SCHEDULED_JOB" -> annotations.stream().filter(a -> a.contains("Scheduled") || a.contains("EnableScheduling")).findFirst().orElse(null);
            case "MQ_LISTENER" -> annotations.stream().filter(a -> a.contains("Listener")).findFirst().orElse(null);
            case "COMPONENT" -> annotations.stream().filter(a -> a.equals("Component")).findFirst().orElse(null);
            case EntryPointConfig.TYPE_OTHER -> annotations.stream().findFirst().orElse(null);
            default -> null;
        };
    }

    // ============================ low-level helpers ============================

    private String fqName(ParsedClassInfo info) {
        if (!StringUtils.hasText(info.getPackageName())) return info.getClassName();
        return info.getPackageName() + "." + info.getClassName();
    }

    private String resolveFilePathForClass(String fqcn, ParsedClassInfo info, Map<String, String> shortNameToFilePath) {
        if (info != null && StringUtils.hasText(info.getSourceRelativePath())) {
            return info.getSourceRelativePath();
        }
        if (StringUtils.hasText(fqcn)) {
            String shortName = fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
            String fromCalls = shortNameToFilePath != null ? shortNameToFilePath.get(shortName) : null;
            if (StringUtils.hasText(fromCalls)) {
                return fromCalls;
            }
        }
        return inferStandardMavenPath(info != null ? info.getClassName() : null, info);
    }

    private String inferStandardMavenPath(String className, ParsedClassInfo info) {
        if (info != null && StringUtils.hasText(info.getPackageName()) && StringUtils.hasText(className)) {
            return "src/main/java/" + info.getPackageName().replace('.', '/') + "/" + className + ".java";
        }
        return null;
    }

    private File findJavaFileByFqcn(File projectDir, String fqcn) {
        if (projectDir == null || !StringUtils.hasText(fqcn) || !fqcn.contains(".")) {
            return null;
        }
        String suffix = fqcn.replace('.', '/') + ".java";
        return findFileByPathSuffix(projectDir, suffix);
    }

    private File findFileByPathSuffix(File dir, String pathSuffix) {
        if (dir == null || !dir.exists() || !StringUtils.hasText(pathSuffix)) {
            return null;
        }
        String normalizedSuffix = pathSuffix.replace('\\', '/');
        if (dir.isFile()) {
            String rel = dir.getPath().replace('\\', '/');
            return rel.endsWith(normalizedSuffix) ? dir : null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            File hit = findFileByPathSuffix(child, normalizedSuffix);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private String lookupFilePathByClass(List<MethodCall> calls, String className) {
        if (calls == null || !StringUtils.hasText(className)) {
            return null;
        }
        String shortName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
        for (MethodCall mc : calls) {
            if (shortName.equals(mc.getClassName()) && StringUtils.hasText(mc.getFilePath())) {
                return mc.getFilePath();
            }
        }
        return null;
    }

    private String stripVariableFromDependencyName(String dependencyName) {
        if (!StringUtils.hasText(dependencyName)) return null;
        int colonIdx = dependencyName.indexOf(':');
        return colonIdx >= 0 ? dependencyName.substring(colonIdx + 1) : dependencyName;
    }

    private String readSourceFile(File projectDir, String relativePath, String fallbackClassName) {
        if (projectDir == null) return "// (no source: projectDir null)";
        File target = null;
        if (StringUtils.hasText(relativePath)) {
            target = new File(projectDir, relativePath.replace('/', File.separatorChar));
        }
        if (target == null || !target.exists()) {
            target = findJavaFileByFqcn(projectDir, fallbackClassName);
        }
        if (target != null && target.exists()) {
            try {
                return Files.readString(target.toPath());
            } catch (Exception e) {
                log.warn("read source failed: {}", target.getAbsolutePath(), e);
            }
        }
        return "// (source not found: " + (target == null ? "null" : target.getAbsolutePath()) + ")";
    }
}