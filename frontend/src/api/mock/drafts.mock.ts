/**
 * 复核工作区「演示模式」本地数据 store
 *
 * 设计目标：
 * 1. 完全离线运行 —— 不发任何 HTTP 请求，所有读写操作都走 module-level state。
 * 2. 数据可编辑 —— 保存 / 自动保存 / 确认 / 驳回都会修改本地 store，刷新列表时拿到最新数据。
 * 3. 覆盖页面所有交互 —— 系统下拉、状态筛选、目录树、代码来源、修订记录、复核意见都有真实可点。
 * 4. 演示模式隔离 —— 仅当 VITE_USE_MOCK=true 或 URL 含 ?demo=1 时启用，不污染真实请求。
 */

import type {
  DraftRevision,
  DraftReviewComment,
  DraftSourceReference,
  DraftTreeNode,
  DraftWorkspace,
  KnowledgeDraft,
  PreviewSystemDto,
} from '../draft';
import type { Task } from '../../types';
import type { RepositoryReadiness } from '../task';

/* ============================================================
 * 工具
 * ============================================================ */

const nowIso = () => new Date().toISOString();
let revSeq = 1000;
let cmtSeq = 2000;
let refSeq = 3000;
const nextRevId = () => ++revSeq;
const nextCmtId = () => ++cmtSeq;
const nextRefId = () => ++refSeq;

/* ============================================================
 * 演示数据：3 个系统 / 5 个任务 / 2 个工作区 / 11 个模块（含目录分组）
 * ============================================================ */

const tasks: Task[] = [
  {
    id: 1001,
    systemId: 1,
    repositoryId: 11,
    promptVersion: 3,
    modelName: 'MiniMax-M3',
    status: 'PENDING_REVIEW',
    type: 'INITIAL',
    progress: 100,
    durationMs: 184200,
    startedAt: '2026-06-27T09:12:00.000Z',
    endedAt: '2026-06-27T09:15:04.200Z',
    createdDate: '2026-06-27T09:12:00.000Z',
    updatedDate: '2026-06-27T09:15:04.200Z',
  },
  {
    id: 1002,
    systemId: 1,
    repositoryId: 11,
    promptVersion: 3,
    modelName: 'MiniMax-M3',
    status: 'REVIEWING',
    type: 'INCREMENTAL',
    progress: 100,
    durationMs: 92500,
    startedAt: '2026-06-28T10:30:00.000Z',
    endedAt: '2026-06-28T10:31:32.500Z',
    createdDate: '2026-06-28T10:30:00.000Z',
    updatedDate: '2026-06-28T10:31:32.500Z',
  },
  {
    id: 1003,
    systemId: 2,
    repositoryId: 21,
    promptVersion: 2,
    modelName: 'MiniMax-M3',
    status: 'PENDING_REVIEW',
    type: 'INCREMENTAL',
    progress: 100,
    durationMs: 67800,
    startedAt: '2026-06-28T08:00:00.000Z',
    endedAt: '2026-06-28T08:01:07.800Z',
    createdDate: '2026-06-28T08:00:00.000Z',
    updatedDate: '2026-06-28T08:01:07.800Z',
  },
  {
    id: 1004,
    systemId: 3,
    repositoryId: 31,
    promptVersion: 4,
    modelName: 'MiniMax-M3',
    status: 'CONFIRMED',
    type: 'INITIAL',
    progress: 100,
    durationMs: 213400,
    startedAt: '2026-06-25T14:00:00.000Z',
    endedAt: '2026-06-25T14:03:33.400Z',
    createdDate: '2026-06-25T14:00:00.000Z',
    updatedDate: '2026-06-25T14:03:33.400Z',
  },
  {
    id: 1005,
    systemId: 3,
    repositoryId: 31,
    promptVersion: 4,
    modelName: 'MiniMax-M3',
    status: 'CONFIRMED',
    type: 'INCREMENTAL',
    progress: 100,
    durationMs: 41200,
    startedAt: '2026-06-26T16:20:00.000Z',
    endedAt: '2026-06-26T16:20:41.200Z',
    createdDate: '2026-06-26T16:20:00.000Z',
    updatedDate: '2026-06-26T16:20:41.200Z',
  },
];

