package com.company.codeinsight.modules.parser.service.impl;

import com.company.codeinsight.modules.parser.config.JavaParserLanguageConfig;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodCallInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.SqlReference;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 基于真实语法树（AST）的 Java 静态解析实现（AST 引擎）。
 * 通过 JavaParser (com.github.javaparser) 将源文件解析为 CompilationUnit，
 * 用 visitor 模式抽取包名、类声明、注解、依赖字段、方法签名、调用链与 SQL。
 *
 * <p>相比正则实现的差异：</p>
 * <ul>
 *   <li>多行签名 / 泛型 / Lambda / 链式调用 / 内部类都能正确解析</li>
 *   <li>字段依赖、注解参数、方法体范围由 AST 直接给出，不依赖 brace counting</li>
 *   <li>解析失败抛 ParseProblemException，由 FallbackJavaParserService 接管回到正则实现</li>
 * </ul>
 *
 * <p>本类不直接暴露为 Spring Bean，由 ParserEngineConfig 按配置装配。</p>
 */
@Slf4j
public class AstJavaParserService implements JavaParserService {

    // 任务级缓存：与 RegexJavaParserService 兼容的 key 形式，复用下游 hit 率
    private final ConcurrentHashMap<String, ParsedClassInfo> parseCache;

    // SQL 字面量内容解析用的正则（与 RegexJavaParserService 等价，保持 SQL 字段提取一致性）
    private static final Pattern SQL_FROM_PATTERN = Pattern.compile("(?i)\\bFROM\\s+([`\"']?[\\w.]+[`\"']?)");
    private static final Pattern SQL_JOIN_PATTERN = Pattern.compile("(?i)\\bJOIN\\s+([`\"']?[\\w.]+[`\"']?)");
    private static final Pattern SQL_INTO_PATTERN = Pattern.compile("(?i)\\bINTO\\s+([`\"']?[\\w.]+[`\"']?)");
    private static final Pattern SQL_UPDATE_PATTERN = Pattern.compile("(?i)\\bUPDATE\\s+([`\"']?[\\w.]+[`\"']?)");
    private static final Pattern SQL_SELECT_PATTERN = Pattern.compile("(?i)SELECT\\s+([\\s\\S]+?)\\s+FROM\\s+");
    private static final Pattern SQL_INSERT_PATTERN = Pattern.compile("(?i)INSERT\\s+INTO\\s+[\\w.`\"']+\\s*\\(([^)]*)\\)");
    private static final Pattern SQL_UPDATE_SET_PATTERN = Pattern.compile("(?i)\\bSET\\s+(.+?)(?:\\bWHERE\\b|$)");
    private static final Pattern SQL_WHERE_CLAUSE = Pattern.compile("(?i)\\bWHERE\\b\\s+(.+?)(?:\\bGROUP\\b|\\bORDER\\b|\\bLIMIT\\b|$)");
    private static final Pattern SQL_WHERE_FIELD = Pattern.compile("([\\w.]+)\\s*(?:=|<>|!=|>=|<=|>|<|LIKE\\b|IN\\b|BETWEEN\\b)");

    public AstJavaParserService() {
        this(new ConcurrentHashMap<>());
    }

    /** 用于测试或 Fallback 共享同一缓存 */
    public AstJavaParserService(ConcurrentHashMap<String, ParsedClassInfo> cache) {
        this.parseCache = cache;
    }

    @Override
    public ParsedClassInfo parseFile(File file) {
        JavaParserLanguageConfig.ensureStaticJavaParserConfigured();
        if (file == null || !file.exists() || !file.getName().endsWith(".java")) {
            return null;
        }
        String key = cacheKey(file);
        ParsedClassInfo cached = parseCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            // 0. Phase 2/3：先探测项目上下文，再用带 SymbolResolver 的 Parser 解析，
            //    否则 CU 无求解器，tryResolveReceiverType / toResolvedType 在真实 Maven 布局下恒失败。
            ProjectContext ctx = acquireProjectContext(file);
            CompilationUnit cu = parseCompilationUnit(file, ctx);
            ParsedClassInfo info = new ParsedClassInfo();
            info.setType("UNKNOWN");

            // 1. 包名
            cu.getPackageDeclaration().ifPresent(p -> info.setPackageName(p.getNameAsString()));

            // 2. 顶层类型声明（class / interface / enum / @interface / record）。
            //    注意：getPrimaryType() 仅在文件名与类名一致或类为 public 时返回；
            //    真实场景（gtest 文件名 ≠ 类名，或单文件多类）会用 getTypes() 全集遍历。
            TypeDeclaration<?> td = pickTopLevelType(cu);
            if (td != null) {
                if (td instanceof ClassOrInterfaceDeclaration) {
                    fillFromClass((ClassOrInterfaceDeclaration) td, info, ctx);
                } else if (td instanceof EnumDeclaration) {
                    info.setClassName(td.getNameAsString());
                    info.setType("ENUM");
                } else if (td instanceof AnnotationDeclaration) {
                    info.setClassName(td.getNameAsString());
                    info.setType("ANNOTATION");
                } else {
                    info.setClassName(td.getNameAsString());
                }
            }

            // 3. 类型兜底（注解 + 名称后缀）
            detectTypeByNameSuffix(info);

            // 4. 方法调用收集（caller = 直接外包方法）
            fillMethodCalls(info);

            // 5. SQL 字面量扫描（仅扫描方法体或顶层 StringLiteralExpr）
            fillSqlReferences(info);

            // 6. public static void main 检测
            detectMainMethod(info);

            parseCache.put(key, info);
            return info;
        } catch (ParseProblemException ex) {
            throw ex; // 由 FallbackJavaParserService 兜底
        } catch (Exception ex) {
            throw new RuntimeException("AST parse failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<ParsedClassInfo> parseDirectory(File directory) {
        JavaParserLanguageConfig.ensureStaticJavaParserConfigured();
        List<ParsedClassInfo> list = new ArrayList<>();
        if (directory == null || !directory.exists()) return list;
        walkAndParse(directory, directory, list);
        return list;
    }

    private void walkAndParse(File root, File current, List<ParsedClassInfo> out) {
        if (current.isDirectory()) {
            File[] files = current.listFiles();
            if (files != null) for (File f : files) walkAndParse(root, f, out);
            return;
        }
        if (!current.getName().endsWith(".java")) return;
        try {
            ParsedClassInfo info = parseFile(current);
            if (info != null && info.getClassName() != null) {
                String rel = root.toURI().relativize(current.toURI()).getPath();
                info.setSourceRelativePath(rel.replace('\\', '/'));
                out.add(info);
            }
        } catch (ParseProblemException ex) {
            log.warn("AST parseDirectory skip file {}: {}", current.getAbsolutePath(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("AST parseDirectory skip file {} due to runtime error: {}",
                    current.getAbsolutePath(), ex.toString());
        }
    }

    /**
     * 从 CompilationUnit 顶层类型里挑第一个"业务类型"。
     * 优先 ClassOrInterfaceDeclaration（覆盖常规 service/controller/mapper 等大部分场景），
     * 兼顾 Enum / AnnotationDeclaration。
     */
    private TypeDeclaration<?> pickTopLevelType(CompilationUnit cu) {
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (td instanceof ClassOrInterfaceDeclaration) {
                return td;
            }
        }
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (td instanceof EnumDeclaration) {
                return td;
            }
        }
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (td instanceof AnnotationDeclaration) {
                return td;
            }
        }
        return cu.getTypes().isEmpty() ? null : cu.getTypes().get(0);
    }

