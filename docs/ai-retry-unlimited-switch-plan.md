# AI 重试不限次开关方案

> 状态：已实施  
> 日期：2026-08-04  
> 背景：质量优先时，模块层级 / 文档生成不应因「次数用尽」放弃可用结果；需增加开关，打开后**层级与文档**的 AI 重试均不做次数上限。  
> 约束：可取消（用户终止）与不可恢复错误仍立即停止；速度无所谓。

---

## 1. 目标

| 项 | 内容 |
|----|------|
| 开关打开 | `MODULE_HIERARCHY` 与文档阶段（`FUNCTION_DOC` / `MODULE_DOC` / `GENERATING_DOC` 等）经 `PipelineAiCaller` 的重试 **不设次数上限** |
| 开关关闭 | 保持现网：`hierarchyMaxAttempts` / `docMaxAttempts` |
| 统一入口 | 只改 `AiRetryProperties` + `PipelineAiCaller` 循环条件；业务侧无需各写一套 |

**不在本开关范围**（保持原有限次或一次性逻辑）：

- `persistBindingsWithRetry` 等 **DB 写库** 重试（与 AI 无关）  
- Token 额度超限等 **non-retryable**（继续立即失败）  
- 用户 **终止任务**（`TaskCancelledException` 立即跳出）

---

## 2. 配置设计

### 2.1 属性

`code-insight.ai.retry` 增加：

```yaml
code-insight:
  ai:
    retry:
      hierarchy-max-attempts: ${AI_RETRY_HIERARCHY_MAX_ATTEMPTS:3}
      doc-max-attempts: ${AI_RETRY_DOC_MAX_ATTEMPTS:5}
      backoff-ms: ${AI_RETRY_BACKOFF_MS:1000}
      # 新增：为 true 时层级+文档 AI 重试不限次数（仍受取消 / 不可恢复错误约束）
      unlimited-attempts: ${AI_RETRY_UNLIMITED_ATTEMPTS:false}
```

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `unlimitedAttempts` | boolean | **false** | `true` → 层级+文档都不限次；`false` → 仍用两套 max |

### 2.2 API 语义（`AiRetryProperties`）

```text
boolean isUnlimitedAttempts()

/** @return 正整数 = 有上限；null 或约定哨兵 = 不限次 */
Integer resolveMaxAttemptsOrNull(String stage)
  - unlimited → return null
  - else → 现有 resolveMaxAttempts(stage)

/** 兼容旧调用：不限次时不要用 Int.MAX_VALUE 硬顶（日志难看且有「假上限」） */
```

推荐 `PipelineAiCaller` 直接读 `isUnlimitedAttempts()`，循环写成：

```text
while (unlimited || attempt <= maxAttempts) { ... }
```

而不是 `maxAttempts = Integer.MAX_VALUE`。

### 2.3 环境变量

| 变量 | 默认 | 含义 |
|------|------|------|
| `AI_RETRY_UNLIMITED_ATTEMPTS` | `false` | 打开后层级+文档 AI 重试不限次 |

写入 `.env.example` / `application-local.yml` / 若流量管控页已有 AI 重试项则补开关（可选 P1）。

---

## 3. `PipelineAiCaller` 行为

### 3.1 循环与退出

```text
attempt = 1
loop:
  if cancelled → throw
  call AI + validate
  if success → return ok
  if non-retryable（额度等）→ return fail（不重试）
  log [AI-RETRY] attempt=N max=unlimited|M reason=...
  backoff（见 3.2）
  promptMutator（若有）
  attempt++
  if !unlimited && attempt > max → break
fail after N attempts（unlimited 路径理论上仅取消/不可恢复才离开；见 3.3）
```

日志：

- 有限次：`attempt=2/5`（保持）  
- 不限次：`attempt=2/unlimited`（`max` 位打印 `unlimited`，避免假分母）

任务启动日志（`DecompileTaskServiceImpl` 已有 aiRetry 行）追加：

```text
aiRetry = unlimitedAttempts=true|false hierarchyMaxAttempts=… docMaxAttempts=…
```

### 3.2 退避

- 仍使用 `backoffMs * attempt`（或 concurrency 专用退避）  
- **不限次时必须封顶退避**，避免第 N 次睡到数小时：

