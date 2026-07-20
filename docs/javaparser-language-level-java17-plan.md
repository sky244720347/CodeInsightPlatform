# JavaParser Language Level → JAVA_17 修改方案

> **管什么**：静态解析 / 入口识别因 Text Block 等 Java 15+ 语法失败（日志：`Text Block Literals are not supported ... JAVA_15`），将解析语言级别提升到 **JAVA_17**，并堵住 `parseDirectory` 遇错即空的放大问题。  
> **不管什么**：平台运行时 JDK（已是 17）；被扫仓库自身的 `maven.compiler.release`；正则引擎语法能力扩展。  
> **状态**：已实施（2026-07-20）。  
> **关联日志**（task 示例）：`EntrypointReviewServiceImpl.discoverAndPersist` → `AstJavaParserService.walkAndParse` → `ParseProblemException`（Text Block / JAVA_15）。

---

## 〇、问题与目标

### 0.1 现状

| 项 | 现状 |
|---|---|
| JavaParser 版本 | `3.26.4`（`javaparser-core` + `symbol-solver-core`） |
| 默认 language level | `ParserConfiguration` 默认 `POPULAR` = **`JAVA_11`** |
| CodeInsight 配置 | **从未** `setLanguageLevel`；`StaticJavaParser.parse` 直接用默认 |
| Symbol Solver 侧 | `new ParserConfiguration().setSymbolResolver(...)` 同样默认 JAVA_11 |
| 单文件 `parseFile` | `FallbackJavaParserService` 可 AST→REGEX 降级 |
| 目录 `parseDirectory` | 直接调 `AstJavaParserService.parseDirectory`；`walkAndParse` **无逐文件 try/catch**，一文件抛错 → 整目录失败 → 入口列表为空 |

### 0.2 目标

1. **所有 AST 解析路径**统一使用 **`LanguageLevel.JAVA_17`**（覆盖 Text Block / record / sealed 等常见 15–17 语法；与平台 JDK 17 对齐）。  
2. **`parseDirectory` 与 `parseFile` 行为一致**：单文件失败不拖垮整目录；默认引擎下仍可 REGEX 兜底。  
3. 加回归测试：含 Text Block 的样例在 JAVA_17 下 AST 可解析；目录扫描遇坏文件不抛空。

### 0.3 为何选 JAVA_17 而不是 JAVA_15 / CURRENT

| 选项 | 说明 |
|---|---|
| `JAVA_15` | 刚好覆盖本次 Text Block，但 16/17（record、sealed）仍会再炸 |
| **`JAVA_17`（推荐）** | 与平台/README 的 JDK 17 一致；覆盖当前主流业务代码；明确、可测 |
| `CURRENT`（库内现为 JAVA_18） | 随依赖漂移，行为不如固定枚举可控；本期不采用 |
| `BLEEDING_EDGE` / `JAVA_21` | 库虽有枚举，但非本期目标；若未来要扫 Java 21 再单独立项 |

---

## 一、改动清单

### A. 统一 Language Level 初始化（必做）

| # | 文件 | 改动 |
|---|---|---|
| A1 | 新建 `modules/parser/config/JavaParserLanguageConfig.java`（或同级 util） | 提供常量 `TARGET_LEVEL = LanguageLevel.JAVA_17` + `apply(ParserConfiguration)` + `ensureStaticJavaParserConfigured()`（幂等） |
| A2 | `AstJavaParserService` 构造 / 静态初始化 | 在首次解析前调用 `ensureStaticJavaParserConfigured()`，保证所有 `StaticJavaParser.parse(...)` 使用 JAVA_17 |
| A3 | `AstJavaParserService.acquireProjectContext` | 创建 `ParserConfiguration` 时：`setLanguageLevel(JAVA_17)` **再** `setSymbolResolver`，与 Static 路径一致，避免 TypeSolver 按 JAVA_11 索引源文件失败 |

建议实现要点：

```java
public final class JavaParserLanguageConfig {
    public static final ParserConfiguration.LanguageLevel TARGET =
            ParserConfiguration.LanguageLevel.JAVA_17;

    public static ParserConfiguration apply(ParserConfiguration cfg) {
        return cfg.setLanguageLevel(TARGET);
    }

    /** 幂等：把 StaticJavaParser 全局配置升到 JAVA_17 */
    public static synchronized void ensureStaticJavaParserConfigured() {
        ParserConfiguration cfg = StaticJavaParser.getParserConfiguration();
        if (cfg.getLanguageLevel() != TARGET) {
            cfg.setLanguageLevel(TARGET);
        }
    }
}
```

说明：`StaticJavaParser` 配置是 ThreadLocal；**每个线程首次解析前都要 ensure**（在 `parseFile` / `parseDirectory` / `indexOneFile` 入口调用即可，避免只配了主线程）。

### B. 堵住 `parseDirectory` 放大失败（必做）