const workspaces: DraftWorkspace[] = [
  {
    id: 5001,
    taskId: 1001,
    systemId: 1,
    repositoryId: 11,
    status: 'ACTIVE',
    createdDate: '2026-06-27T09:15:04.200Z',
    updatedDate: '2026-06-28T11:18:00.000Z',
  },
  {
    id: 5002,
    taskId: 1003,
    systemId: 2,
    repositoryId: 21,
    status: 'ACTIVE',
    createdDate: '2026-06-28T08:01:07.800Z',
    updatedDate: '2026-06-28T08:01:07.800Z',
  },
  {
    id: 5003,
    taskId: 1004,
    systemId: 3,
    repositoryId: 31,
    status: 'COMPLETED',
    createdDate: '2026-06-25T14:03:33.400Z',
    updatedDate: '2026-06-26T16:20:41.200Z',
  },
];

/* ============================================================
 * 草稿正文（按 draftId 索引）—— 内容覆盖 Markdown 全部要素
 * ============================================================ */

const CONTENT = {
  6001: `# 订单服务总览

订单服务是 **OrderHub** 系统的核心入口，负责从下单、支付、履约到售后的全链路编排。它向下对接支付网关、库存中心和物流服务，向上暴露 gRPC 与 REST 双协议。

## 核心能力

- **统一订单模型**：将实物订单、虚拟订单、订阅续费三种业务形态抽象为统一 \`OrderEntity\`。
- **状态机驱动**：通过 \`OrderStatusMachine\` 描述生命周期，避免跨服务间状态不一致。
- **幂等性保障**：所有写操作携带 \`requestId\`，已处理的请求直接返回缓存结果。
- **可观测性**：每次状态迁移写入审计日志，关键指标实时上报 Prometheus。

## 业务流程

\`\`\`mermaid
flowchart LR
    A[用户下单] --> B[参数校验]
    B --> C[优惠核销]
    C --> D[库存预占]
    D --> E[支付收单]
    E --> F{支付结果}
    F -->|成功| G[订单确认]
    F -->|失败| H[订单关闭]
    G --> I[履约调度]
    I --> J[签收回传]
\`\`\`

## 关键依赖

| 依赖 | 用途 | 备注 |
| --- | --- | --- |
| \`payment-gateway\` | 支付收单 | 通过 Feign 调用 |
| \`inventory-center\` | 库存预占 | MQ 异步通知 |
| \`risk-engine\` | 风控拦截 | 同步阻塞 |

## 待优化项

- [x] 引入分布式锁解决超卖
- [x] 支付超时自动关单
- [ ] 增加订单合并支付能力
- [ ] 补充秒杀场景专项优化
`,
  6002: `# 订单状态机

订单状态由 \`com.orderhub.state.OrderStatusMachine\` 维护，共 12 个状态、24 条合法迁移。所有跨服务调用都遵循 **「先校验后变更」** 原则。

## 状态迁移全景

\`\`\`mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> PAID: 支付成功
    CREATED --> CLOSED: 30 分钟未支付
    CREATED --> CANCELLED: 用户主动取消
    PAID --> SHIPPED: 履约接单
    PAID --> REFUNDING: 申请退款
    SHIPPED --> COMPLETED: 签收确认
    SHIPPED --> REFUNDING: 售后申请
    REFUNDING --> REFUNDED: 退款成功
    REFUNDING --> PAID: 退款失败
    COMPLETED --> [*]
    CANCELLED --> [*]
    CLOSED --> [*]
    REFUNDED --> [*]
\`\`\`

## 异常分支

> 当出现状态迁移冲突时，系统会抛出 \`OrderStateConflictException\`，由调用方决定重试或回滚。

\`\`\`java
@Transactional
public void transition(Long orderId, OrderEvent event) {
    OrderEntity order = orderRepository.lockById(orderId);
    OrderStatus next = stateMachine.next(order.getStatus(), event);
    order.setStatus(next);
    orderRepository.update(order);
    auditLogger.record(order, event);
}
\`\`\`

## 与支付回调的对接

- 支付成功 → 推送 \`OrderPaidEvent\`
- 支付失败 → 推送 \`OrderPayFailedEvent\`
- 退款完成 → 推送 \`OrderRefundedEvent\`

详见 [支付集成设计](https://wiki.internal/orderhub/payment)。
`,
  6003: `# 支付回调处理

支付回调是订单生命周期中最敏感的环节。本模块统一接入支付宝、微信、银联三种渠道的回调请求，做幂等校验、签名校验、金额校验后再驱动订单状态机。

## 处理流程

\`\`\`mermaid
sequenceDiagram
    participant PS as 支付渠道
    participant API as CallbackController
    participant SVC as PaymentCallbackService
    participant DB as PostgreSQL
    participant MQ as RabbitMQ
    PS->>API: POST /pay/callback
    API->>SVC: handle(req)
    SVC->>DB: 幂等键查询
    alt 已处理
        SVC-->>API: 直接返回 SUCCESS
    else 未处理
        SVC->>SVC: 验签 + 金额校验
        SVC->>DB: 写入回调记录
        SVC->>MQ: 发布 OrderPaidEvent
        SVC-->>API: SUCCESS
    end
\`\`\`

## 幂等键策略

\`callback_id = MD5(channel + outTradeNo + amount + notifyTime)\`

## 监控指标

- \`payment_callback_total\` —— 回调总数
- \`payment_callback_duplicate\` —— 重复回调数
- \`payment_callback_latency\` —— 处理耗时
`,
  6004: `# 订单表结构

订单相关表共 5 张：\`ci_order\`、\`ci_order_item\`、\`ci_order_log\`、\`ci_order_address\`、\`ci_order_payment\`，通过 \`order_id\` 关联。

## 主表字段说明

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| \`id\` | BIGINT | 主键 |
| \`order_no\` | VARCHAR(32) | 业务订单号 |
| \`user_id\` | BIGINT | 下单用户 |
| \`status\` | VARCHAR(20) | 订单状态 |
| \`total_amount\` | DECIMAL(18,2) | 订单总金额（分） |
| \`paid_at\` | TIMESTAMP | 支付完成时间 |
| \`version\` | INT | 乐观锁版本号 |

## 索引策略

- 主键索引：\`id\`
- 唯一索引：\`order_no\`、\`(user_id, order_no)\`
- 普通索引：\`(status, created_at)\`、\`(user_id, status)\`
- 分区键：\`created_at\` 按月分区

> 单表数据超过 1 亿后建议归档至 \`ci_order_archive\` 历史表。
`,
  6005: `# 优惠券系统

优惠券服务提供 **满减 / 折扣 / 兑换码** 三类权益的发放、核销与对账能力。

## 核心类

- \`CouponTemplate\` —— 券模板，描述面额、有效期、使用门槛
- \`CouponInstance\` —— 券实例，用户领取后的实际权益
- \`CouponLedger\` —— 流水账，记录每次冻结 / 核销 / 退还

## 核销流程

\`\`\`mermaid
flowchart TD
    A[下单请求] --> B[选择券实例]
    B --> C{校验可用}
    C -->|否| D[提示不可用]
    C -->|是| E[冻结券金额]
    E --> F[生成订单]
    F --> G{订单结果}
    G -->|成功| H[核销券]
    G -->|失败| I[解冻券]
\`\`\`

## 关键约束

1. 单笔订单最多叠加 **3 张** 券
2. 券过期前 24 小时通过站内信提醒
3. 退款时按比例退还券金额
`,
  6006: `# 满减规则

满减规则由 \`com.promo.rule.FullReductionRule\` 描述，支持 **阶梯满减 / 每满减 / 满件减** 三种模式。

## 规则结构

\`\`\`json
{
  "id": "FR_20260601",
  "name": "618 大促主会场",
  "type": "LADDER",
  "tiers": [
    { "threshold": 199, "discount": 20 },
    { "threshold": 399, "discount": 50 },
    { "threshold": 699, "discount": 120 }
  ],
  "scope": ["BOOK", "HOME"],
  "priority": 100
}
\`\`\`

## 优先级与互斥

- 同优先级按规则 ID 字典序生效
- 高优先级规则可标记 \`exclusive=true\` 排斥其他优惠
`,
  6007: `# 用户画像集成

营销系统通过 \`UserProfileClient\` 实时获取用户画像，用于个性化优惠推荐。

## 接入方式

\`\`\`java
@FeignClient(name = "user-profile", url = "\${profile.url}")
public interface UserProfileClient {
    @GetMapping("/profiles/{userId}")
    ProfileSnapshot query(@PathVariable Long userId);
}
\`\`\`

## 画像标签

- \`RFM\` —— 最近消费 / 频次 / 金额
- \`LIFECYCLE\` —— 新客 / 活跃 / 沉睡 / 流失
- \`PREFERENCE\` —— 品类偏好、价格敏感度
`,
  6008: `# 指标采集

监控平台通过 Prometheus Exporter 暴露业务指标，支持 **进程级 / 中间件级 / 业务级** 三层埋点。

## 关键指标

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| \`http_request_duration_seconds\` | Histogram | HTTP 接口 P50 / P95 / P99 |
| \`jvm_memory_used_bytes\` | Gauge | JVM 各分区内存使用 |
| \`biz_order_created_total\` | Counter | 订单创建总数 |
`,
  6009: `# 告警规则

告警规则由 Prometheus + Alertmanager 组合实现。

## 规则示例

\`\`\`yaml
groups:
  - name: order
    rules:
      - alert: OrderCreateFailureRateHigh
        expr: rate(order_create_failed_total[5m]) / rate(order_create_total[5m]) > 0.05
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "订单创建失败率超过 5%"
\`\`\`

## 通知渠道

- 钉钉群机器人
- 企业微信 webhook
- 电话值班（仅 P0 / P1）
`,
} as Record<number, string>;

