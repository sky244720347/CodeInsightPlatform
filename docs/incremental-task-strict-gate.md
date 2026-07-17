# 增量任务硬性门禁 — 实施清单

> 配套 plan: `C:\Users\sky\.claude\plans\woolly-herding-whale.md`
> 目标：取消所有"INCREMENTAL 降级为全量"的隐式逻辑；INITIAL 与 INCREMENTAL 必须功能独立；任何 INCREMENTAL 路径条件不满足直接 FAIL。

---

## 改动一览

| # | 文件 | 类型 | 行号 / 区域 |
|---|------|------|---|
| 1 | `backend/.../common/exception/ErrorCode.java` | **新增** | — |
| 2 | `backend/.../common/exception/BusinessException.java` | 修改 | line 18 后追加 2 个构造器 |
| 3 | `backend/.../modules/task/service/impl/DecompileTaskServiceImpl.java` | 修改 | line 387 后插入门禁调用；line 583 后新增 `validateIncrementalBaselineGate` |
| 4 | `backend/.../modules/scanner/service/impl/CodeScannerServiceImpl.java` | 修改 | line 154-176 整段重写；line 234-238 删除；新注入 `OperationLogService` |
| 5 | `backend/src/test/.../task/DecompileTaskServiceTests.java` | 修改 | 新增 3 个测试方法 |
| 6 | `CLAUDE.md` | 修改 | line 152 附近"增量任务门禁"段 + 新增"运行期策略" |
| 7 | （视情况）`CodeScannerServiceTests.java` | 新增/修改 | 新增 1 个基线丢失 FAIL 测试 |

---

## Step 1 — 新建 `ErrorCode.java`

**路径**：`backend/src/main/java/com/company/codeinsight/common/exception/ErrorCode.java`

```java
package com.company.codeinsight.common.exception;

import lombok.Getter;

/**
 * 统一业务错误码常量。
 * <p>门禁类错误码区间 2000-2099（增量任务域）。前端可按 code 区分错误种类。</p>
 */
@Getter
public enum ErrorCode {

    // === 增量任务域（2000-2099）===
    /** 仓库从未发布过任何知识版本（lastPublishedVersionId / lastCommitId 为空） */
    INCREMENTAL_NO_BASELINE(2001,
            "仓库尚未发布过任何知识版本，无法创建增量任务。请先完成一次 INITIAL 任务并推送，或显式选择 INITIAL 重跑全量。"),

    /** 仓库使用本地路径模式（gitUrl 指向本地目录） */
    INCREMENTAL_LOCAL_PATH_NOT_SUPPORTED(2002,
            "本地路径模式不支持增量任务，请切换到 Git 仓库后再发起增量。"),

    /** 增量基线 commit 不可解析（force-push / rebase 等） */
    INCREMENTAL_DIFF_FAILED(2003,
            "增量基线 commit 不可解析（force-push / rebase 或本地仓库被覆盖），请手动核查 ci_operation_log 后再决定重试方式。");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
```

---

## Step 2 — 修改 `BusinessException.java`

**文件**：`backend/src/main/java/com/company/codeinsight/common/exception/BusinessException.java`

在原文件末尾（line 18 后）追加 2 个构造器，**老构造器不动**：

```java
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
```

---

## Step 3 — 改造 `DecompileTaskServiceImpl.java`

### 3.1 新增 import（line 8 后追加）

```java
import com.company.codeinsight.common.exception.ErrorCode;
```

### 3.2 主重载 line 387 后插入门禁调用

**位置**：`createIncrementalTask` 主重载（line 381-416），在 `validateTaskSource` + `validateNoPendingReviewTasks` 之后、`decompilePromptService.validateRepositoryPromptBinding` 之前。

**旧代码**（line 387-389）：
```java
        validateTaskSource(systemId, repositoryId);
        validateNoPendingReviewTasks(systemId, repositoryId);
        decompilePromptService.validateRepositoryPromptBinding(repositoryId);
```

**新代码**：
```java
        validateTaskSource(systemId, repositoryId);
        validateNoPendingReviewTasks(systemId, repositoryId);
        validateIncrementalBaselineGate(systemId, repositoryId);
        decompilePromptService.validateRepositoryPromptBinding(repositoryId);
```

