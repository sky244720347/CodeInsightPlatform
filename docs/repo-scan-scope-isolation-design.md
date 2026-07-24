# 同 Git 多组件隔离：扫描根 / 排除规则生效方案

> **目标**：同一 `gitUrl` 下存在多个业务组件时，通过仓库配置的 **扫描根目录 / 排除目录 / 排除文件类型** 把各系统（组件）的扫描、入口识别、调用链、文档生成边界隔开。  
> **不管什么**：Git 凭证拆分、推送目标隔离、前端「系统+组件」维度本身（已见 [system-component-dimension-plan.md](./system-component-dimension-plan.md)）。  
> **状态**：已落地（未全量回归）。  
> **现状**：`ScanScope` 两段过滤已接入快照扫描、调用链、入口识别、增量 diff 过滤与文档源码收集；`scan_root=/` 为整仓。

---

## 〇、业务模型（约定）

推荐配置方式（与现有表结构兼容）：

| 层级 | 含义 | 示例 |
|---|---|---|
| `ci_system` | 一个隔离单元（可已含「系统+组件」） | `warehouse` + 组件 `rms` |
| `ci_repository` | 挂在该系统下；**可与兄弟系统共用同一 `gitUrl`/`branch`** | `https://gitee.com/.../warehouseManager` |
| `scan_root` | **唯一**业务根（相对仓库根）；先按此收窄 | `/a` 或 `rms-service` |
| `exclude_dirs` | **相对扫描根**再排除的子路径（根筛选之后才生效） | `/d`、`target`、`test` |
| `exclude_file_types` | 排除后缀（同样只作用于根内文件） | `.md,.xml,.json`（通常不影响 `.java`） |

**隔离键仍是 `system_id` + `repository_id`**：任务、入口、层级、草稿、配额不变；只把「看见的代码范围」收窄到本组件。

```text
同一 Git 仓
├── rms-service/          ← 系统 A 的 scan_root
├── order-service/        ← 系统 B 的 scan_root
└── common/               ← 可被 A/B 各自 include，或写入对方 exclude
```

---

## 一、设计原则

1. **拉取仍可整仓 clone**（共享 Git、共享 commit 基线）；隔离发生在 **「有效扫描根」** 之后，不按组件做 sparse checkout（一期）。  
2. **所有读源码的流水线阶段统一走「有效项目目录 / 路径过滤器」**，禁止再对整仓 `projectDir` 裸扫。  
3. **路径一律相对仓库根归一化**（`/`、`\`、前后空白）；`scan_root=/` 表示整仓（兼容现状）。  
4. **两段过滤（硬性）**：每个仓库只有 **一个** `scan_root` → 先保留「落在根下」的路径 → 再按相对根的 `exclude_*` 剔除。排除**不得**相对仓库根绕过根路径去匹配其它兄弟树。  
5. **入口复核的「业务排除」仍用 EntryPointConfig**；仓库三配置管的是「物理可见范围」，两者叠加：先物理范围，再入口规则。

---

## 二、核心概念：ScanScope

新增程序内不可变对象（不必新表）：

```text
ScanScope {
  repoRoot: File          // clone 后的仓库根 = 今日 projectDir
  scanRootRel: String     // 归一化后的 scan_root，如 "rms-service"
  effectiveRoot: File     // repoRoot/scanRootRel（不存在则任务 FAIL）
  excludeDirs: List<String>
  excludeFileTypes: List<String>  // 不含点或统一去点
}
```

工具方法：

- `boolean accepts(String relativeToRepoRoot)` — **两段判定**（见 §4.0）  
- `boolean acceptsAbsolute(File f)`  
- `File effectiveRoot()`  
- `String relativizeToScanRoot(String relativeToRepoRoot)` — 供 exclude 匹配  
- 从 `CodeRepository` 工厂构建：`ScanScope.from(repo, repoRoot)`

**相对路径约定（全流水线统一）**：

- 快照 / `ci_method_call.file_path` / 入口 `filePath`：**相对仓库根**（与今日一致，便于增量 git diff 对齐）。  
- 物理读写：一律 `new File(repoRoot, relativePath)`，并先过 `ScanScope.accepts`。  
- **排除匹配用的路径**：相对扫描根（`pathUnderRoot`），不是相对仓库根。

---

## 三、流水线改造点

| 阶段 | 今日 | 改造后 |
|---|---|---|
| **PULLING** | 整仓 clone 到 `workspaces/task_{id}` | **不变**（仍整仓）；校验 `effectiveRoot` 存在 |
| **快照 scanDirectory** | 从仓根扫；已用 exclude | 从 **`effectiveRoot` 起扫**；exclude 相对**扫描根**匹配；落库路径仍相对仓根 |
| **PARSING / 调用链** | `persistAstForTask(projectDir)` 整树 + 硬编码 SKIP_DIRS | walk 叠加 **ScanScope**（先根内，再相对根 exclude）；硬编码 SKIP 保留 |
| **入口识别** | `parseDirectory(projectDir)` 整仓 | 只解析 **ScanScope 内** `.java`；增量 `discoverEntriesInFiles` 对范围外路径直接跳过 |
| **层级 / 文档源码收集** | 按 binding 读文件 | 读文件前 `accepts`；范围外视为不可达（打 warn，不跨组件污染） |
| **增量 diff** | git diff 全仓路径 | diff 结果先 **filter 进 ScanScope**，再进入 snapshot / AST / 入口增量 |

入口复核页展示的列表 = 识别结果；因识别已受限，**自然只含本组件入口**，无需单独改前端复核 UI。

---

## 四、配置语义细则

### 4.0 两段过滤（已确认）

每个仓库 **有且仅有一个** `scan_root`。对任意相对仓库根的路径 `P`：

```text
1) 根筛选：P 必须等于 scan_root，或前缀为 scan_root + "/"
   → 否则拒绝（不参与后续排除）