/* ============================================================
 * 草稿实体与目录树（关键：通过同一个 store 维护一致性）
 * ============================================================ */

const draftStore = new Map<number, KnowledgeDraft>();

function buildDraft(
  id: number,
  workspaceId: number,
  parentId: number | null,
  filePath: string,
  moduleName: string,
  status: string,
  sortOrder: number,
  isFolder: boolean,
  createdDate: string,
  updatedDate: string,
): KnowledgeDraft {
  const draft: KnowledgeDraft = {
    id,
    workspaceId,
    parentId,
    filePath,
    moduleName,
    contentUri: `mock://drafts/${id}.md`,
    status: isFolder ? 'DRAFT' : status,
    sortOrder,
    hash: isFolder ? '' : `mock-hash-${id}`,
    createdDate,
    updatedDate,
  };
  draftStore.set(id, draft);
  return draft;
}

// 工作区 5001（系统 A：订单中心 INITIAL）
buildDraft(6101, 5001, null, 'order-service', '订单服务', 'DRAFT', 1, true, '2026-06-27T09:15:00.000Z', '2026-06-28T11:18:00.000Z');
buildDraft(6102, 5001, 6101, 'order-service/overview.md', '订单服务总览', 'DRAFT', 1, false, '2026-06-27T09:15:01.000Z', '2026-06-28T11:18:00.000Z');
buildDraft(6103, 5001, 6101, 'order-service/state-machine.md', '订单状态机', 'EDITING', 2, false, '2026-06-27T09:15:02.000Z', '2026-06-28T11:18:00.000Z');
buildDraft(6104, 5001, 6101, 'order-service/payment-callback.md', '支付回调处理', 'DRAFT', 3, false, '2026-06-27T09:15:03.000Z', '2026-06-28T11:18:00.000Z');
buildDraft(6105, 5001, null, 'storage', '数据存储', 'DRAFT', 2, true, '2026-06-27T09:15:04.000Z', '2026-06-28T11:18:00.000Z');
buildDraft(6106, 5001, 6105, 'storage/order-table.md', '订单表结构', 'DRAFT', 1, false, '2026-06-27T09:15:05.000Z', '2026-06-28T11:18:00.000Z');
buildDraft(6107, 5001, null, 'docs', 'API 文档', 'DRAFT', 3, false, '2026-06-27T09:15:06.000Z', '2026-06-28T11:18:00.000Z');