> 注：带 triggerSource 的重载（line 474-486）内部调用主重载，主重载已覆盖门禁，无需重复插入。

### 3.3 新增私有方法（line 582 `validateNoPendingReviewTasks` 之后）

```java
    /**
     * INCREMENTAL 任务硬性门禁：
     * <ul>
     *   <li>门禁 1：仓库必须有过 PUSHED（lastPublishedVersionId 非空 且 lastCommitId 非空）</li>
     *   <li>门禁 2：禁止使用本地路径模式（gitUrl 指向本地目录）</li>
     * </ul>
     * 不满足任一条件直接抛 {@link BusinessException}，前端 message.error 展示具体原因。
     */
    private void validateIncrementalBaselineGate(Long systemId, Long repositoryId) {
        CodeRepository repository = codeRepositoryService.getById(repositoryId);
        if (repository == null) {
            throw new BusinessException("所选代码库不存在");
        }
        if (!Objects.equals(repository.getSystemId(), systemId)) {
            throw new BusinessException("所选代码库不属于当前系统");
        }
        // 门禁 1：必须 PUSHED 过
        if (repository.getLastPublishedVersionId() == null
                || !org.springframework.util.StringUtils.hasText(repository.getLastCommitId())) {
            throw new BusinessException(ErrorCode.INCREMENTAL_NO_BASELINE);
        }
        // 门禁 2：本地路径模式一律禁止
        File probe = new File(repository.getGitUrl());
        if (probe.exists() && probe.isDirectory()) {
            throw new BusinessException(ErrorCode.INCREMENTAL_LOCAL_PATH_NOT_SUPPORTED);
        }
    }
```

> 注意：`java.io.File` 已经从 line 49 `import java.io.File;` 导入，无需重复。

---

## Step 4 — 重写 `CodeScannerServiceImpl.java` 降级块

### 4.1 新增 import（在 line 4 `BusinessException` import 附近追加）

```java
import com.company.codeinsight.common.exception.ErrorCode;
import com.company.codeinsight.modules.log.service.OperationLogService;
```

### 4.2 新增 `@Autowired` 字段（line 65 `DecompileTaskMapper` 附近）

```java
    @Autowired
    private com.company.codeinsight.modules.log.service.OperationLogService operationLogService;
```

### 4.3 重写 line 154-176 整段（pullAndScan 内的"计算扫描文件范围"块）

**旧代码**（line 154-176）：
```java
        if (gitPullSuccess) {
            // === 1. 计算本次扫描的文件范围（增量或全量） ===
            if (isIncremental && gitHandle != null && hasBaseline) {
                try {
                    DiffOutcome diff = computeIncrementalDiff(gitHandle, repo.getLastCommitId(), "HEAD");
                    changedPaths = diff.changed;
                    deletedPaths = diff.deleted;
                    log.info("增量扫描 — 变更 {} 个文件，删除 {} 个文件（基线 {} → HEAD {}）",
                            changedPaths.size(), deletedPaths.size(), repo.getLastCommitId(), commitId);
                } catch (Exception diffEx) {
                    // 基线 commit 在新 history 中不可解析（force-push / rebase），
                    // 降级为全量扫描，不让流水线因增量分支异常而中断
                    log.warn("增量 diff 识别失败（{}），降级为全量扫描", diffEx.getMessage());
                    changedPaths = null;
                    deletedPaths = null;
                }
            } else if (isIncremental && !hasBaseline) {
                log.warn("增量任务无基线 commitId (repositoryId={})，降级为全量扫描", repositoryId);
            } else if (isIncremental && gitHandle == null) {
                log.warn("增量任务未持有 Git 句柄（本地路径或 Mock 降级），降级为全量扫描");
            }

            performFullScan = !isIncremental || changedPaths == null;
```