2) 排除：取 pathUnderRoot = P 去掉 scan_root 前缀后的剩余路径
   → 若 pathUnderRoot 命中任一 exclude_dirs / exclude_file_types → 拒绝
3) 否则接受
```

**示例**（仓库内路径相对仓根）：

| 路径 | scan_root=`/a`，exclude=`/d` | 结果 |
|---|---|---|
| `a/b/c` | 在根下；underRoot=`b/c`，未命中 `d` | **保留** |
| `a/d/e` | 在根下；underRoot=`d/e`，首段命中 `d` | **排除** |
| `b/b/c` | 不在根 `/a` 下 | **不进入范围**（根筛选阶段丢弃） |

实现时 walk 从 `effectiveRoot` 起步即可天然完成步骤 1；步骤 2 只对「相对扫描根」的路径做匹配。  
**禁止**：用相对仓根的 `a/d` 去配 exclude，或让 exclude `/d` 误伤仓内其它树（如 `b/d/...`）——后者本就不在根内，步骤 1 已丢掉。

### 4.1 `scan_root`

| 输入 | 归一化 | 行为 |
|---|---|---|
| 空 / `/` / `.` | `""` | 有效根 = 仓库根 |
| `/a`、`a/`、`\a` | `a` | 有效根 = `repoRoot/a` |
| `/rms-service`、`rms-service/` | `rms-service` | 有效根 = `repoRoot/rms-service` |
| 指向不存在目录 | — | **任务 FAIL**，明确错误：`扫描根不存在: …` |

禁止：`..` 跳出仓库根（normalize 后必须仍在 `repoRoot` 下）。一期 **不支持** 多根。

### 4.2 `exclude_dirs`

- 逗号分隔，trim；归一化去首尾 `/`（`/d` → `d`）。  
- **相对扫描根**解释：`exclude=d` 表示排除 `scan_root/d/**`，不是仓根下任意名为 `d` 的目录。  
- 匹配规则（路径段精确，相对 `pathUnderRoot`）：  
  - `pathUnderRoot` 等于排除项，或以 `排除项/` 为前缀；或分段后任一段等于短名排除项（兼容只配目录名 `target`）。  
  - 替代今日宽松的 `contains`（易误伤，如排除 `test` 误伤 `contest`）。  
- 默认仍强制排除（相对扫描根下）：`.` 开头目录、`target`、`node_modules`（与今日一致）。

### 4.3 `exclude_file_types`

- 去点、大小写不敏感；只作用于已通过根筛选的文件。  
- **解析 / 入口只关心 `.java`**：若用户把 `.java` 配进排除 → **warn + 忽略对该后缀的排除**（已确认）。

---

## 五、同 URL 多仓库共存

允许：

```text
系统 A / 仓库 1：gitUrl=U, branch=master, scan_root=rms-service
系统 B / 仓库 2：gitUrl=U, branch=master, scan_root=order-service
```

约束与提示（产品层）：

- 创建/编辑仓库时：若同 `system_id` 下已有相同 `gitUrl+branch+scan_root`，提示重复。  
- **不强制**跨系统唯一；跨系统同 URL 不同 scan_root 是推荐用法。  
- 增量基线：`last_commit_id` **按 repository 行**独立；同 URL 两仓库可各自推进发布基线（互不影响）。

---

## 六、不在一期做的

| 项 | 原因 |
|---|---|
| Git sparse-checkout / partial clone | 复杂度高；整仓磁盘可接受 |
| 把 common 模块自动共享给多组件 | 需显式「附加 include 根」模型，二期再议 |
| 用排除规则替代入口 EntryPointConfig | 职责不同，叠加即可 |
| 改 DB schema 新表 | 现有三列足够 |

二期可选：`include_roots` 多根（主 scan_root + 额外 common/）；或 sparse checkout 省盘。

---

## 七、实际改动清单（已落地）

| 类型 | 路径 | 说明 |
|---|---|---|
| **新增** | `backend/.../scanner/model/ScanScope.java` | 两段过滤核心：归一化、`accepts` / `acceptsDirectory`、`filterPaths`、根存在校验 |
| **新增** | `backend/.../scanner/service/ScanScopeResolver.java` | 按 `repositoryId` / `taskId` / `trialId` 解析 scope |
| **新增** | `backend/.../scanner/ScanScopeTest.java` | 7 个单测（§4.0 示例、整仓 `/`、缺根 FAIL、忽略 `.java` 排除等） |
| **修改** | `backend/.../scanner/service/impl/CodeScannerServiceImpl.java` | `pullAndScan` 构建 scope；增量 diff `filterPaths`；`scanDirectory` 从 `effectiveRoot` 起扫 |
| **修改** | `backend/.../callchain/service/impl/MethodCallServiceImpl.java` | `persistAstForTask` 走 `effectiveRoot` + `scope.accepts` |
| **修改** | `backend/.../entrypoint/service/impl/EntryPointDiscoveryServiceImpl.java` | 全量/增量入口 + 可达源码收集改走 `parseDirectoryScoped` / `accepts` |
| **修改** | `backend/.../ai/service/impl/AiSummaryServiceImpl.java` | `collectFunctionSourceCode` / `collectModuleSourceCode` 读文件前 `accepts`，越界 skip+warn |
| **文档** | `docs/repo-scan-scope-isolation-design.md` | 本方案 |
| **未改** | 前端 | 仅描述性文案，按产品要求不改 |
| **未改** | DB schema | 仍用 `ci_repository.scan_root` / `exclude_dirs` / `exclude_file_types` |
| **未改** | `AstJavaParserService` / `FallbackJavaParserService` | 不污染 parser；由调用方 scoped walk |

> 说明：设计稿中的 D5「改 AstJavaParserService」未直接改引擎；入口侧在 `EntryPointDiscoveryServiceImpl` 内自建 `parseDirectoryScoped`，文档侧在 AI 收集处 guard。

---

## 八、验收场景

1. 同 URL 建两仓库，`scan_root` 分别为 `mod-a`、`mod-b`：A 任务入口不得出现 B 包下 Controller。  
2. `scan_root=/`：行为与改造前整仓扫描一致（回归）。  
3. `scan_root` 配错不存在路径：任务失败且日志可读。  
4. **两段过滤**：`scan_root=/a`、`exclude=/d` 时，保留 `a/b/c`，排除 `a/d/e`，根外 `b/b/c` 不可见。  
5. 排除 `target` + 自定义相对根子路径：调用链与入口均不进入。  
6. 增量：仅 mod-a 下文件变更时，A 仓库增量命中；B 仓库同 commit 无 diff 命中（filter 后空可按现有增量语义处理）。

---

## 九、已确认决策

| # | 决策 | 结论 |
|---|---|---|
| 1 | 拉取策略 | 整仓 clone + 逻辑隔离（一期不做 sparse-checkout） |
| 2 | exclude_dirs 匹配 | 路径段精确匹配；**相对扫描根**（先根后排除） |
| 3 | 误排除 `.java` | warn 并忽略 |
| 4 | common / 多根 | 一期不支持；仅一个 `scan_root` |
| 5 | 文档/层级读源码越界 | 跳过并 warn |
| 6 | 排除基准（本轮澄清） | 唯一根路径筛选 → 再相对根做排除 |
| 7 | 前端 | 不改（无功能变更） |

---

## 十、实施代码明细

### 10.1 新增 `ScanScope`（全文，与源文件一致）

路径：`backend/src/main/java/com/company/codeinsight/modules/scanner/model/ScanScope.java`

```java
package com.company.codeinsight.modules.scanner.model;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 仓库扫描范围：唯一 {@code scan_root} + 相对根的排除规则。
 * <p>
 * 两段过滤：先要求路径落在扫描根下，再对「相对扫描根」的路径做 exclude。
 * 落库路径仍相对<strong>仓库根</strong>。
 *
 */