// 工作区 5002（系统 B：营销引擎 INCREMENTAL）
// 6203 是 DRAFT 状态，用于演示"复核人确认 → 仍可继续编辑修改"的流程。
buildDraft(6201, 5002, null, 'coupon-system.md', '优惠券系统', 'DRAFT', 1, false, '2026-06-28T08:01:00.000Z', '2026-06-28T08:01:00.000Z');
buildDraft(6202, 5002, null, 'full-reduction.md', '满减规则', 'DRAFT', 2, false, '2026-06-28T08:01:01.000Z', '2026-06-28T08:01:01.000Z');
buildDraft(6203, 5002, null, 'user-profile.md', '用户画像集成', 'DRAFT', 3, false, '2026-06-28T08:01:02.000Z', '2026-06-28T08:01:02.000Z');

// 工作区 5003（系统 C：监控平台 INITIAL，已确认只读）
buildDraft(6301, 5003, null, 'metrics.md', '指标采集', 'CONFIRMED', 1, false, '2026-06-25T14:03:00.000Z', '2026-06-26T16:20:41.200Z');
buildDraft(6302, 5003, null, 'alerts.md', '告警规则', 'CONFIRMED', 2, false, '2026-06-25T14:03:01.000Z', '2026-06-26T16:20:41.200Z');