    // -------- 以下为 AST → ParsedClassInfo 的填充逻辑 --------

    private void fillFromClass(ClassOrInterfaceDeclaration cid, ParsedClassInfo info,
                               ProjectContext ctx) {
        JavaSymbolSolver symbolSolver = ctx == null ? null : ctx.symbolSolver;
        info.setClassName(cid.getNameAsString());

        // extends / implements（取第一个）
        if (!cid.getExtendedTypes().isEmpty()) {
            info.setExtendsClass(cid.getExtendedTypes().get(0).getNameAsString());
        }
        for (ClassOrInterfaceType impl : cid.getImplementedTypes()) {
            info.getImplementsList().add(impl.getNameAsString());
        }

        // 注解
        for (AnnotationExpr ann : cid.getAnnotations()) {
            info.getAnnotations().add(simpleName(ann.getNameAsString()));
        }

        // 类级 @RequestMapping
        String classMapping = extractMappingFromAnnotations(cid.getAnnotations(), "RequestMapping");
        if (StringUtils.hasText(classMapping)) {
            info.setRequestMapping(classMapping);
        }

        // 按注解推断组件角色
        detectTypeFromAnnotations(info);

        // 字段依赖
        Map<String, String> depVars = new LinkedHashMap<>();
        for (FieldDeclaration fd : cid.getFields()) {
            // 字段上的注解（如 @Autowired）
            for (AnnotationExpr ann : fd.getAnnotations()) {
                info.getAnnotations().add(simpleName(ann.getNameAsString()));
            }
            for (VariableDeclarator v : fd.getVariables()) {
                String type = stripGeneric(v.getType().asString());
                String name = v.getNameAsString();
                if (!StringUtils.hasText(name) || isSimpleValueType(type)) continue;
                depVars.put(name, type);
                if (!info.getDependencies().contains(name + ":" + type)) {
                    info.getDependencies().add(name + ":" + type);
                }
            }
        }

        // 构造器注入：把构造器参数也加入 depVars
        for (ConstructorDeclaration ctor : cid.getConstructors()) {
            for (Parameter p : ctor.getParameters()) {
                String type = stripGeneric(p.getType().asString());
                String name = p.getNameAsString();
                if (!StringUtils.hasText(name) || isSimpleValueType(type)) continue;
                if (!depVars.containsKey(name)) {
                    depVars.put(name, type);
                }
                if (!info.getDependencies().contains(name + ":" + type)) {
                    info.getDependencies().add(name + ":" + type);
                }
            }
        }

        // 本类方法名（含 private），供同文件 / this 调用落边
        Set<String> ownMethodNames = new LinkedHashSet<>();
        for (MethodDeclaration m : cid.getMethods()) {
            if (m.getNameAsString() != null) {
                ownMethodNames.add(m.getNameAsString());
            }
        }

        // 方法（含起止行号、注解、HTTP 路由、返回类型、参数）
        for (MethodDeclaration md : cid.getMethods()) {
            MethodInfo mi = new MethodInfo();
            mi.setName(md.getNameAsString());
            mi.setReturnType(stripGeneric(md.getType().asString()));
            mi.setArguments(buildArgsText(md.getParameters()));
            if (md.getRange().isPresent()) {
                Range r = md.getRange().get();
                mi.setStartLine(r.begin.line);
                mi.setEndLine(r.end.line);
            }
            // 方法级 HTTP 路由
            for (String verb : new String[]{"GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "RequestMapping"}) {
                String m = extractMappingFromAnnotations(md.getAnnotations(), verb);
                if (StringUtils.hasText(m)) {
                    mi.setRequestMapping(m);
                    mi.setHttpMethod(verb.equals("RequestMapping") ? "ALL" : verb.replace("Mapping", "").toUpperCase(Locale.ROOT));
                    break;
                }
            }
            // 在每个方法体内做调用链 + SQL 收集，存到 info 的暂存上下文
            // 用一个 inner state 暂存，由 fillMethodCalls / fillSqlReferences 完成遍历
            attachMethodBody(md, mi, info, depVars, ownMethodNames, symbolSolver,
                    ctx == null ? null : ctx.subtypeIndex);
            info.getMethods().add(mi);
        }

        // 收集工作已在此方法体内完成（attachMethodBody 内同时遍历调用链与 SQL 字面量）
    }

    /** Object / 明显噪声方法：同类调用落边时跳过，避免 BFS 膨胀。 */
    private static final Set<String> SKIP_SAME_CLASS_CALLEES = Set.of(
            "equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait",
            "clone", "finalize"
    );

    /**
     * 抽出方法级 MethodCallExpr：caller 签名、dependency、完整 target 签名（短类名#method(ParamTypes)）。
     * <p>Phase 2：symbolSolver / import 升级声明类型；Phase 3：subtypeIndex 填多态候选。
     * 另：无 scope / {@code this.} 且 callee 为本类方法时记同类边（requireProduct 等 private 助手）。</p>
     */
    private void attachMethodBody(MethodDeclaration md, MethodInfo mi, ParsedClassInfo info,
                                 Map<String, String> depVars,
                                 Set<String> ownMethodNames,
                                 JavaSymbolSolver symbolSolver,
                                 Map<String, List<String>> subtypeIndex) {
        if (md.getBody().isEmpty()) return;
        BlockStmt body = md.getBody().get();
        mi.setBodyHash(hashMethodBody(body.toString()));
        CompilationUnit cu = md.findCompilationUnit().orElse(null);
        String contextPkg = info.getPackageName();
        String selfClass = info.getClassName();
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            MethodCallInfo ci = new MethodCallInfo();
            ci.setCallerMethod(mi.getName());
            ci.setCallerSignature(mi.getName() + "(" + (mi.getArguments() == null ? "" : mi.getArguments()) + ")");
            ci.setTargetMethod(call.getNameAsString());

            String depType = null;
            String receiver = null;
            if (call.getScope().isPresent()) {
                Expression scope = call.getScope().get();
                String scopeText = scope.toString().trim();
                if (depVars.containsKey(scopeText)) {
                    receiver = scopeText;
                    depType = depVars.get(scopeText);
                    // Phase 2：尝试把 depType 从"声明简单名"升级为"解析后的 FQ"
                    String resolved = tryResolveReceiverType(scope, symbolSolver);
                    if (resolved != null) {
                        depType = resolved;
                    } else {
                        String upgraded = upgradeDeclaredTypeName(depType, contextPkg, cu);
                        if (upgraded != null) {
                            depType = upgraded;
                        }
                    }
                    // Phase 3：把声明类型查表拿到该项目内的所有具体子类候选
                    ci.setDependencyCandidates(findCandidatesForFqcn(depType, subtypeIndex));
                } else if (isThisReceiver(scope)
                        && isSameClassCallee(call.getNameAsString(), ownMethodNames)) {
                    receiver = "this";
                    depType = selfClass;
                }
            } else if (isSameClassCallee(call.getNameAsString(), ownMethodNames)) {
                receiver = "this";
                depType = selfClass;
            }
            ci.setDependencyName(depType == null ? "" : depType);
            ci.setTargetSignature(buildTargetSignature(depType, call, symbolSolver));
            ci.setExpression((receiver == null ? "this" : receiver) + "." + call.getNameAsString() + "()");
            if (call.getRange().isPresent()) {
                ci.setLineNumber(call.getRange().get().begin.line);
            }
            // 注入依赖调用 或 同类助手调用 才落表
            if (depType != null) {
                info.getMethodCalls().add(ci);
            }

            // SQL：若实参含有 String 字面量像 SQL，扫一下
            for (Node child : call.getArguments()) {
                if (child instanceof StringLiteralExpr) {
                    maybeCollectSql(((StringLiteralExpr) child).asString(), info);
                }
            }
        }
        // 方法体内 StringLiteralExpr 直接定位的 SQL
        for (StringLiteralExpr s : body.findAll(StringLiteralExpr.class)) {
            maybeCollectSql(s.asString(), info);
        }
        // new XxxService(...) 也算依赖构造调用（不强制落表）
        // 暂记入 dependencies 以辅助模块识别（不重复加时由 addUnique 兜底）
        for (ObjectCreationExpr oc : body.findAll(ObjectCreationExpr.class)) {
            String t = oc.getType().getNameAsString();
            if (StringUtils.hasText(t) && !isSimpleValueType(t)) {
                if (!info.getDependencies().contains("new:" + t)) {
                    info.getDependencies().add("new:" + t);
                }
            }
        }
    }