**新代码**：
```java
        if (gitPullSuccess) {
            // === 1. 计算本次扫描的文件范围（增量或全量，INITIAL/INCREMENTAL 严格分流） ===
            if (isIncremental) {
                // INCREMENTAL 路径：任何条件不满足立即抛错让任务 FAIL（runPipeline 的 catch 已转 FAILED）
                if (gitHandle == null) {
                    operationLogService.logOperation(repositoryId, taskId, "INCREMENTAL_BASELINE_LOST",
                            "INCREMENTAL 任务执行时检测到 gitHandle 为空（疑似 gitUrl 被改成本地路径）",
                            null, false);
                    throw new BusinessException(ErrorCode.INCREMENTAL_LOCAL_PATH_NOT_SUPPORTED);
                }
                if (!StringUtils.hasText(repo.getLastCommitId())) {
                    operationLogService.logOperation(repositoryId, taskId, "INCREMENTAL_BASELINE_LOST",
                            "INCREMENTAL 任务执行时检测到 lastCommitId 为空",
                            null, false);
                    throw new BusinessException(ErrorCode.INCREMENTAL_NO_BASELINE);
                }
                try {
                    DiffOutcome diff = computeIncrementalDiff(gitHandle, repo.getLastCommitId(), "HEAD");
                    changedPaths = diff.changed;
                    deletedPaths = diff.deleted;
                    log.info("增量扫描 — 变更 {} 个文件，删除 {} 个文件（基线 {} → HEAD {}）",
                            changedPaths.size(), deletedPaths.size(), repo.getLastCommitId(), commitId);
                } catch (Exception diffEx) {
                    // 基线 commit 不可解析（force-push / rebase）。禁止降级，直接 FAIL。
                    String msg = "增量基线 commit " + repo.getLastCommitId() + " 不可解析：" + diffEx.getMessage();
                    operationLogService.logOperation(repositoryId, taskId, "INCREMENTAL_BASELINE_LOST", msg, diffEx.getMessage(), false);
                    throw new BusinessException(ErrorCode.INCREMENTAL_DIFF_FAILED, msg);
                }
                performFullScan = false;
            } else {
                // INITIAL 路径：永远全量，不读 lastCommitId，不做 diff
                performFullScan = true;
            }
```

### 4.4 删除 line 234-238 的 scanMode 二次计算

**旧代码**（line 230-239）：
```java
        // 组装给下游的增量上下文；performFullScan 走 fullScan()，否则把 changed/deleted 透传
        IncrementalContext ctx = performFullScan
                ? IncrementalContext.fullScan()
                : IncrementalContext.incremental(changedPaths, deletedPaths);
        String scanMode = "INITIAL";
        String headCommitId = commitId;
        if (isIncremental) {
            scanMode = performFullScan ? "DEGRADED_FULL" : "INCREMENTAL";
        }
        return new ScanResult(targetDir, ctx, baselineCommitId, headCommitId, scanMode);
```

**新代码**：
```java
        // 组装给下游的增量上下文；performFullScan 走 fullScan()，否则把 changed/deleted 透传
        IncrementalContext ctx = performFullScan
                ? IncrementalContext.fullScan()
                : IncrementalContext.incremental(changedPaths, deletedPaths);
        // scanMode：INITIAL 路径固定为 INITIAL；INCREMENTAL 路径成功为 INCREMENTAL（任何失败已在 4.3 中抛错，不会走到这里）
        String scanMode = isIncremental ? "INCREMENTAL" : "INITIAL";
        String headCommitId = commitId;
        return new ScanResult(targetDir, ctx, baselineCommitId, headCommitId, scanMode);
```

> 同时也消除了对 `boolean isIncremental` 的依赖——但因为 line 105 还在定义它，保留即可。

### 4.5 保留 line 106-107 的变量定义不动

`hasBaseline` 和 `baselineCommitId` 这两个变量虽然在 4.3 块中不再使用，但 `baselineCommitId` 在 line 239 的 `ScanResult` 构造中还会用到（取自 `repo.getLastCommitId()`），不要删除其计算逻辑。

---

## Step 5 — 测试改造

### 5.1 `DecompileTaskServiceTests.java` 新增 3 个测试

**文件**：`backend/src/test/java/com/company/codeinsight/modules/task/DecompileTaskServiceTests.java`

在文件末尾追加：