/* ============================================================
 * 修订 / 意见 / 代码来源
 * ============================================================ */

const revisions: DraftRevision[] = [
  // 订单服务总览
  { id: nextRevId(), draftId: 6102, contentUri: 'mock://drafts/6102.md', author: 'AI', remark: 'AI 初次生成', createdDate: '2026-06-27T09:15:01.000Z' },
  { id: nextRevId(), draftId: 6102, contentUri: 'mock://drafts/6102.md', author: '张伟', remark: '补充索引策略章节', createdDate: '2026-06-27T11:20:00.000Z' },
  { id: nextRevId(), draftId: 6102, contentUri: 'mock://drafts/6102.md', author: '张伟', remark: '修复表格换行问题', createdDate: '2026-06-28T09:05:00.000Z' },
  // 订单状态机
  { id: nextRevId(), draftId: 6103, contentUri: 'mock://drafts/6103.md', author: 'AI', remark: 'AI 初次生成', createdDate: '2026-06-27T09:15:02.000Z' },
  { id: nextRevId(), draftId: 6103, contentUri: 'mock://drafts/6103.md', author: '李婷', remark: '调整状态图为 v2 语法', createdDate: '2026-06-28T10:00:00.000Z' },
  // 优惠券系统
  { id: nextRevId(), draftId: 6201, contentUri: 'mock://drafts/6201.md', author: 'AI', remark: 'AI 初次生成', createdDate: '2026-06-28T08:01:00.000Z' },
];

const comments: DraftReviewComment[] = [
  { id: nextCmtId(), draftId: 6102, author: '王总监', comment: '建议补充订单合并支付的设计思路，未来是个高频场景。', type: 'NORMAL', createdDate: '2026-06-27T14:32:00.000Z' },
  { id: nextCmtId(), draftId: 6102, author: '张伟', comment: '已加入待优化项清单，谢谢提醒。', type: 'NORMAL', createdDate: '2026-06-27T15:01:00.000Z' },
  { id: nextCmtId(), draftId: 6103, author: '王总监', comment: '请补充异常分支的代码示例，便于新人理解。', type: 'NORMAL', createdDate: '2026-06-28T10:20:00.000Z' },
  { id: nextCmtId(), draftId: 6201, author: '陈', comment: '券叠加规则需要和反作弊团队再 review 一遍。', type: 'NORMAL', createdDate: '2026-06-28T09:45:00.000Z' },
  { id: nextCmtId(), draftId: 6106, author: '王总监', comment: '表结构与索引说明清晰，已通过审核。', type: 'PASS', createdDate: '2026-06-28T09:30:00.000Z' },
  // 6203 的复核提示：v0.3 起不再用驳回机制，复核人通过直接编辑修改草稿
  { id: nextCmtId(), draftId: 6203, author: '陈', comment: '建议补充风控接入说明，便于读者了解与反作弊团队的对接流程。', type: 'NORMAL', createdDate: '2026-06-28T09:30:00.000Z' },
];

const references: DraftSourceReference[] = [
  { id: nextRefId(), draftId: 6102, filePath: 'orderhub/order-service/src/main/java/com/orderhub/OrderService.java', startLine: 1, endLine: 80, createdDate: '2026-06-27T09:15:01.000Z' },
  { id: nextRefId(), draftId: 6102, filePath: 'orderhub/order-service/src/main/java/com/orderhub/state/OrderStatusMachine.java', startLine: 1, endLine: 120, createdDate: '2026-06-27T09:15:01.000Z' },
  { id: nextRefId(), draftId: 6103, filePath: 'orderhub/order-service/src/main/java/com/orderhub/state/OrderStatusMachine.java', startLine: 30, endLine: 150, createdDate: '2026-06-27T09:15:02.000Z' },
  { id: nextRefId(), draftId: 6104, filePath: 'orderhub/payment-service/src/main/java/com/orderhub/pay/PaymentCallbackService.java', startLine: 40, endLine: 220, createdDate: '2026-06-27T09:15:03.000Z' },
  { id: nextRefId(), draftId: 6106, filePath: 'orderhub/order-repository/src/main/resources/db/schema.sql', startLine: 100, endLine: 180, createdDate: '2026-06-27T09:15:05.000Z' },
  { id: nextRefId(), draftId: 6201, filePath: 'promo/coupon-service/src/main/java/com/promo/coupon/CouponService.java', startLine: 1, endLine: 90, createdDate: '2026-06-28T08:01:00.000Z' },
  { id: nextRefId(), draftId: 6202, filePath: 'promo/rule-engine/src/main/java/com/promo/rule/FullReductionRule.java', startLine: 1, endLine: 60, createdDate: '2026-06-28T08:01:01.000Z' },
  { id: nextRefId(), draftId: 6203, filePath: 'promo/user-integration/src/main/java/com/promo/profile/UserProfileClient.java', startLine: 1, endLine: 40, createdDate: '2026-06-28T08:01:02.000Z' },
  { id: nextRefId(), draftId: 6301, filePath: 'monitor/exporter/src/main/java/com/monitor/MetricsExporter.java', startLine: 1, endLine: 70, createdDate: '2026-06-25T14:03:00.000Z' },
];