```text
waitMs = min(backoffMs * attempt, unlimitedBackoffCapMs)
```

新增可选配置（建议有默认，不必暴露到前端）：

| 字段 | 默认 | 说明 |
|------|------|------|
| `unlimited-backoff-cap-ms` | `60000` | 不限次模式下单次等待上限 |

### 3.3 安全阀（强烈建议，仍符合「质量优先」）

「不限次」≠ 进程死循环到机器报废。除取消 / 额度外，增加 **可观测软顶**（默认很大，可配）：

| 字段 | 默认 | 行为 |
|------|------|------|
| `unlimited-soft-cap-attempts` | `0`（0=关闭软顶）或 `100` | 达到后打 `[AI-FAIL] reason=unlimited-soft-cap` 并失败 |

**产品二选一（实施前定一条）：**

- **A（更贴「真正不限次」）**：软顶默认 `0`（关闭），仅取消/额度能停  
- **B（更贴运维安全）**：软顶默认 `100`，文档写明「质量模式建议调大或置 0」

推荐默认 **A**：开关语义与用户原话一致；运维靠取消任务 + 监控 attempt 日志止血。

### 3.4 与「可用性校验加强」的关系

本开关 **不替代** 层级「空名 / binding 全灭须重试」的改造；二者正交：

```text
可用性校验更严 → 更容易触发重试
unlimited-attempts=true → 重试不会因 3/5 次停掉
```

建议同迭代或紧后落地可用性校验；否则不限次只对「已有 validator 失败」生效。

---

## 4. 覆盖范围确认

| 调用方 | stage | 是否吃开关 |
|--------|-------|------------|
| `ModuleHierarchyServiceImpl.callAiForEntry` | `MODULE_HIERARCHY` | ✅ |
| `AiSummaryServiceImpl` 功能/模块文档 | 含 `DOC` 的 stage | ✅ |
| 其它走 `PipelineAiCaller` 且 stage 解析为 hierarchy/doc | 同上 | ✅ |
| 不走 `PipelineAiCaller` 的 LLM 调用 | — | ❌（若有，另列；当前主流水线已走 Caller） |

`resolveMaxAttempts` 的 stage 分类逻辑保持：doc 关键字 → doc 次数；否则 → hierarchy 次数；**unlimited 时两者都忽略次数**。

---

## 5. 实施步骤

```text
1. AiRetryProperties：unlimitedAttempts +（可选）unlimitedBackoffCapMs / softCap
2. PipelineAiCaller：循环条件、日志分母、退避封顶
3. application-local.yml + .env.example
4. DecompileTaskServiceImpl 启动日志打印开关
5. PipelineAiCallerTest：unlimited 下失败 N 次仍继续直至 mock 成功；取消仍能停；额度仍立即 fail
6. CHANGELOG / README 一小段说明
```

---

## 6. 测试要点

| 用例 | 期望 |
|------|------|
| `unlimited=false`，hierarchy max=3，连续校验失败 | 3 次后 fail |
| `unlimited=true`，前 10 次 fail 第 11 次 ok | 成功返回，无「after 3 attempts」 |
| `unlimited=true` + 中途 cancel | 抛 `TaskCancelledException`，不再调用 |
| `unlimited=true` + 额度错误 | 立即 fail，不空转 |
| 退避 | 单次 wait ≤ cap |

---

## 7. 明确不做

| 项 | 原因 |
|----|------|
| 用 `Integer.MAX_VALUE` 伪装不限次 | 日志与心智模型差 |
| 打开开关后取消也重试 | 违反用户终止语义 |
| 额度失败也无限重试 | 无意义且可能刷爆计费 |
| DB persist 三连重点燃同一开关 | 范围只限 AI Caller |

---

## 8. 一句话

**新增 `code-insight.ai.retry.unlimited-attempts`（env：`AI_RETRY_UNLIMITED_ATTEMPTS`）：打开后，模块层级与文档经 `PipelineAiCaller` 的重试不再受 `hierarchy/doc-max-attempts` 限制；取消与额度等不可恢复错误仍立即停止；退避加封顶，避免睡眠爆炸。**
