package com.company.codeinsight.modules.parser.service.impl;

import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodCallInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodInfo;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo.SqlReference;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于正则表达式的 Java 静态解析实现（REGEX 引擎）。
 * 使用轻量级正则与括号匹配，对 Java 源文件做词法级别的近似扫描，
 * 提取包名、注解、组件角色、HTTP 路由、方法签名、内部方法调用链及嵌入 SQL 等结构。
 *
 * <p>作为 AST 引擎的兜底实现：AST 解析抛 ParseProblemException 时，FallbackJavaParserService 会转调本类
 * 保证流水线不中断。本类不直接暴露为 Spring Bean，由 ParserEngineConfig 按配置装配。</p>
 */
@Slf4j
public class RegexJavaParserService implements JavaParserService {

    /**
     * 任务级解析结果缓存，避免 Stage 2（方法调用链）和 Stage 3（代码切片）对同一文件重复 AST 解析。
     * key = task_{taskId}/relativePath（从文件绝对路径中提取，与 temp_repos/ 和 storage/ 前缀无关）
     */
    private final ConcurrentHashMap<String, ParsedClassInfo> parseCache = new ConcurrentHashMap<>();

    /** 从文件绝对路径中提取缓存 key：task_{taskId}/relativePath，去掉前缀差异 */
    private static String cacheKey(File file) {
        String abs = file.getAbsolutePath().replace('\\', '/');
        int idx = abs.indexOf("/task_");
        return idx >= 0 ? abs.substring(idx + 1) : abs;
    }