/* ============================================================
 * 内容缓存（运行时修改）
 * ============================================================ */

const contentStore = new Map<number, string>(Object.entries(CONTENT).map(([k, v]) => [Number(k), v]));

/* ============================================================
 * Mock 启用判定
 * ============================================================ */

export function isMockEnabled(): boolean {
  // 1) 环境变量（构建时注入）
  const envFlag = (import.meta as any)?.env?.VITE_USE_MOCK;
  if (envFlag === 'true' || envFlag === true) return true;
  // 2) URL 参数 ?demo=1
  if (typeof window !== 'undefined') {
    const params = new URLSearchParams(window.location.search);
    if (params.get('demo') === '1') return true;
  }
  // 3) sessionStorage 持久化开关（页面级 UI 切换）
  if (typeof window !== 'undefined' && window.sessionStorage.getItem('ci-demo-mode') === '1') return true;
  return false;
}

export function setMockEnabled(enabled: boolean): void {
  if (typeof window === 'undefined') return;
  if (enabled) window.sessionStorage.setItem('ci-demo-mode', '1');
  else window.sessionStorage.removeItem('ci-demo-mode');
}

/* ============================================================
 * Mock API 实现
 * ============================================================ */

const wait = (ms = 200) => new Promise<void>((r) => setTimeout(r, ms));

export async function mockListPreviewSystems(): Promise<PreviewSystemDto[]> {
  await wait(120);
  // 按系统聚合任务数
  const systemsMap = new Map<number, PreviewSystemDto>();
  for (const t of tasks) {
    let sys = systemsMap.get(t.systemId);
    if (!sys) {
      sys = {
        systemId: t.systemId,
        systemName: '',
        component: '',
        owner: '',
        status: 1,
        pendingReviewCount: 0,
        reviewingCount: 0,
        confirmedCount: 0,
        totalReviewableCount: 0,
      };
      systemsMap.set(t.systemId, sys);
    }
    if (t.status === 'PENDING_REVIEW') sys.pendingReviewCount += 1;
    else if (t.status === 'REVIEWING') sys.reviewingCount += 1;
    else if (t.status === 'CONFIRMED') sys.confirmedCount += 1;
  }
  // 注入元数据
  const meta: Record<number, { name: string; owner: string }> = {
    1: { name: '订单中心 OrderHub', owner: '张伟' },
    2: { name: '营销引擎 PromoEngine', owner: '李婷' },
    3: { name: '监控平台 WatchTower', owner: '陈昊' },
  };
  const list: PreviewSystemDto[] = [];
  for (const [id, sys] of systemsMap) {
    sys.systemName = meta[id]?.name ?? `系统 #${id}`;
    sys.owner = meta[id]?.owner ?? '未指定';
    sys.totalReviewableCount = sys.pendingReviewCount + sys.reviewingCount + sys.confirmedCount;
    list.push(sys);
  }
  return list.sort((a, b) => b.totalReviewableCount - a.totalReviewableCount);
}

export async function mockListReviewableTasks(params: {
  systemId?: number;
  status?: string;
}): Promise<Task[]> {
  await wait(120);
  const statuses = params.status
    ? params.status.split(',').map((s) => s.trim()).filter(Boolean)
    : ['PENDING_REVIEW', 'REVIEWING', 'CONFIRMED'];
  return tasks
    .filter((t) => (params.systemId == null ? true : t.systemId === params.systemId))
    .filter((t) => statuses.includes(t.status))
    .sort((a, b) => (a.updatedDate < b.updatedDate ? 1 : -1));
}

