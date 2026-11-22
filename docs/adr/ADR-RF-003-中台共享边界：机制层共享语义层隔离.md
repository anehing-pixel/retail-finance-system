# ADR-RF-003：中台共享边界 — 机制层共享、语义层隔离

## 状态

**Accepted**（2026-11-22） · 影响：`R03` 全篇、`R00 §5 §8`、`R11 §3 W1`

## 背景

用户明确「本系统和 floorplan 项目是同一家公司的，**共享中台能力**」。实查 `../floorplan-system` 后确认：

- 可复用的**机制层**确实丰富：DDD 四层、精确装配、`ApiResponse`/异常骨架、JWT/RBAC 机制、
  Transactional Outbox、幂等、营业日历、分布式锁、AES-GCM、附件端口、金额/时间/审计约定、
  `AGENTS/PROGRESS/docs` 工程治理范式。
- 但**语义层**以「法人经销商 + VIN + 让渡担保 + 在库台账」为轴心，且 floorplan 已把三条边界
  写进定源：`DealerApplicationService.java:30-31`（借款人是法人，不适用个人借款总量规制）、
  `ADR-业务口径总定源.md` **Q12（本轮不采集个人身份信息）**、**Q15（日本征信报送本轮不接）**，
  `ADR-BD` 的 `D-02`（适用法规不给默认值、须法务意见书）、USED 需求书明确「不做个人消费者分期」。
- 这三处恰是 C 端业务的**核心**：自然人 PII、个人征信局双向对接、C 端合规。

⟹ 共享中台若按「把 jar 加进依赖」理解，会造出「代码能跑、合规上已经出事」的系统。

## 结论

**机制层共享、语义层隔离**（`R03` 四分类）：

| 分类 | 内容 |
|---|---|
| ✅ 直接复用 | DDD 四层/精确装配、响应与异常骨架、JWT **机制**、Outbox **机制**、幂等、营业日历、锁、加密底座、附件端口、公司级数据约定、工程治理范式 |
| ⚠️ 扩展后复用 | `ErrorCode` 内容、`Permission`/`UserRole`/SOD、字段级 PII 加密、HMAC 验签（per-partner + nonce + body 摘要）、`audit_events` 主体类型、`PaymentGateway` 实现、DMZ partner 模型 |
| ❌ 必须新建 | 自然人客户 + PII 域、个人征信局双向对接、C 端合规/法务适配、客户侧身份认证、分期产品/合同/还款模型、所有权保留解除链路、通知/催收合规 |
| 🛑 明确废弃 | 让渡担保登记与 Q21 门禁、放车放证、经销商循环额度、在库台账/库龄递减/盘点、R1–R8 对拍、B2B 事件契约 |

**三处隐性冲突必须显式切断**（`R03 §4`）：

1. `JsonPayloadReader.requireVinAndTraceId()` → 改 `requireBizKeyAndTraceId(bizKeyName)`（`ADR-RF-007`）；
2. `Dealer` / `CreditLine` 法人假设 → 不 import，新建 `Customer`(PII) 与 `AffordabilityAssessment`；
3. 员工 RBAC 套到客户 → 双主体（`ADR-RF-004`）。

**落地形态**：S1 抽取新 artifact `company-platform-contracts`（`com.company.platform.*`），
**只放机制、不放任何业务枚举/实体/规则**；**新增 artifact，不改 floorplan 现有包**，
避免破坏其 0 bug / 全量冒烟基线。两项目为 Partnership 关系，共享 jar 版本、端口段、号段、前缀共同登记。

## 后果

**变容易**：两项目共用公司级标准（金额/时间/审计/响应/幂等/日历），跨项目可读；
floorplan 不受影响；C 端合规边界清晰可审计；复用范围可逐条勾验（`R03 §8`）。

**变难**：共享 jar 成为双项目耦合点，不兼容变更需大版本 + 双方评审；
抽取 W1 是所有后续包的前置依赖；「机制 vs 语义」的边界需要评审守护，否则会被「图方便」侵蚀。

**明确不做**：不 import `com.company.floorplan.*`；不共享数据库实例；不复用 JWT 密钥；
不平移 floorplan 已裁决的 Q1–Q30 / Q21 / Q27 / R5–R8；不把「共享中台」读成「领域模型可复用」。

## 被拒选项与理由

| 选项 | 理由 |
|---|---|
| 全量复用 floorplan 领域模型（Dealer/CreditLine/VehicleUnit） | 法人/VIN/让渡担保语义污染自然人业务，Q12/Q15 合规事故 |
| 完全不复用、另起炉灶 | 丢弃公司级标准与工程治理，两项目技术栈漂移，维护成本上升 |
| 把共享机制留在 floorplan 仓里直接依赖 | 会把 floorplan 变成 C 端的构建前置，且无法切断三处隐性冲突 |
| 共享同一个数据库 / schema | 「每类数据一个权威写者」失效，PII 无法独立授权（`ADR-RF-009`） |

## 权衡

放弃「最大化代码复用率」，换取**合规正确性**与**两项目独立演进**。
共享的是**约定与机制**（低成本、高价值），隔离的是**语义与数据**（高风险、低复用价值）。