    // 正则表达式：解析包名定义
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w.]+);");
    // 正则表达式：解析类/接口/枚举/注解的声明头结构
    private static final Pattern CLASS_PATTERN = Pattern.compile("(?:public\\s+)?(?:abstract\\s+|final\\s+)?(@interface|class|interface|enum)\\s+(\\w+)");
    // 正则表达式：提取代码中声明的注解
    private static final Pattern ANNOTATION_PATTERN = Pattern.compile("@([A-Za-z][\\w.]*)");
    // 正则表达式：匹配常见的 Spring REST 控制器 Mapping 注解路由及参数
    private static final Pattern REQ_MAPPING_PATTERN = Pattern.compile("@RequestMapping\\s*\\(([^)]*)\\)");
    private static final Pattern GET_MAPPING_PATTERN = Pattern.compile("@GetMapping\\s*\\(([^)]*)\\)");
    private static final Pattern POST_MAPPING_PATTERN = Pattern.compile("@PostMapping\\s*\\(([^)]*)\\)");
    private static final Pattern PUT_MAPPING_PATTERN = Pattern.compile("@PutMapping\\s*\\(([^)]*)\\)");
    private static final Pattern DELETE_MAPPING_PATTERN = Pattern.compile("@DeleteMapping\\s*\\(([^)]*)\\)");
    // 正则表达式：识别 MyBatis Plus 或 JPA 的 @Table 数据库表注解定义
    private static final Pattern TABLE_PATTERN = Pattern.compile("@Table\\s*\\([^)]*?(?:name\\s*=\\s*)?\"([^\"]+)\"");
    // 正则表达式：解析标准 Java 方法的修饰符、返回值类型、方法名和参数列表
    private static final Pattern METHOD_PATTERN = Pattern.compile("(?:public|protected|private|static|final|synchronized|\\s)+\\s+([\\w<>\\[\\],.?\\s]+)\\s+(\\w+)\\s*\\(([^)]*)\\)");
    // 正则表达式：匹配构造器声明。构造器名（=类名）与左括号之间没有空格，
    // 故 METHOD_PATTERN（要求类型与名称间至少一个空白）不会命中，需独立匹配
    private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile("(?:public|protected|private)\\s+(\\w+)\\s*\\(([^)]*)\\)");
    // 正则表达式：匹配 extends 子句中的父类 FQ
    private static final Pattern EXTENDS_PATTERN = Pattern.compile("\\bextends\\s+([\\w.]+)");
    // 正则表达式：匹配 implements 子句中的接口列表（支持 extends 后置或 { 结束）
    private static final Pattern IMPLEMENTS_PATTERN = Pattern.compile("\\bimplements\\s+([\\w.,\\s]+?)(?:\\bextends\\b|\\{|\\n|$)");
    // 正则表达式：匹配类内部的依赖字段注入（例如 private UserService userService;）
    private static final Pattern FIELD_DEPENDENCY_PATTERN = Pattern.compile("(?:private|protected|public)\\s+(?:final\\s+)?([A-Z][\\w]*(?:<[^>]+>)?)\\s+(\\w+)\\s*(?:=|;)");
    // 正则表达式：简单捕获方法体内部的成员对象方法调用（例如 service.doSomething(...)）
    private static final Pattern METHOD_CALL_PATTERN = Pattern.compile("\\b(\\w+)\\.(\\w+)\\s*\\(");
    // 正则表达式：SQL 语句中各种数据表关联词的提取 (FROM, JOIN, INTO, UPDATE)
    private static final Pattern SQL_FROM_PATTERN = Pattern.compile("(?i)\\bFROM\\s+([`\"']?[\\w.]+[`\"']?)");
    private static final Pattern SQL_JOIN_PATTERN = Pattern.compile("(?i)\\bJOIN\\s+([`\"']?[\\w.]+[`\"']?)");
    private static final Pattern SQL_INTO_PATTERN = Pattern.compile("(?i)\\bINTO\\s+([`\"']?[\\w.]+[`\"']?)");
    private static final Pattern SQL_UPDATE_PATTERN = Pattern.compile("(?i)\\bUPDATE\\s+([`\"']?[\\w.]+[`\"']?)");

    /**
     * 单个 Java 文件的静态解析核心逻辑
     *
     * @param file Java 源文件对象
     * @return 解析后的结构化元数据 ParsedClassInfo
     */
    @Override
    public ParsedClassInfo parseFile(File file) {
        if (file == null || !file.exists() || !file.getName().endsWith(".java")) {
            return null;
        }

        // 任务级缓存：Stage 2 解析后 Stage 3 直接命中，避免同文件 AST 重复解析
        String key = cacheKey(file);
        ParsedClassInfo cached = parseCache.get(key);
        if (cached != null) {
            return cached;
        }

        ParsedClassInfo classInfo = new ParsedClassInfo();
        classInfo.setType("UNKNOWN");
        // 缓存类内部依赖的变量名与对应的类类型映射 (用于判断方法调用来源)
        Map<String, String> dependencyVariables = new LinkedHashMap<>();

        try {
            List<String> lines = Files.readAllLines(file.toPath());
            String pendingMappingUrl = null;
            String pendingMappingMethod = null;
            String currentMethod = null;
            String currentMethodArgs = null;  // 阶段 2 用：当前方法的入参签名（用于拼 callerSignature）
            int methodBraceDepth = 0;
            boolean methodBraceStarted = false;

            // 逐行进行词法解析
            for (int i = 0; i < lines.size(); i++) {
                String rawLine = lines.get(i);
                String line = rawLine.trim();
                // 忽略空行和常见的单行、多行注释行
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) {
                    continue;
                }

                // 1. 匹配包名
                Matcher pkgMatcher = PACKAGE_PATTERN.matcher(line);
                if (pkgMatcher.find()) {
                    classInfo.setPackageName(pkgMatcher.group(1));
                    continue;
                }

                // 2. 收集类上和方法上的注解，并分析其可能的组件类别 (Controller/Service 等)
                collectAnnotations(line, classInfo);
                detectTypeFromAnnotations(line, classInfo);

                // 3. 解析对应的数据库表注解
                Matcher tableMatcher = TABLE_PATTERN.matcher(line);
                if (tableMatcher.find()) {
                    addUnique(cleanIdentifier(tableMatcher.group(1)), classInfo.getTables());
                }

                // 4. 解析控制器路由 Mapping。若在方法外部（类头上），直接设为 RequestMapping 基准路由
                if (line.startsWith("@RequestMapping")) {
                    String value = getMappingUrl(line, REQ_MAPPING_PATTERN);
                    if (currentMethod == null && classInfo.getClassName() == null) {
                        classInfo.setRequestMapping(value);
                    } else {
                        pendingMappingUrl = value;
                        pendingMappingMethod = "ALL";
                    }
                    continue;
                }
                if (line.startsWith("@GetMapping")) {
                    pendingMappingUrl = getMappingUrl(line, GET_MAPPING_PATTERN);
                    pendingMappingMethod = "GET";
                    continue;
                }
                if (line.startsWith("@PostMapping")) {
                    pendingMappingUrl = getMappingUrl(line, POST_MAPPING_PATTERN);
                    pendingMappingMethod = "POST";
                    continue;
                }
                if (line.startsWith("@PutMapping")) {
                    pendingMappingUrl = getMappingUrl(line, PUT_MAPPING_PATTERN);
                    pendingMappingMethod = "PUT";
                    continue;
                }
                if (line.startsWith("@DeleteMapping")) {
                    pendingMappingUrl = getMappingUrl(line, DELETE_MAPPING_PATTERN);
                    pendingMappingMethod = "DELETE";
                    continue;
                }

                // 5. 匹配类声明头（Class / Interface / Enum / Annotation）
                Matcher classMatcher = CLASS_PATTERN.matcher(line);
                if (classMatcher.find() && classInfo.getClassName() == null) {
                    String declarationKind = classMatcher.group(1);
                    classInfo.setClassName(classMatcher.group(2));
                    detectTypeFromDeclaration(classInfo, declarationKind);

                    // 5.1 提取 extends 子句（仅单行声明，多行场景后续接入真实 JavaParser 再升级）
                    Matcher extendsMatcher = EXTENDS_PATTERN.matcher(line);
                    if (extendsMatcher.find()) {
                        classInfo.setExtendsClass(extendsMatcher.group(1));
                    }
                    // 5.2 提取 implements 子句（支持 extends 后置或 { 结束）
                    Matcher implsMatcher = IMPLEMENTS_PATTERN.matcher(line);
                    if (implsMatcher.find()) {
                        for (String item : implsMatcher.group(1).split(",")) {
                            String trimmed = item.trim();
                            if (!trimmed.isEmpty()) {
                                classInfo.getImplementsList().add(trimmed);
                            }
                        }
                    }
                    continue;
                }

                // 6. 收集类的依赖属性与成员变量
                collectDependency(line, classInfo, dependencyVariables);
                
                // 7. 扫描行内可能存在的硬编码 SQL 语句特征
                collectSql(line, classInfo);

                // 8. 匹配类中的方法定义头
                // 8.0 构造器注入识别：把 (paramName → type) 加入 dependencyVariables，
                //     让方法体内的 svc.foo() 这类调用能被 collectMethodCalls 识别为依赖调用，
                //     与 FIELD_DEPENDENCY_PATTERN 等价，覆盖构造器注入场景。
                if (classInfo.getClassName() != null) {
                    Matcher ctorMatcher = CONSTRUCTOR_PATTERN.matcher(line);
                    if (ctorMatcher.find() && classInfo.getClassName().equals(ctorMatcher.group(1))) {
                        collectConstructorDependencies(ctorMatcher.group(2), dependencyVariables, classInfo);
                    }
                }
                Matcher methodMatcher = METHOD_PATTERN.matcher(line);
                // 过滤掉 if/for/while/switch 等结构控制关键字误匹配为方法
                boolean methodStartedOnLine = methodMatcher.find() && !isControlFlow(methodMatcher.group(2));
                if (methodStartedOnLine) {
                    MethodInfo methodInfo = new MethodInfo();
                    methodInfo.setReturnType(methodMatcher.group(1).trim());
                    methodInfo.setName(methodMatcher.group(2).trim());
                    methodInfo.setArguments(methodMatcher.group(3).trim());
                    methodInfo.setRequestMapping(pendingMappingUrl);
                    methodInfo.setHttpMethod(pendingMappingMethod);
                    methodInfo.setStartLine(i + 1);  // 阶段 2 用：方法体起始行
                    classInfo.getMethods().add(methodInfo);

                    // 开启对当前方法内部大括号作用域的深度追踪，用于限制方法内调用的收集范围
                    currentMethod = methodInfo.getName();
                    currentMethodArgs = methodInfo.getArguments();  // 阶段 2 用：保存当前方法参数
                    methodBraceDepth = countChar(line, '{') - countChar(line, '}');
                    methodBraceStarted = line.contains("{");
                    pendingMappingUrl = null;
                    pendingMappingMethod = null;
                }

                // 9. 追踪并分析方法体内的方法调用表达式，记录方法级的链路拓扑
                if (currentMethod != null) {
                    collectMethodCalls(line, i + 1, currentMethod, currentMethodArgs, dependencyVariables, classInfo);
                    if (!methodBraceStarted && line.contains("{")) {
                        methodBraceStarted = true;
                    } else if (!methodStartedOnLine) {
                        methodBraceDepth += countChar(line, '{') - countChar(line, '}');
                    }
                    // 当括号深度归零，说明当前方法的作用域块已解析结束
                    if (methodBraceStarted && methodBraceDepth <= 0) {
                        // 阶段 2 用：写入方法体结束行
                        if (!classInfo.getMethods().isEmpty()) {
                            MethodInfo lastMethod = classInfo.getMethods().get(classInfo.getMethods().size() - 1);
                            if (lastMethod.getName().equals(currentMethod) && lastMethod.getEndLine() == null) {
                                lastMethod.setEndLine(i + 1);
                            }
                        }
                        currentMethod = null;
                        currentMethodArgs = null;
                        methodBraceDepth = 0;
                        methodBraceStarted = false;
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to read Java file: {}", file.getAbsolutePath(), e);
        }

        // 根据类名后缀规则，作为类型检测的兜底猜测
        detectTypeFromName(classInfo);

        // 识别 Java SE 标准入口方法 public static void main(String[] args)
        detectMainMethod(classInfo);

        parseCache.put(key, classInfo);
        return classInfo;
    }

    /**
     * 识别类是否包含 public static void main(String[] args) 标准入口方法。
     * 命中时置 hasMainMethod=true，并在 type 仍为 UNKNOWN 时置为 APPLICATION。
     */
    private void detectMainMethod(ParsedClassInfo classInfo) {
        if (classInfo == null || classInfo.getMethods() == null) {
            return;
        }
        for (MethodInfo m : classInfo.getMethods()) {
            if ("main".equals(m.getName())
                    && "void".equalsIgnoreCase(m.getReturnType() == null ? "" : m.getReturnType().trim())
                    && m.getArguments() != null && m.getArguments().contains("String")) {
                classInfo.setHasMainMethod(true);
                if ("UNKNOWN".equals(classInfo.getType())) {
                    classInfo.setType("APPLICATION");
                }
                return;
            }
        }
    }

    /**
     * 递归遍历扫描文件夹下所有 Java 源文件并进行解析
     *
     * @param directory 目标目录
     * @return 解析完成的类元数据集合
     */
    @Override
    public List<ParsedClassInfo> parseDirectory(File directory) {
        List<ParsedClassInfo> list = new ArrayList<>();
        if (directory == null || !directory.exists()) {
            return list;
        }
        scanAndParse(directory, directory, list);
        return list;
    }

    /**
     * 递归扫描与深度分析的核心辅助方法
     */
    private void scanAndParse(File root, File file, List<ParsedClassInfo> list) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    scanAndParse(root, f, list);
                }
            }
        } else if (file.getName().endsWith(".java")) {
            ParsedClassInfo info = parseFile(file);
            if (info != null && info.getClassName() != null) {
                String rel = root.toURI().relativize(file.toURI()).getPath();
                info.setSourceRelativePath(rel.replace('\\', '/'));
                list.add(info);
            }
        }
    }

    /**
     * 正则匹配并去重收集代码中的所有注解
     */
    private void collectAnnotations(String line, ParsedClassInfo classInfo) {
        Matcher matcher = ANNOTATION_PATTERN.matcher(line);
        while (matcher.find()) {
            addUnique(simpleName(matcher.group(1)), classInfo.getAnnotations());
        }
    }

    /**
     * 根据特有的 Spring/JPA 注解判定当前类的核心类型
     */
    private void detectTypeFromAnnotations(String line, ParsedClassInfo classInfo) {
        if (line.contains("@RestController") || line.contains("@Controller")) {
            classInfo.setType("CONTROLLER");
        } else if (line.contains("@Service") && "UNKNOWN".equals(classInfo.getType())) {
            classInfo.setType("SERVICE");
        } else if (line.contains("@Mapper") && "UNKNOWN".equals(classInfo.getType())) {
            classInfo.setType("MAPPER");
        } else if ((line.contains("@Entity") || line.contains("@Table")) && "UNKNOWN".equals(classInfo.getType())) {
            classInfo.setType("ENTITY");
        } else if (line.contains("@Configuration") && "UNKNOWN".equals(classInfo.getType())) {
            classInfo.setType("CONFIG");
        } else if ((line.contains("@Scheduled") || line.contains("@EnableScheduling")) && "UNKNOWN".equals(classInfo.getType())) {
            classInfo.setType("JOB");
        } else if ((line.contains("@RabbitListener") || line.contains("@KafkaListener")
                || line.contains("@JmsListener") || line.contains("@RocketMQMessageListener"))
                && "UNKNOWN".equals(classInfo.getType())) {
            classInfo.setType("MESSAGE_LISTENER");
        } else if (line.contains("@Component") && "UNKNOWN".equals(classInfo.getType())) {
            classInfo.setType("COMPONENT");
        }
    }

    /**
     * 根据关键字声明提取特有类型 (枚举、注解定义)
     */
    private void detectTypeFromDeclaration(ParsedClassInfo classInfo, String declarationKind) {
        if ("enum".equals(declarationKind)) {
            classInfo.setType("ENUM");
        } else if ("@interface".equals(declarationKind)) {
            classInfo.setType("ANNOTATION");
        }
    }

    /**
     * 依据类名命名规范后缀进行类型猜测匹配 (在无注解时生效)
     */
    private void detectTypeFromName(ParsedClassInfo classInfo) {
        if (!"UNKNOWN".equals(classInfo.getType()) || classInfo.getClassName() == null) {
            return;
        }
        String className = classInfo.getClassName();
        String lower = className.toLowerCase(Locale.ROOT);
        if (lower.endsWith("controller")) {
            classInfo.setType("CONTROLLER");
        } else if (lower.endsWith("service") || lower.endsWith("serviceimpl")) {
            classInfo.setType("SERVICE");
        } else if (lower.endsWith("mapper") || lower.endsWith("dao")) {
            classInfo.setType("MAPPER");
        } else if (lower.endsWith("entity") || lower.endsWith("po")) {
            classInfo.setType("ENTITY");
        } else if (lower.endsWith("dto")) {
            classInfo.setType("DTO");
        } else if (lower.endsWith("vo")) {
            classInfo.setType("VO");
        } else if (lower.endsWith("config") || lower.endsWith("configuration")) {
            classInfo.setType("CONFIG");
        } else if (lower.endsWith("job") || lower.endsWith("task") || lower.endsWith("scheduler")) {
            classInfo.setType("JOB");
        }
    }

    /**
     * 解析成员属性，提取非原生类型依赖，建立类级别的依赖依赖关系
     */
    private void collectDependency(String line, ParsedClassInfo classInfo, Map<String, String> dependencyVariables) {
        Matcher matcher = FIELD_DEPENDENCY_PATTERN.matcher(line);
        if (matcher.find()) {
            String type = stripGeneric(matcher.group(1));
            String variable = matcher.group(2);
            // 过滤 String、Integer 等原生基础值类型依赖
            if (!isSimpleValueType(type)) {
                dependencyVariables.put(variable, type);
                addUnique(variable + ":" + type, classInfo.getDependencies());
            }
        }
    }

    /**
     * 解析构造器参数列表中的依赖项（构造器注入）。
     * 把 (paramName → type) 加入 dependencyVariables，使 collectMethodCalls
     * 能识别 svc.foo() 这类调用，与字段注入（FIELD_DEPENDENCY_PATTERN）等价。
     *
     * 支持形式：
     * - 简单：public X(Type1 a, Type2 b)
     * - 注解：@Autowired public X(Type a) / public X(@Autowired Type a)
     * - final：public X(final Type a)
     * - 泛型与 varargs：Map<String, UserService> services / FooService... svcs
     *
     * 不支持：Lombok @RequiredArgsConstructor 等编译期生成的构造器（源码里看不到）
     */
    private void collectConstructorDependencies(String argsText,
                                                Map<String, String> dependencyVariables,
                                                ParsedClassInfo classInfo) {
        if (!StringUtils.hasText(argsText)) {
            return;
        }
        for (String rawParam : argsText.split(",")) {
            String param = rawParam.trim();
            if (param.isEmpty()) {
                continue;
            }
            // 去掉 @Autowired / @Qualifier / @Lazy 等注解前缀
            param = param.replaceAll("@[A-Za-z][\\w.]*", "").trim();
            // 去掉 final 修饰符
            if (param.startsWith("final ")) {
                param = param.substring("final ".length()).trim();
            }
            // 按最后一个空白切：前面是 Type（含泛型），后面是变量名
            int split = param.lastIndexOf(' ');
            if (split <= 0) {
                continue;
            }
            String type = param.substring(0, split).trim();
            String variable = param.substring(split + 1).trim();
            // 去泛型 + 去 varargs 后缀（如 FooService...）
            type = stripGeneric(type).replaceAll("\\.\\.\\.", "").trim();
            // 过滤：空 / 原始类型（首字母小写，long/int/boolean 等）/ 基础包装类
            if (!StringUtils.hasText(type) || !StringUtils.hasText(variable)) {
                continue;
            }
            if (!Character.isUpperCase(type.charAt(0))) {
                continue;
            }
            if (isSimpleValueType(type)) {
                continue;
            }
            dependencyVariables.put(variable, type);
            addUnique(variable + ":" + type, classInfo.getDependencies());
        }
    }

    /**
     * 分析方法体内部依赖的方法调用，建立调用关系元数据 MethodCallInfo。
     * targetSignature 尽量写成 {@code 短类名#methodName}（正则难以可靠推参数类型，完整参数由 AST 主路径负责）。
     */
    private void collectMethodCalls(String line, int lineNumber, String currentMethod, String currentMethodArgs, Map<String, String> dependencyVariables, ParsedClassInfo classInfo) {
        Matcher matcher = METHOD_CALL_PATTERN.matcher(line);
        while (matcher.find()) {
            String variable = matcher.group(1);
            String targetMethod = matcher.group(2);
            // 若调用的成员变量属于类依赖组件，则进行记录
            if (dependencyVariables.containsKey(variable)) {
                MethodCallInfo callInfo = new MethodCallInfo();
                callInfo.setCallerMethod(currentMethod);
                callInfo.setCallerSignature(buildMethodSignature(currentMethod, currentMethodArgs));
                String depType = dependencyVariables.get(variable);
                callInfo.setDependencyName(depType);
                callInfo.setTargetMethod(targetMethod);
                callInfo.setTargetSignature(buildRegexTargetSignature(depType, targetMethod, line, matcher.end() - 1));
                callInfo.setExpression(variable + "." + targetMethod + "()");
                callInfo.setLineNumber(lineNumber);
                classInfo.getMethodCalls().add(callInfo);
            }
        }
    }

    /**
     * 正则路径：有类名时写 {@code 短类名#method()} 或无参括号缺失时的前缀形式；无法可靠解析参数类型。
     */
    private String buildRegexTargetSignature(String depType, String targetMethod, String line, int openParenIdx) {
        String shortClass = stripPackageSimple(depType);
        if (!StringUtils.hasText(shortClass) || !StringUtils.hasText(targetMethod)) {
            return targetMethod;
        }
        String inside = extractCallArgsInside(line, openParenIdx);
        if (inside == null) {
            return shortClass + "#" + targetMethod;
        }
        if (inside.isBlank()) {
            return shortClass + "#" + targetMethod + "()";
        }
        // 有实参但正则推不出类型 → 无括号，供 BFS 前缀匹配
        return shortClass + "#" + targetMethod;
    }

    /** @return 括号内文本；括号不配对时 null */
    private static String extractCallArgsInside(String line, int openParenIdx) {
        if (line == null || openParenIdx < 0 || openParenIdx >= line.length() || line.charAt(openParenIdx) != '(') {
            return null;
        }
        int depth = 0;
        for (int i = openParenIdx; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return line.substring(openParenIdx + 1, i);
                }
            }
        }
        return null;
    }

    private static String stripPackageSimple(String fqOrShort) {
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

    /**
     * 拼装方法签名：methodName(ParamType1, ParamType2)
     * args 为空时返回 "methodName()"
     */
    private String buildMethodSignature(String methodName, String args) {
        if (methodName == null) return null;
        return methodName + "(" + (args == null ? "" : args) + ")";
    }

    /**
     * 静态识别字符串中硬编码的 SQL 语句和操作，解析其引用的表与字段
     */
    private void collectSql(String line, ParsedClassInfo classInfo) {
        String normalized = line.toUpperCase(Locale.ROOT);
        // 判断行内是否含有四大 DML 数据库操作词
        if (!normalized.contains("SELECT") && !normalized.contains("INSERT") && !normalized.contains("UPDATE") && !normalized.contains("DELETE")) {
            return;
        }

        SqlReference sqlReference = new SqlReference();
        sqlReference.setOperation(resolveSqlOperation(normalized));
        extractTablesFromSql(line, classInfo.getTables(), sqlReference);
        extractSqlFields(line, sqlReference);
        // 如果成功提取到了操作类型或表引用，则计入列表
        if (StringUtils.hasText(sqlReference.getOperation()) || !sqlReference.getTables().isEmpty()) {
            classInfo.getSqlReferences().add(sqlReference);
        }
    }

    /**
     * 判断 SQL 主要操作类型
     */
    private String resolveSqlOperation(String normalizedSql) {
        if (normalizedSql.contains("SELECT")) {
            return "SELECT";
        }
        if (normalizedSql.contains("INSERT")) {
            return "INSERT";
        }
        if (normalizedSql.contains("UPDATE")) {
            return "UPDATE";
        }
        if (normalizedSql.contains("DELETE")) {
            return "DELETE";
        }
        return "UNKNOWN";
    }

    /**
     * 提取 SQL 语句中的数据表引用 (基于 FROM, JOIN, INTO, UPDATE 关键字后接结构)
     */
    private void extractTablesFromSql(String line, List<String> tables, SqlReference sqlReference) {
        collectTableMatches(SQL_FROM_PATTERN, line, tables, sqlReference.getTables());
        collectTableMatches(SQL_INTO_PATTERN, line, tables, sqlReference.getTables());
        collectTableMatches(SQL_UPDATE_PATTERN, line, tables, sqlReference.getTables());

        Matcher joinMatcher = SQL_JOIN_PATTERN.matcher(line);
        while (joinMatcher.find()) {
            String table = cleanIdentifier(joinMatcher.group(1));
            addUnique(table, tables);
            addUnique(table, sqlReference.getTables());
            addUnique(table, sqlReference.getJoinedTables());
        }
    }

    /**
     * 数据表去重收集辅助方法
     */
    private void collectTableMatches(Pattern pattern, String line, List<String> allTables, List<String> sqlTables) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            String table = cleanIdentifier(matcher.group(1));
            addUnique(table, allTables);
            addUnique(table, sqlTables);
        }
    }

    /**
     * 字段级别解析 (对不同 DML 类型匹配相应的字段提取子范围)
     */
    private void extractSqlFields(String line, SqlReference sqlReference) {
        String sql = unwrapSql(line);
        String upper = sql.toUpperCase(Locale.ROOT);
        if ("SELECT".equals(sqlReference.getOperation())) {
            // 解析 SELECT 与 FROM 之间的查询字段
            addFieldsBetween(sql, upper, "SELECT", "FROM", sqlReference.getSelectedFields());
            collectConditionFields(sql, sqlReference.getConditionFields());
        } else if ("INSERT".equals(sqlReference.getOperation())) {
            // 解析 INSERT INTO table(...) 的插入字段
            Matcher matcher = Pattern.compile("(?i)INSERT\\s+INTO\\s+[\\w.`\"']+\\s*\\(([^)]*)\\)").matcher(sql);
            if (matcher.find()) {
                addCsvFields(matcher.group(1), sqlReference.getInsertedFields());
            }
        } else if ("UPDATE".equals(sqlReference.getOperation())) {
            // 解析 SET ... WHERE 的修改字段
            Matcher matcher = Pattern.compile("(?i)\\bSET\\s+(.+?)(?:\\bWHERE\\b|$)").matcher(sql);
            if (matcher.find()) {
                for (String part : matcher.group(1).split(",")) {
                    String[] fieldAndValue = part.split("=");
                    if (fieldAndValue.length > 0) {
                        addUnique(cleanIdentifier(fieldAndValue[0]), sqlReference.getUpdatedFields());
                    }
                }
            }
            collectConditionFields(sql, sqlReference.getConditionFields());
        } else if ("DELETE".equals(sqlReference.getOperation())) {
            collectConditionFields(sql, sqlReference.getConditionFields());
        }
    }

    /**
     * 范围提取 SQL 字段名
     */
    private void addFieldsBetween(String sql, String upper, String startToken, String endToken, List<String> fields) {
        int start = upper.indexOf(startToken);
        int end = upper.indexOf(endToken);
        if (start >= 0 && end > start) {
            addCsvFields(sql.substring(start + startToken.length(), end), fields);
        }
    }

    /**
     * 按逗号分割收集字段并剔除 AS 别名修饰
     */
    private void addCsvFields(String csv, List<String> fields) {
        for (String part : csv.split(",")) {
            String field = cleanIdentifier(part.replaceAll("(?i)\\s+AS\\s+\\w+", ""));
            if (!"*".equals(field)) {
                addUnique(field, fields);
            }
        }
    }

    /**
     * 捕获 WHERE 子句中使用的条件字段 (支持各种逻辑操作符的前置识别)
     */
    private void collectConditionFields(String sql, List<String> fields) {
        Matcher whereMatcher = Pattern.compile("(?i)\\bWHERE\\b\\s+(.+?)(?:\\bGROUP\\b|\\bORDER\\b|\\bLIMIT\\b|$)").matcher(sql);
        if (!whereMatcher.find()) {
            return;
        }
        Matcher fieldMatcher = Pattern.compile("([\\w.]+)\\s*(?:=|<>|!=|>=|<=|>|<|LIKE\\b|IN\\b|BETWEEN\\b)").matcher(whereMatcher.group(1));
        while (fieldMatcher.find()) {
            addUnique(cleanIdentifier(fieldMatcher.group(1)), fields);
        }
    }

    /**
     * 从注解行中提取出具体的 URL 映射路径字符串
     */
    private String getMappingUrl(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            String params = matcher.group(1);
            String value = extractSimpleValue(params);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        String value = extractSimpleValue(line);
        return StringUtils.hasText(value) ? value : "/";
    }

    /**
     * 单双引号内部属性文本值提取
     */
    private String extractSimpleValue(String line) {
        int start = line.indexOf('"');
        if (start != -1) {
            int end = line.indexOf('"', start + 1);
            if (end != -1) {
                return line.substring(start + 1, end);
            }
        }
        return "/";
    }

    /**
     * 格式化整理 SQL 字符串以便于正则扫描
     */
    private String unwrapSql(String line) {
        return line.replace("\\\"", "\"")
                .replace("+", " ")
                .replace(";", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 清理字段或表名标识符（移除非法字符，排除多级 Schema/别名命名干扰）
     */
    private String cleanIdentifier(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[`\"']", "")
                .replaceAll("\\$\\{[^}]+}", "")
                .trim();
        int dot = cleaned.lastIndexOf('.');
        if (dot >= 0) {
            cleaned = cleaned.substring(dot + 1);
        }
        return cleaned.replaceAll("[^A-Za-z0-9_]", "");
    }

    /**
     * 集合去重添加元素
     */
    private void addUnique(String value, List<String> values) {
        if (StringUtils.hasText(value) && !values.contains(value)) {
            values.add(value);
        }
    }

    /**
     * 提取注解简称（去除全限定类名包路径部分）
     */
    private String simpleName(String annotation) {
        int dot = annotation.lastIndexOf('.');
        return dot >= 0 ? annotation.substring(dot + 1) : annotation;
    }

    /**
     * 去除泛型标识，获取基本类类型（例如 List<User> -> List）
     */
    private String stripGeneric(String type) {
        int genericStart = type.indexOf('<');
        return genericStart > 0 ? type.substring(0, genericStart) : type;
    }

    /**
     * 判断是否是常见的基础数据类型
     */
    private boolean isSimpleValueType(String type) {
        return List.of("String", "Integer", "Long", "Boolean", "Double", "Float", "BigDecimal", "LocalDate", "LocalDateTime").contains(type);
    }

    /**
     * 判断方法名是否是保留的控制流关键字，防范误识别
     */
    private boolean isControlFlow(String methodName) {
        return List.of("if", "for", "while", "switch", "catch", "new").contains(methodName);
    }

    /**
     * 单个字符匹配计数统计
     */
    private int countChar(String value, char target) {
        int count = 0;
        for (char current : value.toCharArray()) {
            if (current == target) {
                count++;
            }
        }
        return count;
    }
}