@Slf4j
public final class ScanScope {

    /** 相对扫描根下默认强制排除的目录名（短名段匹配） */
    private static final Set<String> DEFAULT_EXCLUDE_DIR_NAMES = Set.of("target", "node_modules");

    private final File repoRoot;
    /** 归一化后的扫描根（相对仓根，无首尾 {@code /}）；空串 = 整仓 */
    private final String scanRootRel;
    private final File effectiveRoot;
    private final List<String> excludeDirs;
    private final Set<String> excludeFileTypes;

    private ScanScope(File repoRoot,
                      String scanRootRel,
                      File effectiveRoot,
                      List<String> excludeDirs,
                      Set<String> excludeFileTypes) {
        this.repoRoot = repoRoot;
        this.scanRootRel = scanRootRel == null ? "" : scanRootRel;
        this.effectiveRoot = effectiveRoot;
        this.excludeDirs = excludeDirs == null ? List.of() : List.copyOf(excludeDirs);
        this.excludeFileTypes = excludeFileTypes == null ? Set.of() : Set.copyOf(excludeFileTypes);
    }

    /**
     * 从仓库配置构建范围；扫描根目录不存在时抛 {@link BusinessException}。
     */
    public static ScanScope from(CodeRepository repo, File repoRoot) {
        if (repoRoot == null || !repoRoot.isDirectory()) {
            throw new BusinessException("仓库根目录无效，无法构建扫描范围");
        }
        String scanRootRel = normalizeScanRoot(repo == null ? null : repo.getScanRoot());
        File effectiveRoot = resolveEffectiveRoot(repoRoot, scanRootRel);
        List<String> excludeDirs = parseExcludeDirs(repo == null ? null : repo.getExcludeDirs());
        Set<String> excludeTypes = parseExcludeFileTypes(repo == null ? null : repo.getExcludeFileTypes());
        return new ScanScope(repoRoot, scanRootRel, effectiveRoot, excludeDirs, excludeTypes);
    }