    private static boolean isThisReceiver(Expression scope) {
        return scope != null && "this".equals(scope.toString().trim());
    }

    private static boolean isSameClassCallee(String methodName, Set<String> ownMethodNames) {
        if (!StringUtils.hasText(methodName) || ownMethodNames == null || ownMethodNames.isEmpty()) {
            return false;
        }
        if (SKIP_SAME_CLASS_CALLEES.contains(methodName)) {
            return false;
        }
        return ownMethodNames.contains(methodName);
    }

    /** 顶层入口：从整个 CompilationUnit 抽 MethodCallExpr。当前实现：调用链由 attachMethodBody 在
     *  方法遍历时收齐；若后续需要支持字段初始化器 / 顶层 lambda，此处可补 visitor */
    private void fillMethodCalls(ParsedClassInfo info) {
        // 当前无需额外工作
    }

    /** 同 fillMethodCalls，SQL 字面量由 attachMethodBody 处理 */
    private void fillSqlReferences(ParsedClassInfo info) {
        // 当前无需额外工作
    }

    private void detectTypeByNameSuffix(ParsedClassInfo info) {
        if (StringUtils.hasText(info.getType()) && !"UNKNOWN".equals(info.getType())) return;
        String className = info.getClassName();
        if (className == null) return;
        String lower = className.toLowerCase(Locale.ROOT);
        if (lower.endsWith("controller")) info.setType("CONTROLLER");
        else if (lower.endsWith("service") || lower.endsWith("serviceimpl")) info.setType("SERVICE");
        else if (lower.endsWith("mapper") || lower.endsWith("dao")) info.setType("MAPPER");
        else if (lower.endsWith("entity") || lower.endsWith("po")) info.setType("ENTITY");
        else if (lower.endsWith("dto")) info.setType("DTO");
        else if (lower.endsWith("vo")) info.setType("VO");
        else if (lower.endsWith("config") || lower.endsWith("configuration")) info.setType("CONFIG");
        else if (lower.endsWith("job") || lower.endsWith("task") || lower.endsWith("scheduler")) info.setType("JOB");
    }