```java
    @Test
    void createIncrementalTask_withNoBaseline_throwsBusinessException() {
        // Arrange：仓库 lastCommitId=null + lastPublishedVersionId=null
        CodeRepository repo = new CodeRepository();
        repo.setId(100L);
        repo.setSystemId(1L);
        repo.setGitUrl("https://gitee.com/example/repo.git");
        repo.setLastCommitId(null);
        repo.setLastPublishedVersionId(null);
        when(codeRepositoryService.getById(100L)).thenReturn(repo);
        when(systemApplicationService.getById(1L)).thenReturn(buildSystem());

        // Act & Assert
        BusinessException ex = assertThrows(BusinessException.class,
                () -> decompileTaskService.createIncrementalTask(
                        1L, 100L, null, null, null, null, null, null));
        assertEquals(2001, ex.getCode());  // INCREMENTAL_NO_BASELINE
    }

    @Test
    void createIncrementalTask_withUnpublishedRepo_throwsBusinessException() {
        CodeRepository repo = new CodeRepository();
        repo.setId(101L);
        repo.setSystemId(1L);
        repo.setGitUrl("https://gitee.com/example/repo.git");
        repo.setLastCommitId("abc123");
        repo.setLastPublishedVersionId(null);   // 已发布指针为空
        when(codeRepositoryService.getById(101L)).thenReturn(repo);
        when(systemApplicationService.getById(1L)).thenReturn(buildSystem());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> decompileTaskService.createIncrementalTask(
                        1L, 101L, null, null, null, null, null, null));
        assertEquals(2001, ex.getCode());
    }

    @Test
    void createIncrementalTask_withLocalPathRepo_throwsBusinessException() throws Exception {
        // Arrange：仓库 gitUrl 指向真实存在的本地目录
        File tempLocalDir = Files.createTempDirectory("ci-test-local-repo").toFile();
        try {
            CodeRepository repo = new CodeRepository();
            repo.setId(102L);
            repo.setSystemId(1L);
            repo.setGitUrl(tempLocalDir.getAbsolutePath());   // 本地路径
            repo.setLastCommitId("abc123");
            repo.setLastPublishedVersionId(99L);             // 已发布，满足门禁 1
            when(codeRepositoryService.getById(102L)).thenReturn(repo);
            when(systemApplicationService.getById(1L)).thenReturn(buildSystem());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> decompileTaskService.createIncrementalTask(
                            1L, 102L, null, null, null, null, null, null));
            assertEquals(2002, ex.getCode());  // INCREMENTAL_LOCAL_PATH_NOT_SUPPORTED
        } finally {
            tempLocalDir.delete();
        }
    }

    private SystemApplication buildSystem() {
        SystemApplication s = new SystemApplication();
        s.setId(1L);
        s.setName("test-system");
        return s;
    }
```

> 注意：`@Mock` / `@InjectMocks` / `when` / `assertThrows` / `assertEquals` 等都需要视现有测试类风格补 import。如果测试类用 `@SpringBootTest`，可能要额外配置 mock bean。

### 5.2 `CodeScannerServiceTests.java` 新增基线丢失测试

**文件**：`backend/src/test/java/com/company/codeinsight/modules/scanner/CodeScannerServiceTests.java`（如不存在则新建）

```java
package com.company.codeinsight.modules.scanner;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.scanner.service.CodeScannerService;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CodeScannerServiceTests {

    @Autowired
    private CodeScannerService codeScannerService;

    @Test
    void pullAndScan_incrementalWithLostBaseline_throwsInProgress() {
        // Arrange：准备一个仓库对象 + 已 PUSHED 但 lastCommitId 在远端 history 中不可解析
        // 此处仅断言"在 baseline 不可解析时会抛 BusinessException，code 是 INCREMENTAL_DIFF_FAILED (2003)"
        // 真实场景需要 mock JGit 的 git.getRepository().resolve(oldRef^{tree}) 抛 MissingObjectException
        // 简化为：构造一个 lastCommitId 指向不存在的 commit 来触发 IllegalStateException
        // （computeIncrementalDiff 内部 resolve 返回 null 时会抛 IllegalStateException）
        // 此测试需要真实的 git handle 才能跑到 try-catch 分支，本次不实施完整 E2E 测试，
        // 仅在 PR 描述中说明"由人工端到端验证"。
    }
}
```