| # | 文件 | 改动 |
|---|---|---|
| B1 | `FallbackJavaParserService.parseDirectory` | **禁止**再直接 `return primary.parseDirectory(...)`。改为自写目录 walk：对每个 `.java` 调 **本类的** `parseFile`（自带 AST→REGEX），失败/null 跳过并打 warn，汇总 `List<ParsedClassInfo>`，并补 `sourceRelativePath` |
| B2 | `AstJavaParserService.walkAndParse` | 单文件包 try/catch：`ParseProblemException` / 其它异常 → log warn 并 **continue**（纯 `ast` 引擎时目录扫描也不中断）；成功则加入 list |

B1 是默认引擎（`ast-fallback-regex`）的主修复；B2 保证 `parser.engine=ast` 时入口扫描也不会因单文件直接空列表。

### C. 测试（必做）

| # | 文件 | 用例 |
|---|---|---|
| C1 | `AstJavaParserServiceTest`（或新建 `JavaParserLanguageLevelTest`） | 临时文件含 Text Block `"""..."""` → `parseFile` 成功且 `className` 非空（JAVA_17） |
| C2 | 同上 | 临时目录：1 个合法 Controller + 1 个故意坏语法文件 → `FallbackJavaParserService.parseDirectory` 至少返回合法类，不抛异常 |
| C3 | 可选 | 断言 `StaticJavaParser.getParserConfiguration().getLanguageLevel() == JAVA_17`（ensure 后） |

不依赖 PG/Redis，沙盒可跑。

### D. 文档 / 配置说明（建议）

| # | 文件 | 改动 |
|---|---|---|
| D1 | 本方案文档状态改为「已实施」 | 实施完成后勾选 |
| D2 | `CLAUDE.md` / README「已知限制」可选一句 | 静态解析 language level = JAVA_17；更高语法仍可能降级 REGEX 或跳过 |

**不做**：新增 `application.yml` 可配 language level（本期写死 JAVA_17，避免运维误配回 11）。若二期要可配，再加 `code-insight.parser.language-level`。

---

## 二、明确不做

| # | 项 |
|---|---|
| F1 | 不升级 JavaParser 大版本（仍 3.26.4），除非测出 JAVA_17 枚举缺失（当前已有） |
| F2 | 不改入口识别业务规则 / include-exclude |
| F3 | 不扩展 Regex 引擎去「理解」Text Block（升 level 后 AST 应能吃掉） |
| F4 | 不把 `CURRENT`/`JAVA_21` 作为默认 |

---

## 三、实施顺序

1. 落地 A1 工具类 + A2/A3 接入点  
2. 落地 B1（Fallback 目录扫描）+ B2（AST walk 容错）  
3. 补 C1/C2 单测并跑通  
4. 用原失败仓库（含 `BlazeWarnRecordMapper` 一类 Text Block）重跑任务，确认 ENTRYPOINT 非空、日志不再出现 JAVA_15 Text Block 硬失败  

---

## 四、验证计划

| 步骤 | 期望 |
|---|---|
| 单测 C1 | Text Block 样例 AST 解析成功 |
| 单测 C2 | 目录扫描不因坏文件抛空 |
| 复现任务（原 task 同类仓库） | 日志无 `Text Block Literals are not supported`；`discoverAndPersist` 有入口；`parseDirectory` 不再以该异常失败退出 |
| 回归 | `AstJavaParserServiceTest` / 调用链相关既有测仍绿 |

---

## 五、风险与回滚

| 风险 | 缓解 |
|---|---|
| JAVA_17 校验更严，个别旧奇葩语法反而变多 warn | 仍有 REGEX 兜底（B1）；单文件跳过不空仓 |
| ThreadLocal 配置在线程池线程未 ensure | 在 `parseFile`/`parseDirectory`/`indexOneFile` 入口统一 ensure |
| Symbol Solver 与 Static 级别不一致 | A3 强制同一 `TARGET` |
| 误以为要改本机 JDK | 文档强调：改的是 **解析器 language level**，不是安装 JDK |

回滚：还原 A/B 相关提交；或临时把 `TARGET` 改回 `JAVA_11`（不推荐，仅排障）。

---

## 六、工作量估计

| 项 | 估时 |
|---|---|
| A 配置统一 | 0.5h |
| B 目录扫描修复 | 1h |
| C 单测 | 0.5–1h |
| 实测复现仓库 | 0.5h |
| **合计** | **约 0.5 人日** |

---

## 七、验收标准

- [x] `StaticJavaParser` / Symbol Solver 使用的 language level 均为 `JAVA_17`  
- [x] 含 Text Block 的源文件 AST 可解析，不再报 JAVA_15 硬错误  
- [x] 入口识别 `parseDirectory` 单文件失败不导致整任务「无入口」  
- [x] 相关单测通过（`JavaParserLanguageLevelTest` + `AstJavaParserServiceTest`）
