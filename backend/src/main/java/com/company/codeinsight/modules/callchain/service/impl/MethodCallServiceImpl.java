package com.company.codeinsight.modules.callchain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.callchain.model.MethodCallEdgeLite;
import com.company.codeinsight.modules.callchain.service.MethodCallService;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.company.codeinsight.modules.scanner.model.IncrementalContext;
import com.company.codeinsight.modules.scanner.model.ScanScope;
import com.company.codeinsight.modules.scanner.service.ScanScopeResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 方法调用链路服务实现类
 * 递归遍历项目目录，复用 JavaParserService.parseFile 收集 methodCalls，按批次写入 ci_method_call 表。
 * 单文件解析异常不中断整批；任务重试时通过 deleteByTaskId + 批量 insert 保证幂等。
 * <p>
 * 不包长事务：软删为短 SQL；batch 独立提交。增量基线继承由流水线负责，本类不再 inherit。
 */
@Slf4j
@Service
public class MethodCallServiceImpl implements MethodCallService {

    /** 单次批量入库的缓冲区大小 */
    private static final int BATCH_SIZE = 500;

    /** 入口识别轻量边分页大小 */
    private static final int EDGE_LITE_PAGE_SIZE = 5000;

    /** 调用表达式最大长度（防止超长表达式撑爆 VARCHAR(1000)） */
    private static final int MAX_EXPR_LEN = 1000;