    /** 整仓、无业务排除（仅默认敏感目录仍在 accepts 中生效） */
    public static ScanScope wholeRepository(File repoRoot) {
        if (repoRoot == null || !repoRoot.isDirectory()) {
            throw new BusinessException("仓库根目录无效，无法构建扫描范围");
        }
        return new ScanScope(repoRoot, "", repoRoot, List.of(), Set.of());
    }

    public File getRepoRoot() {
        return repoRoot;
    }

    public String getScanRootRel() {
        return scanRootRel;
    }

    public File getEffectiveRoot() {
        return effectiveRoot;
    }

    public List<String> getExcludeDirs() {
        return excludeDirs;
    }

    public Set<String> getExcludeFileTypes() {
        return excludeFileTypes;
    }

    /**
     * 文件是否在可见范围内（相对仓库根路径）。
     */
    public boolean accepts(String relativeToRepoRoot) {
        String p = normalizeRelativePath(relativeToRepoRoot);
        if (p == null) {
            return false;
        }
        if (!underScanRoot(p)) {
            return false;
        }
        String underRoot = relativizeToScanRoot(p);
        if (isExcludedDirPath(underRoot)) {
            return false;
        }
        return !isExcludedFileType(p);
    }

    /**
     * 目录是否可进入（相对仓库根）。目录不做后缀排除。
     */
    public boolean acceptsDirectory(String relativeToRepoRoot) {
        String p = normalizeRelativePath(relativeToRepoRoot);
        if (p == null) {
            // 空路径表示仓库根；仅当 scan_root 为空（整仓）时可进入
            return scanRootRel.isEmpty();
        }
        if (!underScanRoot(p)) {
            return false;
        }
        return !isExcludedDirPath(relativizeToScanRoot(p));
    }

    /**
     * 去掉扫描根前缀后的路径；不在根下时返回 {@code null}。
     */
    public String relativizeToScanRoot(String relativeToRepoRoot) {
        String p = normalizeRelativePath(relativeToRepoRoot);
        if (p == null) {
            return scanRootRel.isEmpty() ? "" : null;
        }
        if (!underScanRoot(p)) {
            return null;
        }
        if (scanRootRel.isEmpty()) {
            return p;
        }
        if (p.equals(scanRootRel)) {
            return "";
        }
        return p.substring(scanRootRel.length() + 1);
    }

