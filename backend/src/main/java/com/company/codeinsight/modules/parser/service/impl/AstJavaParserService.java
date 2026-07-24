package com.company.codeinsight.modules.parser.service.impl;

import com.company.codeinsight.modules.parser.config.JavaParserLanguageConfig;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodCallInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.SqlReference;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            CompilationUnit cu = StaticJavaParser.parse(file);
            ParsedClassInfo info = new ParsedClassInfo();
            info.setType("UNKNOWN");

            // 0. Phase 2/3：探测项目源根并装配 symbol solver（用于把声明类型升级为解析类型）+ subtype 索引
            //    无项目标记（裸文件 / 单元测试时）返回 null，走现有"声明类型"路径
            ProjectContext ctx = acquireProjectContext(file);
            JavaSymbolSolver symbolSolver = ctx == null ? null : ctx.symbolSolver;

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
            attachMethodBody(md, mi, info, depVars, symbolSolver,
                    ctx == null ? null : ctx.subtypeIndex);
            info.getMethods().add(mi);
        }

        // 收集工作已在此方法体内完成（attachMethodBody 内同时遍历调用链与 SQL 字面量）
    }

    /**
     * 抽出方法级 MethodCallExpr：caller 签名、dependency、完整 target 签名（短类名#method(ParamTypes)）。
     * <p>Phase 2：symbolSolver 升级 receiver 声明类型为 FQ；Phase 3：subtypeIndex 填多态候选。
     * target 参数优先用 resolve() 声明类型，失败再按实参推类型。
     */
    private void attachMethodBody(MethodDeclaration md, MethodInfo mi, ParsedClassInfo info,
                                 Map<String, String> depVars,
                                 JavaSymbolSolver symbolSolver,
                                 Map<String, List<String>> subtypeIndex) {
        if (md.getBody().isEmpty()) return;
        BlockStmt body = md.getBody().get();
        mi.setBodyHash(hashMethodBody(body.toString()));
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
                    }
                    // Phase 3：把声明类型查表拿到该项目内的所有具体子类候选
                    ci.setDependencyCandidates(findCandidatesForFqcn(depType, subtypeIndex));
                }
            }
            ci.setDependencyName(depType == null ? "" : depType);
            ci.setTargetSignature(buildTargetSignature(depType, call, symbolSolver));
            ci.setExpression((receiver == null ? "this" : receiver) + "." + call.getNameAsString() + "()");
            if (call.getRange().isPresent()) {
                ci.setLineNumber(call.getRange().get().begin.line);
            }
            // 只在 receiver 是已知依赖时记入；与 regex 版等价
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

    /** 项目源根 → JavaSymbolSolver 缓存。源根以绝对路径为 key。 */
    private static final ConcurrentHashMap<String, JavaSymbolSolver> SYMBOL_SOLVER_CACHE = new ConcurrentHashMap<>();

    /** Phase 3：项目源根 → subtype 索引（parent FQ → [concrete impl FQ, ...]）。 */
    private static final ConcurrentHashMap<String, Map<String, List<String>>> SUBTYPE_INDEX_CACHE = new ConcurrentHashMap<>();

    /** 项目标记（用于源根探测）：.git / Maven pom / Gradle */
    private static final List<String> PROJECT_MARKERS = List.of(
            ".git", "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
    );

    /** Phase 3 项目上下文：某个源根下的所有 Phase 2/3 信息 */
    static final class ProjectContext {
        final File sourceRoot;
        final JavaSymbolSolver symbolSolver;
        final Map<String, List<String>> subtypeIndex;
        ProjectContext(File sourceRoot, JavaSymbolSolver symbolSolver, Map<String, List<String>> subtypeIndex) {
            this.sourceRoot = sourceRoot;
            this.symbolSolver = symbolSolver;
            this.subtypeIndex = subtypeIndex;
        }
    }

    /**
     * 解析文件路径向上找项目源根。返回 null 表示裸文件（无法解析）。
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
     * 获取（或创建）一个绑定到当前文件源根的 ProjectContext：含符号求解器 + subtype 索引。
     * 源根不存在时返回 null，下游走"声明类型"路径，不抛异常。
     */
    private ProjectContext acquireProjectContext(File file) {
        File root = discoverSourceRoot(file);
        if (root == null) return null;
        String key = root.getAbsolutePath();
        // 用 SYMBOL_SOLVER_CACHE 作为统一入口，subtype 索引同步构建
        JavaSymbolSolver symbolSolver = SYMBOL_SOLVER_CACHE.computeIfAbsent(key, k -> {
            CombinedTypeSolver combined = new CombinedTypeSolver();
            combined.add(new ReflectionTypeSolver());
            JavaSymbolSolver ss = new JavaSymbolSolver(combined);
            ParserConfiguration parserConfig = JavaParserLanguageConfig.apply(
                    new ParserConfiguration().setSymbolResolver(ss));
            JavaParserTypeSolver jpts = new JavaParserTypeSolver(root, parserConfig);
            combined.add(jpts);
            return ss;
        });
        // Phase 3 subtype 索引懒构建
        Map<String, List<String>> subtypeIndex = SUBTYPE_INDEX_CACHE.computeIfAbsent(key, k -> {
            // 取上面刚 build/取出的 solver 来跑 subtype 索引
            JavaSymbolSolver ss = SYMBOL_SOLVER_CACHE.get(k);
            return buildSubtypeIndex(root, ss);
        });
        return new ProjectContext(root, symbolSolver, subtypeIndex);
    }

    /**
     * Phase 3：构建项目级 subtype 索引。
     * 扫描 source root 下所有 .java 文件，记录每个具体（非接口、非抽象）类 → 它 extends/implements 的父类型 FQ。
     * 反向索引：parent FQ → [concrete child FQ, ...]，用于把声明类型为接口的字段扩充成候选子类集。
     *
     * <p>复杂度 O(N)，N = 项目源文件数。10k 文件约几秒。索引按 source root 缓存，第二次调用走缓存。</p>
     */
    private Map<String, List<String>> buildSubtypeIndex(File root, JavaSymbolSolver symbolSolver) {
        Map<String, List<String>> index = new ConcurrentHashMap<>();
        Path rootPath = root.toPath();
        try (Stream<Path> stream = Files.walk(rootPath)) {
            stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                // 跳过测试目录
                .filter(p -> !p.toString().replace('\\', '/').contains("/test/"))
                .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                .forEach(p -> indexOneFile(p, root, symbolSolver, index));
        } catch (IOException ex) {
            // 走读失败 → 返回空索引
            return index;
        }
        return index;
    }

    /** 单文件 subtype 收集。错就跳过，不抛。 */
    private void indexOneFile(Path p, File root, JavaSymbolSolver symbolSolver, Map<String, List<String>> index) {
        try {
            JavaParserLanguageConfig.ensureStaticJavaParserConfigured();
            CompilationUnit cu = StaticJavaParser.parse(p.toFile());
            String pkg = cu.getPackageDeclaration().map(d -> d.getNameAsString()).orElse("");
            for (TypeDeclaration<?> td : cu.getTypes()) {
                if (!(td instanceof ClassOrInterfaceDeclaration)) continue;
                ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) td;
                // 只索引"具象"类（非接口、非 abstract），避免返回抽象基类作为 caller 真正调用的对象
                if (cid.isInterface()) continue;
                if (cid.hasModifier(Modifier.Keyword.ABSTRACT)) continue;

                String classFqcn = pkg.isEmpty() ? td.getNameAsString() : pkg + "." + td.getNameAsString();

                for (ClassOrInterfaceType parent : cid.getExtendedTypes()) {
                    String parentFqcn = resolveTypeFqcn(parent, pkg, symbolSolver);
                    if (parentFqcn != null) {
                        index.computeIfAbsent(parentFqcn, k -> new ArrayList<>()).add(classFqcn);
                    }
                }
                for (ClassOrInterfaceType parent : cid.getImplementedTypes()) {
                    String parentFqcn = resolveTypeFqcn(parent, pkg, symbolSolver);
                    if (parentFqcn != null) {
                        index.computeIfAbsent(parentFqcn, k -> new ArrayList<>()).add(classFqcn);
                    }
                }
            }
        } catch (Exception ex) {
            // 单文件解析失败或符号解析失败，安静跳过
        }
    }

    /**
     * 解析 ClassOrInterfaceType 到 FQ 名。优先走 symbolSolver.toResolvedType()，
     * 拿不到再退到包路径 + 简单名。
     */
    private String resolveTypeFqcn(ClassOrInterfaceType t, String contextPkg, JavaSymbolSolver symbolSolver) {
        try {
            ResolvedReferenceTypeDeclaration decl = symbolSolver.toResolvedType(t, ResolvedReferenceTypeDeclaration.class);
            if (decl != null) {
                String qn = decl.getQualifiedName();
                if (StringUtils.hasText(qn) && qn.contains(".")) return qn;
            }
        } catch (Exception ignored) {
            // 解析失败：fallback
        }
        String simple = t.getNameAsString();
        if (!StringUtils.hasText(contextPkg)) return simple;
        return contextPkg + "." + simple;
    }

    /**
     * 给定声明类型 FQ，找出项目内所有候选子类（多态候选集），返回逗号分隔的 FQ 列表。
     * 无候选 / 源根缺失 / depType 无值时返回 null。
     */
    private String findCandidatesForFqcn(String declaredFqcn, Map<String, List<String>> subtypeIndex) {
        if (subtypeIndex == null || !StringUtils.hasText(declaredFqcn)) return null;
        List<String> candidates = subtypeIndex.get(declaredFqcn);
        if (candidates == null || candidates.isEmpty()) return null;
        return String.join(",", candidates);
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