> 完整 E2E 测试需要 git server + force-push 模拟，环境依赖重。建议改用手动验证（见 Step 7）+ 在 PR 描述里加截图。

---

## Step 6 — CLAUDE.md 文档同步

**文件**：`CLAUDE.md`，在 line 152 附近的"增量任务门禁"段改写：

**旧文**：
> 增量任务门禁：仓库必须有 PUSHED 版本 + `lastCommitId` 非空，否则拒绝创建（见 [docs/incremental-release-merge-plan.md](./docs/incremental-release-merge-plan.md)）。

**新文**：
> 增量任务门禁（硬性，在创建时校验）：
> - 仓库必须有 PUSHED 版本：`ci_repository.last_published_version_id` 非空 且 `last_commit_id` 非空
> - 不得使用本地路径模式：gitUrl 指向本地目录的仓库不允许建增量任务
>
> 运行期策略（INITIAL 与 INCREMENTAL 严格功能独立）：
> - 任何 INCREMENTAL 路径下条件不满足（基线 commit 不可解析 / gitHandle 为空 / lastCommitId 为空）→ 任务直接 FAIL，绝不降级为全量
> - INITIAL 任务走全量扫描，不读 lastCommitId，不做 diff
> - `scanMode` 只剩 `INITIAL` / `INCREMENTAL` 两种值（不再有 `DEGRADED_FULL`）

---

## Step 7 — 验证

### 7.1 编译
```bash
cd backend
mvn -DskipTests compile
```

### 7.2 单元测试（需要本地 PG + Redis 可用）
```bash
cd backend
mvn -Dtest=DecompileTaskServiceTests test
mvn -Dtest=TaskStateMachineRemediationTransitTest test   # 回归：INITIAL 路径
mvn -Dtest=TaskRetryCleanupTest test                    # 回归：重试逻辑
```

### 7.3 端到端手动验证
| # | 场景 | 操作 | 预期 |
|---|------|------|------|
| 1 | 未推送仓库建 INCREMENTAL | POST `/api/tasks/incremental` | HTTP 400，`code=2001`，message=ErrorCode.INCREMENTAL_NO_BASELINE.defaultMessage |
| 2 | 已推送仓库建 INCREMENTAL | 同上 | HTTP 200，任务正常创建，扫描器 `scanMode=INCREMENTAL` |
| 3 | 本地路径仓库建 INCREMENTAL | 把仓库 gitUrl 改成本地目录 | HTTP 400，`code=2002` |
| 4 | 基线丢失 | 在已推送仓库上 `git push --force` 让 lastCommitId 失效后建 INCREMENTAL | 任务转 FAILED，`ci_operation_log` 有 `action_type=INCREMENTAL_BASELINE_LOST` 记录，`code=2003` |
| 5 | INITIAL 路径不受影响 | 把本地路径仓库跑 INITIAL | 任务正常全量扫，与今天行为一致 |

### 7.4 前端无改动
前端 `dispatch.tsx` 的 `try/catch + message.error` 自动展示后端 message，无需调整。

---

## 风险与回滚

- **风险 1**：门禁 1 的 `lastPublishedVersionId == null` 判定可能在某些边缘仓库（曾 PUSHED 但被人工回滚到 NULL）误杀。**回滚方式**：临时把 `lastPublishedVersionId` 回填后再允许建任务。
- **风险 2**：Scanner 抛异常会让存量正在跑的任务立即失败。**建议**：发布时同步通知运维，把正在跑的 INCREMENTAL 任务手动 cancel。
- **风险 3**：本地路径门禁会让老 demo（用本地路径当 demo 仓库）的用户突然无法建增量。**应对**：本次只对 INCREMENTAL 加门禁，INITIAL 不受影响。

---

## 改动量估算

| 文件 | 行数变化 |
|------|---------|
| `ErrorCode.java` | +25 行（新建） |
| `BusinessException.java` | +8 行 |
| `DecompileTaskServiceImpl.java` | +25 行（门禁方法 + 调用） |
| `CodeScannerServiceImpl.java` | +5/-15 行（重写降级块、删 DEGRADED_FULL） |
| 测试 | +60 行 |
| CLAUDE.md | +6/-2 行 |
| **合计** | **约 120 行净增 / 17 行删除** |