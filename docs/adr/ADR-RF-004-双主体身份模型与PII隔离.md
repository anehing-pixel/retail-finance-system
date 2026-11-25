---
schema: 1
id: ADR-RF-004
title: "双主体身份模型与 PII 隔离"
type: adr
status: accepted
date: "2026-11-22"
updated: "2026-11-23"
owner: 架构侧
deciders: ["架构侧"]
lang: zh-CN
---

# ADR-RF-004：双主体身份模型与 PII 隔离

## 文档控制

| 项 | 值 |
|---|---|
| 文档编号 | `ADR-RF-004` |
| 状态 | accepted |
| 决策日期 | 2026-11-22 |
| 最近更新 | 2026-11-23 |
| 决策人 | 架构侧 |
| 关系 | — |

## 状态

**Accepted**（2026-11-22） · 影响：`R06 §3 §5`、`R08 §2 §5 §6`、`R03 §4` 切断点 ③

## 背景

- floorplan 的认证体系是**内部员工**：14 个 `UserRole` 全是岗位、种子账号 `admin/admin123`、
  登录页是运营控制台、`audit_events.actor` = 操作员工号、`JwtAuthInterceptor` 把 `perms` 放进 token。
- 本项目多了**外部自然人主体**：消费者要申请、取得/撤回同意、查看书面文件、还款、行使查阅请求。
- 若把客户当内部员工发 `UserRole` token，或把客户自助操作记成员工 `actor`，
  **审计链与 APPI 同时失效**：无法证明「这条 PII 访问是客户本人还是员工代办」。
- APPI 要求使用目的特定、向第三方提供同意、查阅/更正/停止使用、保留期限与安全管理措施。
- `outbox_events` 表跨模块共享且 `SENT` 行永久保留，是最容易被忽视的 PII 泄漏面。

## 结论

1. **四类主体**（`actor_subject`）：`CUSTOMER` / `STAFF` / `SYSTEM` / `PARTNER`。
   - 客户侧：`CustomerIdentity`（eKYC / 身份核验 / OTP / 设备绑定），**不设 `UserRole`**；
   - 员工侧：沿用 RBAC **机制**，但 `Permission`/`UserRole` 枚举内容重写（`R08 §3`）；
   - 系统/批处理：作业上下文，无用户 token；
   - 外部合作方：per-partner HMAC / mTLS + scope，**只到 DMZ**。
2. **两套 JWT 完全隔离**：独立 issuer + audience + 签名密钥
   （`RETAIL_JWT_SECRET` 员工侧 / `RETAIL_CUSTOMER_JWT_SECRET` 客户侧）+ 独立过期策略。
   客户 token 访问员工端点 → 401/403，反之亦然。
3. **PII 独立 `pii` schema + 独立 DB role**：业务角色 `retail_app_rw` **物理无权**直查
   `pii.customer_pii`，只能经 `PiiService`；字段级 AES-256-GCM + HMAC 检索 + `key_version` 轮换。
   **生效前提（2026-11-23 用户口径）**：本地统一以 `postgres` 超级用户连库，超级用户绕过全部 ACL，
   本地**不生效**；「物理无权」是**生产侧兜底**，兑现需**按角色分配连接账号**（`R06 §3.1`）。
4. **PII 七条控制**（`R08 §5`）：最小返回 / 对外不回传 / 不入日志 / 不进 outbox / 独立 role /
   全量查看需授权留痕 / 保留期与匿名化。`audit.pii_access_logs` 记「谁/何时/哪个 customer/哪些字段/用途」，**不记明文值**。
5. 默认**不采集个人编号**（L-8）；如需另建隔离域。

## 后果

**变容易**：审计能区分客户自助 / 员工代办 / 系统 / 合作方；APPI 的同意、查阅、撤回有明确落点；
DB role 在物理层兜住「图方便直查 PII」（生产侧兑现，前提是按角色分配连接账号，`R06 §3.1`）；密钥轮换不影响 JWT 与账号加密。

**变难**：客户侧要自建身份与会话体系（不能白嫖员工 RBAC）；PII 查询多一跳 `PiiService`，有性能损耗；
两套 token 的密钥管理、轮换、撤销要分别做；`pii` 独立 role 增加连接池与运维复杂度。

**明确不做**：不把 `RequirePermission` / `UserRole` 套到客户；不共享 floorplan 的 JWT 密钥；
不在 `pii.customer_pii` 建明文列；不物理删除审计记录。

## 被拒选项与理由

| 选项 | 理由 |
|---|---|
| 客户与员工共用一套 JWT/RBAC | 审计链与 APPI 同时失效（`R03 §4` 切断点 ③） |
| 只做员工侧，客户侧靠店端代操作 | 消费者本人同意、查阅请求无法成立；店端代操作无法自证 |
| PII 与业务表同 schema、靠应用鉴权 | 一次越权 SQL / 一个疏忽的 `SELECT *` 就是泄漏；缺物理兜底 |
| 沿用 floorplan 的 `AccountCryptoService` 直接加密 PII | 它只覆盖银行账号且密钥与账号域共用；PII 需独立密钥与轮换 |

## 权衡

放弃「一套用户体系走天下」的简洁，换取**可证明的合规边界**。
在 C 端个人金融里，「谁看了谁的个人信息」必须能被独立审计，这不是可以优化的开销。
