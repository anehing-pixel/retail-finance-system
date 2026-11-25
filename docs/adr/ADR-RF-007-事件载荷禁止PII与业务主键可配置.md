---
schema: 1
id: ADR-RF-007
title: "事件载荷禁止 PII 与业务主键可配置"
type: adr
status: accepted
date: "2026-11-22"
updated: "2026-11-22"
owner: 架构侧
deciders: ["架构侧"]
lang: zh-CN
---

# ADR-RF-007：事件载荷禁止 PII 与业务主键可配置

## 文档控制

| 项 | 值 |
|---|---|
| 文档编号 | `ADR-RF-007` |
| 状态 | accepted |
| 决策日期 | 2026-11-22 |
| 最近更新 | 2026-11-22 |
| 决策人 | 架构侧 |
| 关系 | — |

## 状态

**Accepted**（2026-11-22） · 影响：`R09 §5`、`R03 §4` 切断点 ①、`R02 §8` INV-4、`R08 §5` PII-4

## 背景

- 公司中台的 Outbox 机制（`V21` `outbox_events` + `V26` `event_consumptions`/`consumer_offsets`）
  值得复用：本地消息表 + DB 轮询、两层幂等、游标按 consumer、3 次失败置 DEAD。
- 但 floorplan 的 `common/event/JsonPayloadReader.requireVinAndTraceId()` **硬编码 payload 必含 `vin` + `traceId`**，
  `LoanCreatedCommand` 以 `vin/advanceId` 为键，`V21` 迁移注释同口径。
- C 端事件主键是 `applicationId` / `contractId` / `loanId` / `releaseOrderId` / `paymentRef`，**没有 VIN 业务主键**。
- 更严重的是：`outbox_events` 表**跨模块共享且 `SENT` 行永久保留**。一旦为绕过校验把客户身份塞进 payload，
  等于把 PII 写进**无法撤回**的永久留痕消息表 —— 这是合规事故，不是技术债。
- APPI 的向第三方提供、使用目的、安全管理措施要求 PII 面最小化（`R08 §5`）。

## 结论

1. **切断 `vin` 硬编码**：`requireVinAndTraceId()` → `requireBizKeyAndTraceId(bizKeyName)`，
   默认 `applicationId`，按事件类型配置业务主键；**保留必填校验**但不再绑车型。
   `LoanCreatedCommand` 不复用，事件以 `applicationId/contractId/loanId` 为键。
2. **payload 白名单（默认拒绝）**：只允许元数据、内部 ID、`status`/`outcomeCode`、`amount`/`currency`、
   日期、幂等/来源等技术字段（`R09 §5.2`）。**白名单外的键一律拒绝发布**。
3. **明确禁止键**（`R09 §5.3`）：姓名、住址、出生日期、电话、email、工作单位、年收入、身份核验材料号、
   账户号码、个人编号、`encrypted_*`、附件内容、`rawPayload` 等。
   发布前由 `OutboxPublisher` 校验，命中即抛异常 + 写审计，**不写库**。
4. 需要客户信息时只传 `customerId` 等**内部非 PII 引用**，消费方按需经 `PiiService` 授权读取；
   原始报文/附件只传 `attachmentId` / `ref`。
5. `aggregate_key` 规则沿用公司方法论：一次发生一个值，禁裸 `vin` / `traceId` / `event_type` 单键 / 每次换新值。
6. INV-4 落地为可证伪实验：扫描全部 outbox payload 键，白名单外命中即失败（`R10` A6）。

## 后果

**变容易**：PII 泄漏面收敛到发布前一处校验；事件表可安全永久保留；跨模块排障仍靠 `traceId` + 内部 ID；
消费方按需授权读 PII，访问可审计。

**变难**：消费方需要客户信息时多一次 `PiiService` 调用（且无 JWT 上下文，需系统设计 authorized 读取通道）；
白名单要随新事件维护，漏登记会阻塞发布；切断 `vin` 后不能照抄 floorplan 的事件契约。

**明确不做**：不做「先写进去再脱敏」（SENT 行永久保留，事后无法撤回）；
不采用确定性加密把 PII 塞进 payload；不把「默认放行 + 黑名单」当白名单用；
不复用 `requireVinAndTraceId`。

## 被拒选项与理由

| 选项 | 理由 |
|---|---|
| 直接复用 `requireVinAndTraceId`，C 端事件也塞个 vin | C 端无 VIN 业务主键；且会诱导把客户身份写进永久消息表 |
| 用黑名单过滤 PII | 新字段/新命名会漏；默认放行等于把风险交给后来人 |
| 事件里带脱敏后的 PII 方便消费方 | 脱敏值仍属個人関連信息，且 outbox 永久保留，无必要 |
| 把 PII 加密后放进 payload | 密钥轮换与永久留痕冲突；消费方无授权上下文，等于绕过 `PiiService` |
| 删掉必填校验图省事 | 会失去「业务主键 + traceId」的跨模块排障能力 |

## 权衡

放弃「payload 里什么都带最方便」，换取**不可撤回留痕面的合规安全**。
本决策是 P-6（PII 最小面）与「预防优于补救」的直接落地：对永久保留的数据，唯一可靠手段是不让它进来。