    private void detectTypeFromAnnotations(ParsedClassInfo info) {
        List<String> ann = info.getAnnotations();
        if (ann.contains("RestController") || ann.contains("Controller")) {
            info.setType("CONTROLLER");
        } else if (ann.contains("Service") && "UNKNOWN".equals(info.getType())) {
            info.setType("SERVICE");
        } else if (ann.contains("Mapper") && "UNKNOWN".equals(info.getType())) {
            info.setType("MAPPER");
        } else if ((ann.contains("Entity") || ann.contains("Table")) && "UNKNOWN".equals(info.getType())) {
            info.setType("ENTITY");
        } else if (ann.contains("Configuration") && "UNKNOWN".equals(info.getType())) {
            info.setType("CONFIG");
        } else if ((ann.contains("Scheduled") || ann.contains("EnableScheduling")) && "UNKNOWN".equals(info.getType())) {
            info.setType("JOB");
        } else if ((ann.contains("RabbitListener") || ann.contains("KafkaListener")
                || ann.contains("JmsListener") || ann.contains("RocketMQMessageListener"))
                && "UNKNOWN".equals(info.getType())) {
            info.setType("MESSAGE_LISTENER");
        } else if (ann.contains("Component") && "UNKNOWN".equals(info.getType())) {
            info.setType("COMPONENT");
        }
    }

    private void detectMainMethod(ParsedClassInfo info) {
        for (MethodInfo m : info.getMethods()) {
            if ("main".equals(m.getName())
                    && "void".equalsIgnoreCase(m.getReturnType() == null ? "" : m.getReturnType().trim())
                    && m.getArguments() != null && m.getArguments().contains("String")) {
                info.setHasMainMethod(true);
                if ("UNKNOWN".equals(info.getType())) {
                    info.setType("APPLICATION");
                }
                return;
            }
        }
    }

    // -------- 注解参数提取（@XxxMapping("/foo") 等） --------

    /**
     * 从一批同名前缀的注解中取首个字符串参数。
     * JavaParser 3.x 把 {@code @XxxMapping("/foo")} 归类为 SingleMemberAnnotationExpr，
     * 把 {@code @XxxMapping(value = "/foo")} 归类为 NormalAnnotationExpr——
     * 不分两种子类，纯按 toString() 抓第一对引号即可覆盖两种形式。
     * 这与 Regex 版的"取注解内首个双引号串"语义等价。
     */
    private String extractMappingFromAnnotations(NodeList<AnnotationExpr> annotations, String name) {
        for (AnnotationExpr ann : annotations) {
            if (!name.equalsIgnoreCase(simpleName(ann.getNameAsString()))) continue;
            String text = ann.toString();
            int p = text.indexOf('(');
            if (p < 0) continue; // Marker 注释（无括号）一定没有路径
            int q1 = text.indexOf('"', p);
            int q2 = text.indexOf('"', q1 + 1);
            if (q1 > 0 && q2 > q1) return text.substring(q1 + 1, q2);
        }
        return null;
    }

    // -------- SQL 字面量扫描 + 字段提取 --------

    private void maybeCollectSql(String literal, ParsedClassInfo info) {
        if (literal == null) return;
        String upper = literal.toUpperCase(Locale.ROOT);
        if (!upper.contains("SELECT") && !upper.contains("INSERT") && !upper.contains("UPDATE") && !upper.contains("DELETE")) {
            return;
        }
        SqlReference ref = new SqlReference();
        ref.setOperation(resolveSqlOp(upper));
        extractTablesFromSql(literal, info, ref);
        extractSqlFields(literal, ref);
        if (StringUtils.hasText(ref.getOperation()) || !ref.getTables().isEmpty()) {
            info.getSqlReferences().add(ref);
        }
    }

    private String resolveSqlOp(String upperSql) {
        if (upperSql.contains("SELECT")) return "SELECT";
        if (upperSql.contains("INSERT")) return "INSERT";
        if (upperSql.contains("UPDATE")) return "UPDATE";
        if (upperSql.contains("DELETE")) return "DELETE";
        return "UNKNOWN";
    }

    private void extractTablesFromSql(String sql, ParsedClassInfo info, SqlReference ref) {
        collectTableMatches(SQL_FROM_PATTERN, sql, info, ref);
        collectTableMatches(SQL_INTO_PATTERN, sql, info, ref);
        collectTableMatches(SQL_UPDATE_PATTERN, sql, info, ref);
        Matcher joinMatcher = SQL_JOIN_PATTERN.matcher(sql);
        while (joinMatcher.find()) {
            String t = cleanIdent(joinMatcher.group(1));
            addUnique(t, info.getTables());
            addUnique(t, ref.getTables());
            addUnique(t, ref.getJoinedTables());
        }
    }

    private void collectTableMatches(Pattern p, String sql, ParsedClassInfo info, SqlReference ref) {
        Matcher m = p.matcher(sql);
        while (m.find()) {
            String t = cleanIdent(m.group(1));
            addUnique(t, info.getTables());
            addUnique(t, ref.getTables());
        }
    }