    /** 递归遍历时跳过的目录（与 ci_file_snapshot 扫描口径保持一致） */
    private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
            "target", "build", "node_modules", ".git", ".idea", ".vscode", "dist", "out"
    ));

    @Autowired
    private MethodCallMapper methodCallMapper;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private JavaParserService javaParserService;

    @Autowired
    private ScanScopeResolver scanScopeResolver;

    @Override
    public int persistAstForTask(Long taskId, File projectDir) {
        return persistAstForTask(taskId, projectDir, IncrementalContext.fullScan());
    }

    @Override
    public int persistAstForTask(Long taskId, File projectDir, IncrementalContext ctx) {
        if (taskId == null || projectDir == null || !projectDir.exists() || !projectDir.isDirectory()) {
            log.warn("persistAstForTask skipped: taskId={}, projectDir={}", taskId, projectDir);
            return 0;
        }
        IncrementalContext effective = ctx == null ? IncrementalContext.fullScan() : ctx;
        ScanScope scope = scanScopeResolver.resolveBestEffort(taskId, projectDir);

        if (!effective.isIncremental()) {
            // 全量：幂等软删 + 走扫描根子树（无外层长事务）
            deleteByTaskId(taskId);
            return walkAndPersist(projectDir, scope.getEffectiveRoot(), taskId, null, scope);
        }

        // 增量：基线边已由 pipeline inherit；此处只覆盖变更 / 删除路径
        if (!effective.getDeletedPaths().isEmpty()) {
            methodCallMapper.delete(
                    new LambdaQueryWrapper<MethodCall>()
                            .eq(MethodCall::getTaskId, taskId)
                            .in(MethodCall::getFilePath, effective.getDeletedPaths())
            );
        }
        if (!effective.getChangedPaths().isEmpty()) {
            methodCallMapper.delete(
                    new LambdaQueryWrapper<MethodCall>()
                            .eq(MethodCall::getTaskId, taskId)
                            .in(MethodCall::getFilePath, effective.getChangedPaths())
            );
        }
        int inserted = walkAndPersist(projectDir, scope.getEffectiveRoot(), taskId, effective.getChangedPaths(), scope);
        log.info("AST incremental call-chain persistence done. taskId={}, ctx={}, callsInserted={}",
                taskId, effective, inserted);
        return inserted;
    }

    /**
     * 递归遍历并把 .java 文件的 method calls 落表。
     *
     * @param pathFilter 非 null 时只解析相对路径命中该集合的文件（增量模式用）；null 表示全量遍历。
     * @return 本次实际写入的调用链条目数
     */
    private int walkAndPersist(File baseDir, File startDir, Long taskId, Set<String> pathFilter, ScanScope scope) {
        int[] counters = new int[]{0, 0, 0};
        List<MethodCall> buffer = new ArrayList<>(BATCH_SIZE);
        walk(baseDir, startDir, taskId, buffer, counters, pathFilter, scope);
        if (!buffer.isEmpty()) {
            insertBatch(buffer);
        }
        log.info("AST call-chain persistence done. taskId={}, filesScanned={}, filesFailed={}, callsInserted={}",
                taskId, counters[0], counters[1], counters[2]);
        return counters[2];
    }

    @Override
    public List<MethodCall> listByTaskId(Long taskId) {
        return methodCallMapper.selectList(
                new LambdaQueryWrapper<MethodCall>()
                        .eq(MethodCall::getTaskId, taskId)
                        .orderByAsc(MethodCall::getId)
        );
    }

    @Override
    public void forEachEdgeLite(Long taskId, Consumer<List<MethodCallEdgeLite>> consumer) {
        if (taskId == null || consumer == null) {
            return;
        }
        long afterId = 0L;
        while (true) {
            List<MethodCallEdgeLite> page = methodCallMapper.selectEdgeLitePage(taskId, afterId, EDGE_LITE_PAGE_SIZE);
            if (page == null || page.isEmpty()) {
                return;
            }
            consumer.accept(page);
            Long lastId = page.get(page.size() - 1).getId();
            if (lastId == null || page.size() < EDGE_LITE_PAGE_SIZE) {
                return;
            }
            afterId = lastId;
        }
    }

    @Override
    public List<MethodCall> listByClass(Long taskId, String className) {
        if (!StringUtils.hasText(className)) {
            return new ArrayList<>();
        }
        return methodCallMapper.selectList(
                new LambdaQueryWrapper<MethodCall>()
                        .eq(MethodCall::getTaskId, taskId)
                        .eq(MethodCall::getClassName, className)
                        .orderByAsc(MethodCall::getClassName, MethodCall::getCallerMethod, MethodCall::getLineNumber)
        );
    }

    @Override
    public void deleteByTaskId(Long taskId) {
        methodCallMapper.delete(
                new LambdaQueryWrapper<MethodCall>().eq(MethodCall::getTaskId, taskId)
        );
    }

    // ============================ private helpers ============================

    /**
     * 递归遍历目录，识别 Java 文件后调用 JavaParserService.parseFile 并把 methodCalls 灌入 buffer。
     *
     * @param pathFilter 非 null 时只解析相对路径命中该集合的文件（增量场景用）；
     *                   目录级短路：若子树中没有命中文件，直接跳过递归。
     */
    private void walk(File baseDir, File current, Long taskId, List<MethodCall> buffer, int[] counters,
                      Set<String> pathFilter, ScanScope scope) {
        if (current.isDirectory()) {
            String dirRel = relativize(baseDir, current);
            // effectiveRoot 自身相对路径可能为空或等于 scanRoot；空路径视为可进入
            if (StringUtils.hasText(dirRel) && !scope.acceptsDirectory(dirRel)) {
                return;
            }
            File[] children = current.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                if (child.isDirectory() && SKIP_DIRS.contains(child.getName())) {
                    continue;
                }
                walk(baseDir, child, taskId, buffer, counters, pathFilter, scope);
            }
            return;
        }

        if (!current.isFile() || !current.getName().endsWith(".java")) {
            return;
        }

        String relativePath = relativize(baseDir, current);
        if (!scope.accepts(relativePath)) {
            return;
        }
        // 增量模式：仅处理命中 pathFilter 的文件
        if (pathFilter != null && !pathFilter.contains(relativePath)) {
            return;
        }

        counters[0]++;
        try {
            ParsedClassInfo info = javaParserService.parseFile(current);
            if (info == null || info.getClassName() == null
                    || info.getMethodCalls() == null || info.getMethodCalls().isEmpty()) {
                return;
            }

            for (ParsedClassInfo.MethodCallInfo src : info.getMethodCalls()) {
                MethodCall mc = new MethodCall();
                mc.setTaskId(taskId);
                mc.setFilePath(relativePath);
                mc.setClassName(info.getClassName());
                mc.setCallerMethod(src.getCallerMethod());
                mc.setCallerSignature(buildCallerSignature(info.getClassName(), src.getCallerSignature()));
                mc.setDependencyName(src.getDependencyName());
                mc.setTargetMethod(src.getTargetMethod());
                mc.setTargetSignature(buildTargetSignature(
                        src.getDependencyName(), src.getTargetMethod(), src.getTargetSignature()));
                mc.setExpression(truncate(src.getExpression(), MAX_EXPR_LEN));
                mc.setLineNumber(src.getLineNumber());
                // Phase 3：多态候选集透传（候选解析阶段已经在 parser 模块做完）
                mc.setDependencyCandidates(com.company.codeinsight.common.util.DbStringLimits.truncate(
                        src.getDependencyCandidates(),
                        com.company.codeinsight.common.util.DbStringLimits.DEPENDENCY_CANDIDATES));
                mc.setCreatedDate(LocalDateTime.now());
                buffer.add(mc);
                counters[2]++;

                if (buffer.size() >= BATCH_SIZE) {
                    insertBatch(buffer);
                }
            }
        } catch (Exception e) {
            counters[1]++;
            log.error("AST parse failed for file: {}", current.getAbsolutePath(), e);
        }
    }

    /**
     * 把缓冲区里的全部记录批量插入 ci_method_call 表（JDBC batch 模式），然后清空缓冲区。
     * 失败抛 {@link BusinessException}，由流水线将任务标为 FAILED，禁止静默丢边。
     */
    private void insertBatch(List<MethodCall> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        int size = buffer.size();
        try {
            try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
                MethodCallMapper mapper = sqlSession.getMapper(MethodCallMapper.class);
                for (MethodCall mc : buffer) {
                    mapper.insert(mc);
                }
                sqlSession.flushStatements();
                sqlSession.commit();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("批量写入方法调用链失败，count={}", size, e);
            throw new BusinessException("批量写入方法调用链失败: " + e.getMessage());
        } finally {
            buffer.clear();
        }
    }

    /**
     * 把绝对路径转成相对于项目根的 unix 风格相对路径（如 src/main/java/Foo.java）。
     */
    private String relativize(File baseDir, File file) {
        try {
            String abs = file.getAbsolutePath();
            String base = baseDir.getAbsolutePath();
            if (abs.startsWith(base)) {
                String rel = abs.substring(base.length());
                while (rel.startsWith(File.separator) || rel.startsWith("/")) {
                    rel = rel.substring(1);
                }
                return rel.replace(File.separatorChar, '/');
            }
        } catch (Exception ignored) {
            // fall through to file name
        }
        return file.getName();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /**
     * 拼装 caller_signature："className#methodName(ParamType1, ParamType2)"
     * （methodSignature 段由 parser 提供，可含参数名）
     */
    private String buildCallerSignature(String className, String methodSignature) {
        if (!StringUtils.hasText(className) || !StringUtils.hasText(methodSignature)) {
            return null;
        }
        return className + "#" + methodSignature;
    }

    /**
     * 规范化 target_signature 为 {@code 短类名#method(...)}。
     * parser 已给出含 {@code #} 的签名时仅校正类名前缀；否则用 dependencyName + targetMethod 补全。
     */
    static String buildTargetSignature(String dependencyName, String targetMethod, String fromParser) {
        if (StringUtils.hasText(fromParser) && fromParser.contains("#")) {
            int hash = fromParser.indexOf('#');
            String classPart = stripPackage(fromParser.substring(0, hash));
            String methodPart = fromParser.substring(hash + 1);
            if (StringUtils.hasText(classPart) && StringUtils.hasText(methodPart)) {
                return classPart + "#" + methodPart;
            }
        }
        String shortClass = stripPackage(dependencyName);
        if (!StringUtils.hasText(shortClass) || !StringUtils.hasText(targetMethod)) {
            return StringUtils.hasText(fromParser) ? fromParser : targetMethod;
        }
        if (StringUtils.hasText(fromParser) && fromParser.startsWith(targetMethod + "(")) {
            return shortClass + "#" + fromParser;
        }
        return shortClass + "#" + targetMethod;
    }

    private static String stripPackage(String fqOrShort) {
        if (!StringUtils.hasText(fqOrShort)) {
            return fqOrShort;
        }
        String t = fqOrShort.trim();
        int colon = t.lastIndexOf(':');
        if (colon >= 0 && colon < t.length() - 1) {
            t = t.substring(colon + 1).trim();
        }
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }
}
