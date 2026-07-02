package com.company.codeinsight.modules.parser.service.impl;

import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodCallInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.SqlReference;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
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
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

            // 1. 包名
            cu.getPackageDeclaration().ifPresent(p -> info.setPackageName(p.getNameAsString()));

            // 2. 顶层类型声明（class / interface / enum / @interface / record）。
            //    注意：getPrimaryType() 仅在文件名与类名一致或类为 public 时返回；
            //    真实场景（gtest 文件名 ≠ 类名，或单文件多类）会用 getTypes() 全集遍历。
            TypeDeclaration<?> td = pickTopLevelType(cu);
            if (td != null) {
                if (td instanceof ClassOrInterfaceDeclaration) {
                    fillFromClass((ClassOrInterfaceDeclaration) td, info);
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
        ParsedClassInfo info = parseFile(current);
        if (info != null && info.getClassName() != null) {
            String rel = root.toURI().relativize(current.toURI()).getPath();
            info.setSourceRelativePath(rel.replace('\\', '/'));
            out.add(info);
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

    private void fillFromClass(ClassOrInterfaceDeclaration cid, ParsedClassInfo info) {
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
            attachMethodBody(md, mi, info, depVars);
            info.getMethods().add(mi);
        }

        // 收集工作已在此方法体内完成（attachMethodBody 内同时遍历调用链与 SQL 字面量）
    }

    /** 抽出方法级 MethodCallExpr，caller = 当前方法名，target = 方法名，dependencyName = depVars[receiver] */
    private void attachMethodBody(MethodDeclaration md, MethodInfo mi, ParsedClassInfo info,
                                 Map<String, String> depVars) {
        if (md.getBody().isEmpty()) return;
        BlockStmt body = md.getBody().get();
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            MethodCallInfo ci = new MethodCallInfo();
            ci.setCallerMethod(mi.getName());
            ci.setCallerSignature(mi.getName() + "(" + (mi.getArguments() == null ? "" : mi.getArguments()) + ")");
            ci.setTargetMethod(call.getNameAsString());
            // MVP 简化：targetSignature 仅为方法名（不带参数）
            ci.setTargetSignature(call.getNameAsString());

            String depType = null;
            String receiver = null;
            if (call.getScope().isPresent()) {
                Expression scope = call.getScope().get();
                String scopeText = scope.toString().trim();
                if (depVars.containsKey(scopeText)) {
                    receiver = scopeText;
                    depType = depVars.get(scopeText);
                }
            }
            ci.setDependencyName(depType == null ? "" : depType);
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

    private String extractMappingFromAnnotations(NodeList<AnnotationExpr> annotations, String name) {
        for (AnnotationExpr ann : annotations) {
            if (!name.equalsIgnoreCase(simpleName(ann.getNameAsString()))) continue;
            // 单 value 形式 @XxxMapping("/foo")
            if (ann.isNormalAnnotationExpr()) {
                return extractSingleStringArg(ann.toString());
            }
            // @XxxMapping(value = "/foo") 或 @XxxMapping(path = "/foo")
            String text = ann.toString();
            int eq = text.indexOf('=');
            if (eq > 0) {
                int q1 = text.indexOf('"', eq);
                int q2 = text.indexOf('"', q1 + 1);
                if (q1 > 0 && q2 > q1) return text.substring(q1 + 1, q2);
            }
        }
        return null;
    }

    private String extractSingleStringArg(String annText) {
        int p = annText.indexOf('(');
        int q1 = annText.indexOf('"', p + 1);
        int q2 = annText.indexOf('"', q1 + 1);
        if (q1 > 0 && q2 > q1) return annText.substring(q1 + 1, q2);
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

    private static boolean isSimpleValueType(String type) {
        return List.of(
                "String", "Integer", "Long", "Boolean", "Double", "Float", "BigDecimal",
                "LocalDate", "LocalDateTime",
                // 原生小写（防御）
                "int", "long", "boolean", "double", "float", "byte", "short", "char"
        ).contains(type);
    }
}