    private void extractSqlFields(String sql, SqlReference ref) {
        if ("SELECT".equals(ref.getOperation())) {
            Matcher m = SQL_SELECT_PATTERN.matcher(sql);
            if (m.find()) {
                for (String part : m.group(1).split(",")) {
                    String f = cleanIdent(part.replaceAll("(?i)\\s+AS\\s+\\w+", "")).trim();
                    if (StringUtils.hasText(f) && !"*".equals(f)) addUnique(f, ref.getSelectedFields());
                }
            }
            Matcher wm = SQL_WHERE_CLAUSE.matcher(sql);
            if (wm.find()) {
                Matcher wf = SQL_WHERE_FIELD.matcher(wm.group(1));
                while (wf.find()) addUnique(cleanIdent(wf.group(1)), ref.getConditionFields());
            }
        } else if ("INSERT".equals(ref.getOperation())) {
            Matcher m = SQL_INSERT_PATTERN.matcher(sql);
            if (m.find()) {
                for (String p : m.group(1).split(",")) {
                    String f = cleanIdent(p).trim();
                    if (StringUtils.hasText(f)) addUnique(f, ref.getInsertedFields());
                }
            }
        } else if ("UPDATE".equals(ref.getOperation())) {
            Matcher m = SQL_UPDATE_SET_PATTERN.matcher(sql);
            if (m.find()) {
                for (String part : m.group(1).split(",")) {
                    String[] kv = part.split("=");
                    if (kv.length > 0) addUnique(cleanIdent(kv[0]), ref.getUpdatedFields());
                }
            }
            Matcher wm = SQL_WHERE_CLAUSE.matcher(sql);
            if (wm.find()) {
                Matcher wf = SQL_WHERE_FIELD.matcher(wm.group(1));
                while (wf.find()) addUnique(cleanIdent(wf.group(1)), ref.getConditionFields());
            }
        } else if ("DELETE".equals(ref.getOperation())) {
            Matcher wm = SQL_WHERE_CLAUSE.matcher(sql);
            if (wm.find()) {
                Matcher wf = SQL_WHERE_FIELD.matcher(wm.group(1));
                while (wf.find()) addUnique(cleanIdent(wf.group(1)), ref.getConditionFields());
            }
        }
    }

    private void addUnique(String v, List<String> list) {
        if (StringUtils.hasText(v) && !list.contains(v)) list.add(v);
    }

    private String cleanIdent(String v) {
        if (v == null) return "";
        String c = v.replaceAll("[`\"']", "").replaceAll("\\$\\{[^}]+}", "").trim();
        int dot = c.lastIndexOf('.');
        if (dot >= 0) c = c.substring(dot + 1);
        return c.replaceAll("[^A-Za-z0-9_]", "");
    }

    // -------- 工具 --------

    private static String cacheKey(File file) {
        String abs = file.getAbsolutePath().replace('\\', '/');
        int idx = abs.indexOf("/task_");
        return idx >= 0 ? abs.substring(idx + 1) : abs;
    }

    @Override
    public void evictTaskCaches(Long taskId) {
        if (taskId == null) {
            return;
        }
        String prefix = "task_" + taskId + "/";
        int parseRemoved = 0;
        for (String key : parseCache.keySet()) {
            if (key != null && key.startsWith(prefix) && parseCache.remove(key) != null) {
                parseRemoved++;
            }
        }
        int solverRemoved = evictPathKeyedCache(SYMBOL_SOLVER_CACHE, taskId);
        int subtypeRemoved = evictPathKeyedCache(SUBTYPE_INDEX_CACHE, taskId);
        if (parseRemoved > 0 || solverRemoved > 0 || subtypeRemoved > 0) {
            log.info("evictTaskCaches taskId={} parse={} symbolSolver={} subtypeIndex={} | remaining {}",
                    taskId, parseRemoved, solverRemoved, subtypeRemoved, cacheStatsSummary());
        }
    }

    @Override
    public void clearAllCaches() {
        int parse = parseCache.size();
        int solver = SYMBOL_SOLVER_CACHE.size();
        int subtype = SUBTYPE_INDEX_CACHE.size();
        parseCache.clear();
        SYMBOL_SOLVER_CACHE.clear();
        SUBTYPE_INDEX_CACHE.clear();
        log.warn("AstJavaParserService.clearAllCaches cleared parse={} symbolSolver={} subtypeIndex={}",
                parse, solver, subtype);
    }

    @Override
    public String cacheStatsSummary() {
        return "parse=" + parseCache.size()
                + " symbolSolver=" + SYMBOL_SOLVER_CACHE.size()
                + " subtypeIndex=" + SUBTYPE_INDEX_CACHE.size();
    }

    /** 静态全局缓存规模（跨实例共享）。 */
    public static String globalCacheStatsSummary() {
        return "symbolSolver=" + SYMBOL_SOLVER_CACHE.size()
                + " subtypeIndex=" + SUBTYPE_INDEX_CACHE.size();
    }

    private static <V> int evictPathKeyedCache(ConcurrentHashMap<String, V> cache, Long taskId) {
        int removed = 0;
        for (String key : cache.keySet()) {
            if (matchesTaskWorkspacePath(key, taskId) && cache.remove(key) != null) {
                removed++;
            }
        }
        return removed;
    }

    /** workspace / SymbolSolver 绝对路径是否属于 task_{id} */
    public static boolean matchesTaskWorkspacePath(String path, Long taskId) {
        if (path == null || taskId == null) {
            return false;
        }
        String n = path.replace('\\', '/');
        String token = "task_" + taskId;
        int idx = n.indexOf(token);
        if (idx < 0) {
            return false;
        }
        int after = idx + token.length();
        return after >= n.length() || n.charAt(after) == '/' || n.charAt(after) == '-';
    }

    private static String simpleName(String annotation) {
        int dot = annotation.lastIndexOf('.');
        return dot >= 0 ? annotation.substring(dot + 1) : annotation;
    }

    private static String stripGeneric(String type) {
        int g = type.indexOf('<');
        return g > 0 ? type.substring(0, g).trim() : type.trim();
    }