export async function mockGetWorkspaceByTask(taskId: number): Promise<{ workspace: DraftWorkspace; drafts: KnowledgeDraft[] }> {
  await wait(80);
  const workspace = workspaces.find((w) => w.taskId === taskId);
  if (!workspace) throw new Error(`找不到任务 ${taskId} 对应的工作区`);
  const drafts = Array.from(draftStore.values()).filter((d) => d.workspaceId === workspace.id);
  return { workspace, drafts };
}

export async function mockGetWorkspaceTree(workspaceId: number): Promise<DraftTreeNode[]> {
  await wait(80);
  const all = Array.from(draftStore.values()).filter((d) => d.workspaceId === workspaceId);
  if (all.length === 0) return [];
  // 文件夹 = parentId=null 且带子节点的根；叶子 = 真正的草稿
  // 简化：按文件路径前缀判定父子（与后端 parent_id 自引用行为一致）
  const sorted = [...all].sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id);
  const map = new Map<number, DraftTreeNode>();
  for (const d of sorted) {
    map.set(d.id, {
      id: d.id,
      parentId: d.parentId,
      workspaceId: d.workspaceId,
      moduleName: d.moduleName,
      status: d.status,
      filePath: d.filePath,
      sortOrder: d.sortOrder,
      isFolder: false,
      children: [],
    });
  }
  const roots: DraftTreeNode[] = [];
  for (const d of sorted) {
    const node = map.get(d.id)!;
    if (d.parentId != null && map.has(d.parentId)) {
      const parent = map.get(d.parentId)!;
      // 把 parent 标记为 folder
      parent.isFolder = true;
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  }
  return roots;
}

/** 演示模式下的编辑锁（单用户始终可持有） */
const mockEditLocks = new Map<number, string>();

function mockLockHolder(author?: string): string {
  return (author && author.trim()) || 'Admin';
}

export async function mockAcquireDraftEditLock(draftId: number, author?: string): Promise<void> {
  await wait(40);
  const holder = mockLockHolder(author);
  const current = mockEditLocks.get(draftId);
  if (current && current !== holder) {
    throw new Error('该草稿正由其他用户编辑中，请稍后再试');
  }
  mockEditLocks.set(draftId, holder);
}

export async function mockRenewDraftEditLock(draftId: number, author?: string): Promise<void> {
  await wait(20);
  const holder = mockLockHolder(author);
  if (mockEditLocks.get(draftId) !== holder) {
    throw new Error('编辑锁已失效或被他人占用');
  }
}

export async function mockReleaseDraftEditLock(draftId: number, author?: string): Promise<void> {
  await wait(20);
  const holder = mockLockHolder(author);
  if (mockEditLocks.get(draftId) === holder) {
    mockEditLocks.delete(draftId);
  }
}

export async function mockGetDraftContent(draftId: number): Promise<string> {
  await wait(60);
  return contentStore.get(draftId) ?? `# (草稿 #${draftId})\n\n*演示模式下暂无内容，可直接编辑。*`;
}

export async function mockSaveDraft(draftId: number, content: string, author?: string, remark?: string): Promise<void> {
  await wait(220);
  contentStore.set(draftId, content);
  const d = draftStore.get(draftId);
  if (d) {
    d.updatedDate = nowIso();
    draftStore.set(draftId, d);
  }
  revisions.unshift({
    id: nextRevId(),
    draftId,
    contentUri: `mock://drafts/${draftId}.md`,
    author: author ?? 'Admin',
    remark: remark || '人工保存',
    createdDate: nowIso(),
  });
}

export async function mockAutoSaveDraft(draftId: number, content: string, _author?: string): Promise<void> {
  await wait(80);
  contentStore.set(draftId, content);
  // 自动保存不产生修订记录，仅更新 updated_at（避免列表噪音）
  const d = draftStore.get(draftId);
  if (d) {
    d.updatedDate = nowIso();
    draftStore.set(draftId, d);
  }
}