    public Set<String> filterPaths(Set<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return paths == null ? Set.of() : paths;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String path : paths) {
            if (accepts(path)) {
                out.add(normalizeRelativePath(path));
            }
        }
        return out;
    }

    // -------------------- normalize / match --------------------

    public static String normalizeScanRoot(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty() || ".".equals(s)) {
            return "";
        }
        if (s.contains("..")) {
            throw new BusinessException("扫描根不允许包含 '..': " + raw);
        }
        return s;
    }

    static String normalizeRelativePath(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.contains("..")) {
            return null;
        }
        return s.isEmpty() ? null : s;
    }

    static List<String> parseExcludeDirs(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String n = normalizeScanRoot(part);
            if (StringUtils.hasText(n)) {
                out.add(n);
            }
        }
        return Collections.unmodifiableList(out);
    }

    static Set<String> parseExcludeFileTypes(String csv) {
        if (!StringUtils.hasText(csv)) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        boolean ignoredJava = false;
        for (String part : csv.split(",")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            String ext = part.trim().toLowerCase(Locale.ROOT);
            if (ext.startsWith(".")) {
                ext = ext.substring(1);
            }
            if (!StringUtils.hasText(ext)) {
                continue;
            }
            if ("java".equals(ext)) {
                ignoredJava = true;
                continue;
            }
            out.add(ext);
        }
        if (ignoredJava) {
            log.warn("exclude_file_types 含 .java，已忽略，避免误配导致扫描为空");
        }
        return Collections.unmodifiableSet(out);
    }

    private static File resolveEffectiveRoot(File repoRoot, String scanRootRel) {
        File effective = scanRootRel.isEmpty() ? repoRoot : new File(repoRoot, scanRootRel.replace('/', File.separatorChar));
        if (!effective.exists() || !effective.isDirectory()) {
            throw new BusinessException("扫描根不存在: " + (scanRootRel.isEmpty() ? "/" : "/" + scanRootRel)
                    + "（仓库根: " + repoRoot.getAbsolutePath() + "）");
        }
        try {
            Path repoCanon = repoRoot.getCanonicalFile().toPath().normalize();
            Path effCanon = effective.getCanonicalFile().toPath().normalize();
            if (!effCanon.startsWith(repoCanon)) {
                throw new BusinessException("扫描根越出仓库根: " + scanRootRel);
            }
        } catch (IOException e) {
            throw new BusinessException("扫描根路径校验失败: " + e.getMessage());
        }
        return effective;
    }

    private boolean underScanRoot(String normalizedRepoRel) {
        if (scanRootRel.isEmpty()) {
            return true;
        }
        return normalizedRepoRel.equals(scanRootRel)
                || normalizedRepoRel.startsWith(scanRootRel + "/");
    }

    private boolean isExcludedDirPath(String underRoot) {
        if (underRoot == null) {
            return true;
        }
        if (underRoot.isEmpty()) {
            return false;
        }
        String[] segments = underRoot.split("/");
        for (String seg : segments) {
            if (!StringUtils.hasText(seg)) {
                continue;
            }
            if (seg.startsWith(".") || DEFAULT_EXCLUDE_DIR_NAMES.contains(seg)) {
                return true;
            }
        }
        for (String ex : excludeDirs) {
            if (matchesExclude(underRoot, ex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 相对扫描根匹配：前缀相等，或短名排除项命中任一路段。
     */
    public static boolean matchesExclude(String underRoot, String exclude) {
        if (!StringUtils.hasText(underRoot) || !StringUtils.hasText(exclude)) {
            return false;
        }
        if (underRoot.equals(exclude) || underRoot.startsWith(exclude + "/")) {
            return true;
        }
        if (!exclude.contains("/")) {
            for (String seg : underRoot.split("/")) {
                if (exclude.equals(seg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isExcludedFileType(String repoRelPath) {
        if (excludeFileTypes.isEmpty()) {
            return false;
        }
        int dot = repoRelPath.lastIndexOf('.');
        int slash = repoRelPath.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            return false;
        }
        String ext = repoRelPath.substring(dot + 1).toLowerCase(Locale.ROOT);
        return excludeFileTypes.contains(ext);
    }
}
```

### 10.2 新增 `ScanScopeResolver`（全文，与源文件一致）

路径：`backend/src/main/java/com/company/codeinsight/modules/scanner/service/ScanScopeResolver.java`

```java
package com.company.codeinsight.modules.scanner.service;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.entrypoint.trial.EntryScanTrialEntity;
import com.company.codeinsight.modules.entrypoint.trial.EntryScanTrialMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.scanner.model.ScanScope;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 按仓库配置解析 {@link ScanScope}；支持正式任务与入口试跑（trialId 同工作区 id）。
 */
@Slf4j
@Component
public class ScanScopeResolver {

    @Autowired
    private CodeRepositoryMapper repositoryMapper;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired(required = false)
    private EntryScanTrialMapper trialMapper;

    public ScanScope require(CodeRepository repo, File repoRoot) {
        return ScanScope.from(repo, repoRoot);
    }

    public ScanScope requireByRepositoryId(Long repositoryId, File repoRoot) {
        if (repositoryId == null) {
            throw new BusinessException("repositoryId 为空，无法解析扫描范围");
        }
        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        if (repo == null) {
            throw new BusinessException("未找到代码库配置: " + repositoryId);
        }
        return ScanScope.from(repo, repoRoot);
    }

    /**
     * 按 taskId / trialId 尽力解析；都找不到时退回整仓（兼容无配置上下文）。
     */
    public ScanScope resolveBestEffort(Long taskOrTrialId, File repoRoot) {
        if (repoRoot == null || !repoRoot.isDirectory()) {
            throw new BusinessException("仓库根目录无效，无法解析扫描范围");
        }
        Long repositoryId = lookupRepositoryId(taskOrTrialId);
        if (repositoryId == null) {
            log.warn("ScanScope: taskOrTrialId={} 无关联仓库，使用整仓范围", taskOrTrialId);
            return ScanScope.wholeRepository(repoRoot);
        }
        return requireByRepositoryId(repositoryId, repoRoot);
    }

    private Long lookupRepositoryId(Long taskOrTrialId) {
        if (taskOrTrialId == null) {
            return null;
        }
        DecompileTask task = taskMapper.selectById(taskOrTrialId);
        if (task != null && task.getRepositoryId() != null) {
            return task.getRepositoryId();
        }
        if (trialMapper != null) {
            EntryScanTrialEntity trial = trialMapper.selectById(taskOrTrialId);
            if (trial != null && trial.getRepositoryId() != null) {
                return trial.getRepositoryId();
            }
        }
        return null;
    }
}
```

### 10.3 修改 `CodeScannerServiceImpl`

路径：`backend/src/main/java/com/company/codeinsight/modules/scanner/service/impl/CodeScannerServiceImpl.java`

**pullAndScan：clone 成功后构建 scope，增量 diff 先过滤，从 effectiveRoot 扫描**

```java
if (gitPullSuccess) {
    // 校验扫描根并构建两段过滤范围（整仓 clone 后逻辑隔离）
    ScanScope scope = ScanScope.from(repo, targetDir);
    log.info("ScanScope taskId={} scanRoot={} effectiveRoot={}",
            taskId,
            scope.getScanRootRel().isEmpty() ? "/" : scope.getScanRootRel(),
            scope.getEffectiveRoot().getAbsolutePath());

    if (isIncremental) {
        // ... 基线校验不变 ...
        DiffOutcome diff = computeIncrementalDiff(gitHandle, repo.getLastCommitId(), "HEAD");
        int rawChanged = diff.changed.size();
        int rawDeleted = diff.deleted.size();
        changedPaths = scope.filterPaths(diff.changed);
        deletedPaths = scope.filterPaths(diff.deleted);
        log.info("增量扫描 — 变更 {}→{} 个文件，删除 {}→{} 个文件（ScanScope 过滤后；基线 {} → HEAD {}）",
                rawChanged, changedPaths.size(), rawDeleted, deletedPaths.size(),
                repo.getLastCommitId(), commitId);
        performFullScan = false;
    } else {
        performFullScan = true;
    }

    // ... snapshot 清理逻辑不变，使用过滤后的 changed/deleted ...

    if (performFullScan) {
        scanDirectory(targetDir, scope.getEffectiveRoot(), scope, taskId, batchBuffer, null, totalInserted);
    } else {
        scanDirectory(targetDir, scope.getEffectiveRoot(), scope, taskId, batchBuffer, changedPaths, totalInserted);
    }
}
```

**scanDirectory：相对仓根算路径；目录/文件分别走 acceptsDirectory / accepts**

```java
private void scanDirectory(File baseDir, File currentDir, ScanScope scope, Long taskId,
                           List<CodeFileSnapshot> batchBuffer, Set<String> pathFilter,
                           int[] totalInserted) {
    File[] files = currentDir.listFiles();
    if (files == null) return;

    for (File file : files) {
        // 相对仓库根（例如 a/b/c.java），非相对扫描根
        String relativePath = baseDir.toURI().relativize(file.toURI()).getPath();
        if (relativePath.endsWith("/")) {
            relativePath = relativePath.substring(0, relativePath.length() - 1);
        }
        relativePath = relativePath.replace('\\', '/');

        if (file.isDirectory()) {
            if (!scope.acceptsDirectory(relativePath)) continue;
            if (pathFilter != null && !subtreeHasMatch(pathFilter, relativePath)) continue;
            scanDirectory(baseDir, file, scope, taskId, batchBuffer, pathFilter, totalInserted);
        } else {
            if (!scope.accepts(relativePath)) continue;
            if (pathFilter != null && !pathFilter.contains(relativePath)) continue;
            String ext = getFileExtension(file.getName());
            createFileSnapshot(baseDir, file, relativePath, taskId, ext, batchBuffer, totalInserted);
        }
    }
}
```

### 10.4 修改 `MethodCallServiceImpl`

路径：`backend/src/main/java/com/company/codeinsight/modules/callchain/service/impl/MethodCallServiceImpl.java`

```java
@Autowired
private ScanScopeResolver scanScopeResolver;

public int persistAstForTask(Long taskId, File projectDir, IncrementalContext ctx) {
    // ...
    ScanScope scope = scanScopeResolver.resolveBestEffort(taskId, projectDir);

    if (!effective.isIncremental()) {
        deleteByTaskId(taskId);
        return walkAndPersist(projectDir, scope.getEffectiveRoot(), taskId, null, scope);
    }
    // ... 增量删插逻辑不变 ...
    return walkAndPersist(projectDir, scope.getEffectiveRoot(), taskId, effective.getChangedPaths(), scope);
}

private void walk(File baseDir, File current, Long taskId, List<MethodCall> buffer, int[] counters,
                  Set<String> pathFilter, ScanScope scope) {
    if (current.isDirectory()) {
        String dirRel = relativize(baseDir, current);
        if (StringUtils.hasText(dirRel) && !scope.acceptsDirectory(dirRel)) return;
        // ... SKIP_DIRS 保留 ...
        for (File child : children) {
            walk(baseDir, child, taskId, buffer, counters, pathFilter, scope);
        }
        return;
    }
    String relativePath = relativize(baseDir, current);
    if (!scope.accepts(relativePath)) return;
    if (pathFilter != null && !pathFilter.contains(relativePath)) return;
    // ... parseFile + 落表 ...
}
```

### 10.5 修改 `EntryPointDiscoveryServiceImpl`

路径：`backend/src/main/java/com/company/codeinsight/modules/entrypoint/service/impl/EntryPointDiscoveryServiceImpl.java`

要点：

1. 注入 `ScanScopeResolver`。  
2. `discoverEntriesInternal`：`parseDirectoryScoped(projectDir, scope)` 替代 `javaParserService.parseDirectory(projectDir)`。  
3. `discoverEntriesInFiles`：对每个 `relativePath` 先 `scope.accepts`。  
4. `collectReachableSourceInternal`：scoped 解析 + `readSourceFile(..., scope)`；越界返回占位并 skip。  
5. 新增私有方法：

```java
private List<ParsedClassInfo> parseDirectoryScoped(File repoRoot, ScanScope scope) {
    List<ParsedClassInfo> out = new ArrayList<>();
    if (repoRoot == null || scope == null) return out;
    walkParseScoped(repoRoot, scope.getEffectiveRoot(), scope, out);
    return out;
}

private void walkParseScoped(File repoRoot, File current, ScanScope scope, List<ParsedClassInfo> out) {
    if (current.isDirectory()) {
        String dirRel = repoRoot.toURI().relativize(current.toURI()).getPath().replace('\\', '/');
        if (dirRel.endsWith("/")) dirRel = dirRel.substring(0, dirRel.length() - 1);
        if (StringUtils.hasText(dirRel) && !scope.acceptsDirectory(dirRel)) return;
        File[] children = current.listFiles();
        if (children == null) return;
        for (File child : children) walkParseScoped(repoRoot, child, scope, out);
        return;
    }
    if (!current.isFile() || !current.getName().endsWith(".java")) return;
    String rel = repoRoot.toURI().relativize(current.toURI()).getPath().replace('\\', '/');
    if (!scope.accepts(rel)) return;
    ParsedClassInfo info = javaParserService.parseFile(current);
    if (info != null && info.getClassName() != null) {
        info.setSourceRelativePath(rel); // 仍相对仓库根
        out.add(info);
    }
}

private String readSourceFile(File projectDir, String relativePath, String fallbackClassName, ScanScope scope) {
    if (StringUtils.hasText(relativePath) && scope != null && !scope.accepts(relativePath)) {
        log.warn("readSourceFile: 路径超出扫描范围，跳过 path={}", relativePath);
        return "// (source out of scan scope: " + relativePath + ")";
    }
    // ... 原读取逻辑；FQCN 回退文件同样做 accepts 校验 ...
}
```

### 10.6 修改 `AiSummaryServiceImpl`

路径：`backend/src/main/java/com/company/codeinsight/modules/ai/service/impl/AiSummaryServiceImpl.java`

```java
@Autowired
private ScanScopeResolver scanScopeResolver;

// collectFunctionSourceCode / collectModuleSourceCode 内：
ScanScope scope = (projectDir != null && projectDir.isDirectory() && taskId != null)
        ? scanScopeResolver.resolveBestEffort(taskId, projectDir)
        : null;

// 每个 classFilePath 读盘前：
if (scope != null && !scope.accepts(classFilePath)) {
    log.warn("... 超出扫描范围，跳过 class={} path={}", className, classFilePath);
    continue;
}
```

### 10.7 单测 `ScanScopeTest`（全文，与源文件一致）

路径：`backend/src/test/java/com/company/codeinsight/modules/scanner/ScanScopeTest.java`

运行：`mvn -Dtest=ScanScopeTest test`（7 tests, 0 failures）。

```java
package com.company.codeinsight.modules.scanner;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.scanner.model.ScanScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ScanScope 两段过滤：先唯一 scan_root，再相对根 exclude。
 */
class ScanScopeTest {

    @TempDir
    Path tempDir;

    @Test
    void twoStageFilter_rootA_excludeD() throws Exception {
        Path a = tempDir.resolve("a");
        Files.createDirectories(a.resolve("b/c"));
        Files.createDirectories(a.resolve("d/e"));
        Files.createDirectories(tempDir.resolve("b/b/c"));
        Files.writeString(a.resolve("b/c/Keep.java"), "class Keep {}");
        Files.writeString(a.resolve("d/e/Drop.java"), "class Drop {}");
        Files.writeString(tempDir.resolve("b/b/c/Out.java"), "class Out {}");

        CodeRepository repo = new CodeRepository();
        repo.setScanRoot("/a");
        repo.setExcludeDirs("/d");

        ScanScope scope = ScanScope.from(repo, tempDir.toFile());

        assertTrue(scope.accepts("a/b/c/Keep.java"));
        assertFalse(scope.accepts("a/d/e/Drop.java"));
        assertFalse(scope.accepts("b/b/c/Out.java"));
        assertTrue(scope.acceptsDirectory("a/b"));
        assertFalse(scope.acceptsDirectory("a/d"));
        assertFalse(scope.acceptsDirectory("b"));
    }

    @Test
    void scanRootSlash_meansWholeRepo() throws Exception {
        Files.createDirectories(tempDir.resolve("x"));
        Files.writeString(tempDir.resolve("x/A.java"), "class A {}");

        CodeRepository repo = new CodeRepository();
        repo.setScanRoot("/");
        repo.setExcludeDirs("x");

        ScanScope scope = ScanScope.from(repo, tempDir.toFile());
        assertEquals("", scope.getScanRootRel());
        assertEquals(tempDir.toFile().getCanonicalFile(), scope.getEffectiveRoot().getCanonicalFile());
        assertFalse(scope.accepts("x/A.java"));
    }

    @Test
    void missingScanRoot_fails() {
        CodeRepository repo = new CodeRepository();
        repo.setScanRoot("/missing-mod");
        assertThrows(BusinessException.class, () -> ScanScope.from(repo, tempDir.toFile()));
    }

    @Test
    void excludeJava_ignored() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/A.java"), "class A {}");
        Files.writeString(tempDir.resolve("src/note.md"), "# n");

        CodeRepository repo = new CodeRepository();
        repo.setScanRoot("/");
        repo.setExcludeFileTypes(".java,.md");

        ScanScope scope = ScanScope.from(repo, tempDir.toFile());
        assertTrue(scope.accepts("src/A.java"), ".java 排除应被忽略");
        assertFalse(scope.accepts("src/note.md"));
    }

    @Test
    void filterPaths_keepsInScopeOnly() throws Exception {
        Files.createDirectories(tempDir.resolve("mod-a"));
        Files.createDirectories(tempDir.resolve("mod-b"));

        CodeRepository repo = new CodeRepository();
        repo.setScanRoot("mod-a");

        ScanScope scope = ScanScope.from(repo, tempDir.toFile());
        Set<String> filtered = scope.filterPaths(Set.of(
                "mod-a/Foo.java",
                "mod-b/Bar.java",
                "mod-a/target/X.java"
        ));
        assertEquals(Set.of("mod-a/Foo.java"), filtered);
    }

    @Test
    void normalizeScanRoot_variants() {
        assertEquals("", ScanScope.normalizeScanRoot("/"));
        assertEquals("", ScanScope.normalizeScanRoot("."));
        assertEquals("", ScanScope.normalizeScanRoot(""));
        assertEquals("a", ScanScope.normalizeScanRoot("/a/"));
        assertEquals("rms-service", ScanScope.normalizeScanRoot("\\rms-service\\"));
    }

    @Test
    void matchesExclude_segmentAndPrefix() {
        assertTrue(ScanScope.matchesExclude("d/e", "d"));
        assertTrue(ScanScope.matchesExclude("b/d/e", "d"));
        assertTrue(ScanScope.matchesExclude("src/test/java", "src/test"));
        assertFalse(ScanScope.matchesExclude("b/c", "d"));
        assertFalse(ScanScope.matchesExclude("contest", "test"));
    }
}
```

## 十一、数据流（落地后）

```text
ci_repository(scan_root, exclude_dirs, exclude_file_types)
        │
        ▼
pullAndScan ──► ScanScope.from(repo, workspace)
        │            │
        │            ├─ effectiveRoot 不存在 → BusinessException → 任务 FAIL
        │            ├─ INCREMENTAL: filterPaths(git diff)
        │            └─ scanDirectory(effectiveRoot, scope) → ci_code_file_snapshot
        ▼
persistAstForTask ──► resolveBestEffort → walk(effectiveRoot, scope) → ci_method_call
        ▼
discoverEntries* ──► parseDirectoryScoped / accepts → 入口表
        ▼
AiSummary 读源码 ──► accepts？否 → warn+skip；是 → 拼进 prompt
```