    private static String buildArgsText(NodeList<Parameter> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Parameter p : params) {
            if (!first) sb.append(", ");
            // 不带 final / 不带注解简写；保留类型 + 名字
            sb.append(p.getType().asString()).append(" ").append(p.getNameAsString());
            first = false;
        }
        return sb.toString();
    }

    /**
     * 拼装 target_signature：{@code 短类名#methodName(ParamType1, ParamType2)}。
     * 无 dependency 时退回方法名（不应落边）；有类名但参数推不出且 call 有实参时写 {@code 短类名#methodName}（无括号）供 BFS 前缀匹配。
     */
    private String buildTargetSignature(String depType, MethodCallExpr call, JavaSymbolSolver symbolSolver) {
        String methodName = call.getNameAsString();
        if (!StringUtils.hasText(depType)) {
            return methodName;
        }
        String shortClass = stripPackageName(depType);
        String paramTypes = resolveTargetParamTypes(call, symbolSolver);
        if (paramTypes != null) {
            return shortClass + "#" + methodName + "(" + paramTypes + ")";
        }
        if (call.getArguments() == null || call.getArguments().isEmpty()) {
            return shortClass + "#" + methodName + "()";
        }
        log.debug("target 参数类型未解析，写无括号签名供 BFS 前缀匹配: {}#{}", shortClass, methodName);
        return shortClass + "#" + methodName;
    }

    /**
     * @return 参数类型列表（逗号分隔、无空格偏好与历史一致用 ", "）；空串表示无参；null 表示未能解析
     */
    private String resolveTargetParamTypes(MethodCallExpr call, JavaSymbolSolver symbolSolver) {
        if (symbolSolver != null) {
            try {
                ResolvedMethodDeclaration resolved = call.resolve();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < resolved.getNumberOfParams(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(simplifyResolvedType(resolved.getParam(i).getType()));
                }
                return sb.toString();
            } catch (Exception ignored) {
                // fall through to argument inference
            }
        }
        return inferParamTypesFromArguments(call, symbolSolver);
    }

    private String inferParamTypesFromArguments(MethodCallExpr call, JavaSymbolSolver symbolSolver) {
        if (call.getArguments() == null) {
            return "";
        }
        if (call.getArguments().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < call.getArguments().size(); i++) {
            Expression arg = call.getArguments().get(i);
            String type = inferExpressionType(arg, symbolSolver);
            if (type == null) {
                return null;
            }
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(type);
        }
        return sb.toString();
    }

    private String inferExpressionType(Expression arg, JavaSymbolSolver symbolSolver) {
        if (arg == null) {
            return null;
        }
        if (symbolSolver != null) {
            try {
                ResolvedType resolved = symbolSolver.calculateType(arg);
                return simplifyResolvedType(resolved);
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (arg.isStringLiteralExpr()) {
            return "String";
        }
        if (arg.isBooleanLiteralExpr()) {
            return "boolean";
        }
        if (arg.isCharLiteralExpr()) {
            return "char";
        }
        if (arg.isIntegerLiteralExpr()) {
            return "int";
        }
        if (arg.isLongLiteralExpr()) {
            return "long";
        }
        if (arg.isDoubleLiteralExpr()) {
            return "double";
        }
        if (arg.isNullLiteralExpr()) {
            return null;
        }
        return null;
    }

    private static String simplifyResolvedType(ResolvedType type) {
        if (type == null) {
            return "Object";
        }
        if (type.isPrimitive()) {
            return type.asPrimitive().describe();
        }
        if (type.isArray()) {
            return simplifyResolvedType(type.asArrayType().getComponentType()) + "[]";
        }
        if (type.isReferenceType()) {
            String qname = type.asReferenceType().getQualifiedName();
            return stripPackageName(stripGeneric(qname));
        }
        return stripPackageName(stripGeneric(type.describe()));
    }

    private static String stripPackageName(String fqOrShort) {
        if (fqOrShort == null) {
            return null;
        }
        String t = fqOrShort.trim();
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private static boolean isSimpleValueType(String type) {
        return List.of(
                "String", "Integer", "Long", "Boolean", "Double", "Float", "BigDecimal",
                "LocalDate", "LocalDateTime",
                // 原生小写（防御）
                "int", "long", "boolean", "double", "float", "byte", "short", "char"
        ).contains(type);
    }

    // -------- Phase 2/3: Symbol Solver 装配与缓存 --------

    /** 项目标记根 → JavaSymbolSolver 缓存。key = 项目标记目录绝对路径。 */
    private static final ConcurrentHashMap<String, JavaSymbolSolver> SYMBOL_SOLVER_CACHE = new ConcurrentHashMap<>();

    /**
     * Phase 3：项目标记根 → subtype 索引。
     * key 同时包含 parent FQ 与 parent 短类名，value = 具象实现 FQ 列表（多实现全部保留）。
     */
    private static final ConcurrentHashMap<String, Map<String, List<String>>> SUBTYPE_INDEX_CACHE = new ConcurrentHashMap<>();

    /** 项目标记（用于源根探测）：.git / Maven pom / Gradle */
    private static final List<String> PROJECT_MARKERS = List.of(
            ".git", "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
    );

    private static final List<String> MODULE_MARKERS = List.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
    );

    /** 扫描多模块时限制深度，避免超大 monorepo 拖垮索引。 */
    private static final int MODULE_WALK_MAX_DEPTH = 8;

    /** Phase 3 项目上下文：某个项目标记根下的 SymbolSolver + subtype 索引 */
    static final class ProjectContext {
        final File projectRoot;
        final JavaSymbolSolver symbolSolver;
        final Map<String, List<String>> subtypeIndex;
        ProjectContext(File projectRoot, JavaSymbolSolver symbolSolver, Map<String, List<String>> subtypeIndex) {
            this.projectRoot = projectRoot;
            this.symbolSolver = symbolSolver;
            this.subtypeIndex = subtypeIndex;
        }
    }

    /**
     * 解析文件路径向上找项目标记根。返回 null 表示裸文件（无法解析）。
     *
     * <p>严格策略：必须撞见 .git / pom.xml / build.gradle 等项目级标记才算"项目"。
     * 没找到就返 null——避免对一个非项目目录（甚至 C:\）跑 Files.walk 时撞到系统保留目录
     * （Windows 上的 $Recycle.Bin 等）抛 AccessDeniedException。</p>
     */
    private File discoverSourceRoot(File file) {
        File current = file.getAbsoluteFile().getParentFile();
        while (current != null) {
            for (String marker : PROJECT_MARKERS) {
                if (new File(current, marker).exists()) {
                    return current;
                }
            }
            current = current.getParentFile();
        }
        return null;
    }

    /**
     * 在项目标记根下发现 Java 源码根（包路径的真实起点）。
     * <ul>
     *   <li>Maven/Gradle：各模块的 {@code src/main/java}</li>
     *   <li>多模块：扫描子目录中带 pom/build 标记的模块</li>
     *   <li>兜底：无标准目录时退回项目根本身（兼容单测扁平 {@code com/xxx} 布局）</li>
     * </ul>
     */
    private List<File> discoverJavaSourceRoots(File projectRoot) {
        LinkedHashSet<File> javaRoots = new LinkedHashSet<>();
        LinkedHashSet<File> modules = new LinkedHashSet<>();
        modules.add(projectRoot);
        collectModuleDirs(projectRoot, modules);
        for (File module : modules) {
            File mavenJava = new File(module, "src/main/java");
            if (mavenJava.isDirectory()) {
                javaRoots.add(mavenJava);
            }
        }
        if (javaRoots.isEmpty()) {
            javaRoots.add(projectRoot);
        }
        return new ArrayList<>(javaRoots);
    }

    /** 收集项目根下带构建标记的模块目录（含自身）。 */
    private void collectModuleDirs(File projectRoot, Set<File> out) {
        Path rootPath = projectRoot.toPath();
        try (Stream<Path> stream = Files.walk(rootPath, MODULE_WALK_MAX_DEPTH)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return MODULE_MARKERS.contains(name);
                    })
                    .filter(p -> {
                        String norm = p.toString().replace('\\', '/');
                        return !norm.contains("/target/")
                                && !norm.contains("/.git/")
                                && !norm.contains("/node_modules/");
                    })
                    .forEach(p -> {
                        File dir = p.getParent() == null ? null : p.getParent().toFile();
                        if (dir != null) {
                            out.add(dir);
                        }
                    });
        } catch (IOException ignored) {
            // 扫描失败时仅保留已加入的 projectRoot
        }
    }

    /**
     * 获取（或创建）绑定到当前文件项目标记根的 ProjectContext：含符号求解器 + subtype 索引。
     * 源根不存在时返回 null，下游走"声明类型"路径，不抛异常。
     */
    private ProjectContext acquireProjectContext(File file) {
        File root = discoverSourceRoot(file);
        if (root == null) return null;
        String key = root.getAbsolutePath();
        JavaSymbolSolver symbolSolver = SYMBOL_SOLVER_CACHE.computeIfAbsent(key, k -> {
            CombinedTypeSolver combined = new CombinedTypeSolver();
            combined.add(new ReflectionTypeSolver());
            JavaSymbolSolver ss = new JavaSymbolSolver(combined);
            ParserConfiguration parserConfig = JavaParserLanguageConfig.apply(
                    new ParserConfiguration().setSymbolResolver(ss));
            // TypeSolver 必须挂在「包根」上：真实仓是 src/main/java，不是 pom 所在项目根
            for (File javaRoot : discoverJavaSourceRoots(root)) {
                combined.add(new JavaParserTypeSolver(javaRoot, parserConfig));
            }
            return ss;
        });
        Map<String, List<String>> subtypeIndex = SUBTYPE_INDEX_CACHE.computeIfAbsent(key, k -> {
            JavaSymbolSolver ss = SYMBOL_SOLVER_CACHE.get(k);
            return buildSubtypeIndex(root, ss);
        });
        int solverSize = SYMBOL_SOLVER_CACHE.size();
        int subtypeSize = SUBTYPE_INDEX_CACHE.size();
        if (solverSize >= 8 || subtypeSize >= 8) {
            log.warn("AstJavaParser static caches growing: {}", globalCacheStatsSummary());
        }
        return new ProjectContext(root, symbolSolver, subtypeIndex);
    }

    /**
     * 用项目 SymbolSolver 解析文件；无上下文时退回 StaticJavaParser。
     * CU 必须挂上 SymbolResolver，否则 Phase 2 calculateType / toResolvedType 恒失败。
     */
    private CompilationUnit parseCompilationUnit(File file, ProjectContext ctx) throws IOException {
        if (ctx == null || ctx.symbolSolver == null) {
            return StaticJavaParser.parse(file);
        }
        ParserConfiguration cfg = JavaParserLanguageConfig.apply(
                new ParserConfiguration().setSymbolResolver(ctx.symbolSolver));
        ParseResult<CompilationUnit> result = new JavaParser(cfg).parse(file);
        if (result.getResult().isPresent()) {
            return result.getResult().get();
        }
        throw new ParseProblemException(result.getProblems());
    }

    /**
     * Phase 3：构建项目级 subtype 索引。
     * 扫描各 Java 源码根下 .java，记录具象类 extends/implements 的父类型 → 子类 FQ。
     * 索引同时以 parent FQ 与 parent 短类名建键，供短名 dependency_name 回查。
     */
    private Map<String, List<String>> buildSubtypeIndex(File projectRoot, JavaSymbolSolver symbolSolver) {
        Map<String, List<String>> index = new ConcurrentHashMap<>();
        for (File javaRoot : discoverJavaSourceRoots(projectRoot)) {
            Path rootPath = javaRoot.toPath();
            try (Stream<Path> stream = Files.walk(rootPath)) {
                stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.toString().replace('\\', '/').contains("/test/"))
                        .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                        .forEach(p -> indexOneFile(p, symbolSolver, index));
            } catch (IOException ex) {
                // 单源根失败继续其它源根
            }
        }
        return index;
    }

    /** 单文件 subtype 收集。错就跳过，不抛。 */
    private void indexOneFile(Path p, JavaSymbolSolver symbolSolver, Map<String, List<String>> index) {
        try {
            CompilationUnit cu = parseCompilationUnit(p.toFile(),
                    new ProjectContext(null, symbolSolver, null));
            String pkg = cu.getPackageDeclaration().map(d -> d.getNameAsString()).orElse("");
            for (TypeDeclaration<?> td : cu.getTypes()) {
                if (!(td instanceof ClassOrInterfaceDeclaration)) continue;
                ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) td;
                // 只索引"具象"类（非接口、非 abstract），避免返回抽象基类作为 caller 真正调用的对象
                if (cid.isInterface()) continue;
                if (cid.hasModifier(Modifier.Keyword.ABSTRACT)) continue;

                String classFqcn = pkg.isEmpty() ? td.getNameAsString() : pkg + "." + td.getNameAsString();

                for (ClassOrInterfaceType parent : cid.getExtendedTypes()) {
                    putSubtype(index, resolveTypeFqcn(parent, pkg, cu, symbolSolver), classFqcn);
                }
                for (ClassOrInterfaceType parent : cid.getImplementedTypes()) {
                    putSubtype(index, resolveTypeFqcn(parent, pkg, cu, symbolSolver), classFqcn);
                }
            }
        } catch (Exception ex) {
            // 单文件解析失败或符号解析失败，安静跳过
        }
    }

    /** 父类型 FQ + 短名双键写入；多实现全部追加，不去重为单值。 */
    private void putSubtype(Map<String, List<String>> index, String parentKey, String classFqcn) {
        if (!StringUtils.hasText(parentKey) || !StringUtils.hasText(classFqcn)) {
            return;
        }
        addSubtypeValue(index, parentKey, classFqcn);
        String simple = stripPackageName(parentKey);
        if (StringUtils.hasText(simple) && !simple.equals(parentKey)) {
            addSubtypeValue(index, simple, classFqcn);
        }
    }

    private void addSubtypeValue(Map<String, List<String>> index, String key, String classFqcn) {
        List<String> list = index.computeIfAbsent(key, k -> new ArrayList<>());
        if (!list.contains(classFqcn)) {
            list.add(classFqcn);
        }
    }

    /**
     * 解析 ClassOrInterfaceType 到 FQ 名。
     * 顺序：已限定名 → symbolSolver → import → 同包简单名 → 短名（供双键索引）。
     * <p>不再用「实现类所在包 + 接口简单名」硬拼，避免跨包 implements 写入错误 FQ。</p>
     */
    private String resolveTypeFqcn(ClassOrInterfaceType t, String contextPkg,
                                   CompilationUnit cu, JavaSymbolSolver symbolSolver) {
        if (t == null) {
            return null;
        }
        if (t.getScope().isPresent()) {
            String qualified = t.asString();
            if (StringUtils.hasText(qualified)) {
                return qualified;
            }
        }
        if (symbolSolver != null) {
            try {
                ResolvedReferenceTypeDeclaration decl =
                        symbolSolver.toResolvedType(t, ResolvedReferenceTypeDeclaration.class);
                if (decl != null) {
                    String qn = decl.getQualifiedName();
                    if (StringUtils.hasText(qn) && qn.contains(".")) {
                        return qn;
                    }
                }
            } catch (Exception ignored) {
                // 解析失败：fallback
            }
        }
        String simple = t.getNameAsString();
        String fromImport = resolveSimpleNameViaImports(simple, cu);
        if (fromImport != null) {
            return fromImport;
        }
        if (StringUtils.hasText(contextPkg)) {
            return contextPkg + "." + simple;
        }
        return simple;
    }

    /**
     * 给定声明类型（FQ 或短名），找出项目内所有候选子类（多实现全部返回），逗号分隔。
     * 先精确 key，再短类名 key；两边结果合并去重。
     */
    private String findCandidatesForFqcn(String declaredFqcn, Map<String, List<String>> subtypeIndex) {
        if (subtypeIndex == null || !StringUtils.hasText(declaredFqcn)) {
            return null;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        List<String> exact = subtypeIndex.get(declaredFqcn);
        if (exact != null) {
            merged.addAll(exact);
        }
        String simple = stripPackageName(declaredFqcn);
        if (StringUtils.hasText(simple) && !simple.equals(declaredFqcn)) {
            List<String> bySimple = subtypeIndex.get(simple);
            if (bySimple != null) {
                merged.addAll(bySimple);
            }
        }
        if (merged.isEmpty()) {
            return null;
        }
        return String.join(",", merged);
    }

    /** 把声明类型短名升为 FQ：显式 import → 同包；已是 FQ 则原样返回。 */
    private String upgradeDeclaredTypeName(String declared, String contextPkg, CompilationUnit cu) {
        if (!StringUtils.hasText(declared)) {
            return null;
        }
        if (declared.contains(".")) {
            return declared;
        }
        String fromImport = resolveSimpleNameViaImports(declared, cu);
        if (fromImport != null) {
            return fromImport;
        }
        if (StringUtils.hasText(contextPkg)) {
            return contextPkg + "." + declared;
        }
        return null;
    }

    private String resolveSimpleNameViaImports(String simpleName, CompilationUnit cu) {
        if (!StringUtils.hasText(simpleName) || cu == null) {
            return null;
        }
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk() || imp.isStatic()) {
                continue;
            }
            String name = imp.getNameAsString();
            if (!StringUtils.hasText(name)) {
                continue;
            }
            if (name.equals(simpleName) || name.endsWith("." + simpleName)) {
                return name;
            }
        }
        return null;
    }

    /**
     * 尝试解析 receiver 表达式（一个 NameExpr / 字段引用）的类型，返回 FQ 名；解析失败返回 null。
     * 设计原则：纯静默失败——solver 不胜任时就用声明类型，不抛错，避免打断流水线。
     */
    private String tryResolveReceiverType(Expression scope, JavaSymbolSolver symbolSolver) {
        if (symbolSolver == null) return null;
        try {
            var resolved = symbolSolver.calculateType(scope);
            if (resolved.isReferenceType()) {
                String fqcn = resolved.asReferenceType().getQualifiedName();
                if (StringUtils.hasText(fqcn) && fqcn.contains(".")) {
                    return fqcn;
                }
            }
            return null;
        } catch (Exception ex) {
            // 解不到（如 lambda 内部 / 复杂表达式 / 跨 JAR 类型），安静回退
            return null;
        }
    }

    /** 方法体归一化空白后取 SHA-256 前 16 位十六进制，供入口 DIFF 内容变更比较 */
    private static String hashMethodBody(String bodySource) {
        if (bodySource == null || bodySource.isEmpty()) {
            return null;
        }
        try {
            String normalized = bodySource.replaceAll("\\s+", " ").trim();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", dig[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