export async function mockConfirmDraft(draftId: number, author?: string, comment?: string): Promise<void> {
  await wait(220);
  const d = draftStore.get(draftId);
  if (!d) throw new Error(`草稿 ${draftId} 不存在`);
  d.status = 'CONFIRMED';
  d.updatedDate = nowIso();
  draftStore.set(draftId, d);
  revisions.unshift({
    id: nextRevId(),
    draftId,
    contentUri: `mock://drafts/${draftId}.md`,
    author: author ?? 'Admin',
    remark: '复核通过 · 已确认',
    createdDate: nowIso(),
  });
  // 填写了通过意见则写入复核意见表（type=PASS）
  if (comment && comment.trim()) {
    comments.unshift({
      id: nextCmtId(),
      draftId,
      author: author ?? 'Admin',
      comment: comment.trim(),
      type: 'PASS',
      createdDate: nowIso(),
    });
  }
}

export async function mockRejectDraft(_draftId: number, _comment: string, _author?: string): Promise<void> {
  // v0.3 起驳回接口已移除，本函数保留作为占位避免旧 mock 调用崩溃；调用方已不再触发此分支。
  await wait(80);
}

export async function mockGetRevisions(draftId: number): Promise<DraftRevision[]> {
  await wait(80);
  return revisions
    .filter((r) => r.draftId === draftId)
    .sort((a, b) => (a.createdDate < b.createdDate ? 1 : -1));
}

export async function mockGetComments(draftId: number): Promise<DraftReviewComment[]> {
  await wait(80);
  return comments
    .filter((c) => c.draftId === draftId)
    .sort((a, b) => (a.createdDate < b.createdDate ? 1 : -1));
}

/**
 * 任务级复核意见聚合：与真后端 listTaskComments 对齐的演示实现。
 * 通过 taskId 找到 workspaceId，再聚合该工作区下所有草稿的评论，附 moduleName / filePath。
 */
export async function mockListTaskComments(
  taskId: number,
): Promise<import('../draft').TaskCommentDto[]> {
  await wait(80);
  const ws = workspaces.find((w) => w.taskId === taskId);
  if (!ws) return [];
  const draftMap = new Map<number, KnowledgeDraft>();
  for (const d of Array.from(draftStore.values())) {
    if (d.workspaceId === ws.id) draftMap.set(d.id, d);
  }
  return comments
    .filter((c) => draftMap.has(c.draftId))
    .sort((a, b) => (a.createdDate < b.createdDate ? 1 : -1))
    .map((c) => {
      const d = draftMap.get(c.draftId)!;
      return {
        id: c.id,
        draftId: c.draftId,
        moduleName: d.moduleName,
        filePath: d.filePath,
        author: c.author,
        comment: c.comment,
        type: c.type,
        createdDate: c.createdDate,
      };
    });
}

export async function mockGetReferences(draftId: number): Promise<DraftSourceReference[]> {
  await wait(80);
  return references.filter((r) => r.draftId === draftId);
}

/**
 * 全局就绪度查询（演示模式）：
 * 遍历 draftStore 中所有非终态草稿，按 updated_at desc 排序后返回。
 * 与后端 DraftService.findGlobalReadiness() 行为一致；演示模式下可以无后端体验拦截流程。
 */
export async function mockGetRepositoryReadiness(): Promise<RepositoryReadiness> {
  await wait(120);
  const TERMINAL = new Set(['CONFIRMED', 'PUSHED', 'ARCHIVED']);
  const blocking: RepositoryReadiness['blockingDrafts'] = [];
  for (const d of Array.from(draftStore.values())) {
    if (!TERMINAL.has(d.status)) {
      blocking.push({
        draftId: d.id,
        moduleName: d.moduleName,
        status: d.status,
        workspaceId: d.workspaceId,
        updatedDate: d.updatedDate,
      });
    }
  }
  blocking.sort((a, b) => (a.updatedDate < b.updatedDate ? 1 : -1));
  // 把 workspace 关联信息补全（taskId / systemId / repositoryId）
  for (const item of blocking) {
    const ws = workspaces.find((w) => w.id === item.workspaceId);
    if (ws) {
      item.taskId = ws.taskId;
      item.systemId = ws.systemId;
      item.repositoryId = ws.repositoryId;
    }
  }
  return {
    ready: blocking.length === 0,
    unconfirmedCount: blocking.length,
    blockingDrafts: blocking,
  };
}

/**
 * 列出指定系统下的全部任务（含历史 PUSHED / ARCHIVED / FAILED / CANCELLED），
 * 不限制状态，便于复核页面切换浏览历史任务。
 */
export async function mockListAllTasksBySystem(systemId: number): Promise<Task[]> {
  await wait(120);
  return tasks
    .filter((t) => t.systemId === systemId)
    .sort((a, b) => (a.updatedDate < b.updatedDate ? 1 : -1));
}
