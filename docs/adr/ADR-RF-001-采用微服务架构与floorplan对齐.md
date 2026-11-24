# ADR-RF-001：采用微服务架构，与 floorplan 对齐

## 状态

**Accepted**（2026-11-22） · 影响：`R02` 全篇、`R00 §3.1 §6`、`R09 §7`、`R11 §3`、`README §3`、`AGENTS §4 §5`
> **修订说明**：本 ADR 初版为「模块化单体起步、按触发器演进为微服务」。用户于 2026-11-22 明确指示
> 「floorplan 已经是微服务架构了，直接按照它是微服务架构处理」，故**整体改写**为直接采用微服务架构。
> 初版的「拆分触发器」保留为 §末「服务边界再调整触发器」，但方向从「何时拆」改为「何时合并/再拆」。

## 背景

- 用户明确：**floorplan 已经是微服务架构**，本项目直接按微服务处理，不做「先单体后演进」。
- `floorplan-system` 现状（**不是目标态**）：Spring Cloud Gateway `18080` +
  `web(floorplan-core):8080` / `auth:8081` / `bank:8082` / `credit:8083` / `risk:8084` /
  `loan:8085` / `collateral-reg:8086` / `release:8087` / `recon:8088` / `external-api:18090`，
  DDD 四层共享 jar（`domain`/`application`/`infrastructure`/`common`），
  **精确装配**（`scanBasePackages` + `@Import` 按类注入）、网关路径切流、JWT 同密钥本地验签零信任、
  Transactional Outbox、Flyway 只由 core 跑、冒烟走网关。
- 本项目与 floorplan **同公司、共享中台能力**（`ADR-RF-003`）。直接对齐其微服务形态，
  可原样复用网关切流、精确装配、Feign 门禁、outbox、错误码/幂等/审计约定与 `run-microservices.sh` 工程范式，
  避免另造一套部署与治理体系。
- **核心账务仍是强一致事务**：入金核销 + 余额 + 债权终态 + 还款计划是同一笔本地事务。
  对齐 floorplan「`web` 核心账务不拆」的判据 —— 拆开就要引入分布式事务，在金融账务上是倒退。
- 外部接入（DMS / 征信局 / 支付回调）鉴权模型与内部完全不同，必须独立 DMZ 进程（floorplan ADR-009 五层纵深）。
- C 端新增的**自然人 PII 域**需要独立进程与独立 DB role（`ADR-RF-004`），微服务天然提供这层隔离。

## 结论

### 1. 部署单元（12 个：10 个业务服务 + 网关 + DMZ）

| 服务 | 端口 | 职责 | 数据真相源 |
|---|---|---|---|
| `api-gateway` | 18081 | 按路径切流、`X-Request-Id`、限流（**已实现**：Redis 令牌桶，`ADR-RF-012`）、CORS | — |
| `external-api` | 18091 | **DMZ**：入站验签 / 出站签名 / nonce / partner scope / 调用留痕 | `integration.*` |
| `retail-core` | 8090 | **核心账务（不拆）**：债权/还款计划/代付/入金核销/结清；运行期不跑 Flyway（迁移作业独立） | `loans` `repayment_plans` `schedule_items` `disbursements` `payments` `payment_allocations` |
| `auth-service` | 8091 | 员工侧 + 客户侧**双主体** JWT 签发/验签 | `users` `user_roles` `pii.customer_identities` |
| `customer-service` | 8092 | 自然人主数据 + **PII 金库** + 身份核验 + 同意 | `pii.*` |
| `application-service` | 8093 | 申请受理 + DMS 申请代办 + 审查结果回传 + **销售店主数据唯一投影** | `applications` `application_items` `dealer_partners` `dealer_agreements` |
| `underwriting-service` | 8094 | 审查授信/支付能力/决定/SOD | `underwriting_cases` `affordability_assessments` `income_documents` `decision_rules` |
| `bureau-service` | 8095 | **征信局防腐上下文**：查询 / 登记 / 异议（`ADR-RF-005`） | `credit_inquiries` `integration.credit_bureau_reports` `bureau_disputes` |
| `contract-service` | 8096 | 合同 + 法定书面文件交付留痕 | `contracts` `statutory_documents` |
| `title-release-service` | 8097 | 所有权保留 + **解除八步** + 外部登记回执（`ADR-RF-006`） | `title_retentions` `release_orders` `release_documents` `registration_receipts` |
| `servicing-service` | 8098 | 贷后 DPD / 催收 / 征信报送触发 | `collection_cases` `dpd_snapshots` |
| `recon-service` | 8099 | INV-1~5 对拍与差异（`R10`） | `recon_runs` `recon_diffs` |

端口段 **8090–8099**（原 8090–8098 扩一位给 `recon-service`），网关 18081、DMZ 18091。

### 2. 与 floorplan 对齐的四条工程范式

1. **精确装配**：每服务 `@SpringBootApplication(scanBasePackages = 自身包)` + `@Import` 按类注入
   所需 ApplicationService / Repository；`application`/`infrastructure` 是共享 jar，但每服务只装自己那几个类。
2. **网关切流**：specific 路由在前、`/api/**` 兜底到 `retail-core` 在后；URI 双模
   `${ROUTE_*:lb://服务名}`，本地无 Nacos 时用环境变量覆盖直连。
   `/external/**` 走 **DMZ 独立端口 18091**，不经过内部网关。
3. **JWT 零信任**：员工侧全服务同密钥本地验签，客户侧独立密钥；**网关本阶段不验签**，鉴权下沉各服务。
   Feign 调用必须由 `RequestInterceptor` **透传 JWT**（floorplan CORE verifier 报的 B-9 缺口），
   否则门禁会走 fallback 拒付。
4. **第一版共用一个库**：服务拆开、数据不拆 —— 库 `retail_finance:5435` 内按
   `core` / `pii` / `integration` / `audit` 四个 schema 分组，**不跨库 join、不引入分布式事务**。
   每类数据只有一个权威写者（上表），跨服务只读走 Feign 或事件，**禁止跨域直写他表**。
   独立实例是**相对 floorplan** 而言（`ADR-RF-009`），不是本项目内部再分库。

### 3. 通信

- **同步 Feign 只用于门禁（fail-closed）**：代付执行前 `retail-core` 问
  `contract-service`（合同 EFFECTIVE + 书面文件交付）、`customer-service`（Consent 有效）、
  `underwriting-service`（审查 APPROVED）；任一超时 / UNKNOWN / fallback → **拒绝代付**。
  超时 2s、最多一次幂等重试、fallback 一律返回拒绝而非放行。
- **异步 outbox 用于事后同步**：`LOAN_SETTLED` → `title-release-service`；
  `TITLE_RELEASE_REGISTERED` → `retail-core` + `servicing-service`；
  `CONSENT_WITHDRAWN` → `underwriting-service` / `bureau-service`（**秒级合规刚性**）；
  `REPAYMENT_RECEIVED` → `servicing-service` / `recon-service`。
- **不引 MQ / Seata**；事件 payload 禁 PII（`ADR-RF-007`）。

### 4. 服务与限界上下文的对应

`R02 §5.1` 的 10 个业务上下文按上表落到服务；`retail-core` 内聚 BC-6（债权）与 BC-7（还款），
因为二者是**同一本地事务边界**；`title-release-service` 独立，因为它是跨机构异步协同，
节奏与核心账本完全不同（对齐 floorplan 把放车放证从 core 拆出的判据）。

## 后果

**变容易**：与 floorplan 的部署、网关、装配、鉴权、outbox、冒烟范式完全一致，中台复用是真复用；
PII / 征信 / 解除各有独立进程与故障域；法务形态差异仍收敛在 4 个策略点（`ADR-RF-002`），
形态反转不炸掉服务拓扑；可按服务独立发布与扩容。

**变难**：10 个进程 + 网关 + DMZ 的本地启动、端口、日志、冒烟编排成本显著上升；
跨服务门禁要处理 JWT 透传、超时、fail-closed fallback；第一版共库意味着
「服务已拆、数据未拆」，跨域直写的诱惑需要靠 DB role 与评审约束；
分布式链路排障依赖 `traceId` 贯穿与 `audit_events`；服务间零调用时容易退化成
「分布式单体」，必须用真实 Feign + outbox + 冒烟断言证明链路已通（floorplan 的历史教训）。

**明确不做**：不建「只有启动类、没有能力」的空服务骨架；不跨服务共库直写；
不引 MQ / Seata / 分布式事务；不预先每服务独立库；不把网关当验签点。

## 被拒选项与理由

| 选项 | 理由 |
|---|---|
| **模块化单体起步、按触发器演进为微服务**（本 ADR 初版） | 用户明确否决：floorplan 已是微服务，本项目直接对齐；再走一遍单体→拆分是重复建设 |
| 全量照搬 floorplan 的 9 个服务名（`credit`/`risk`/`bank`/`collateral-reg`…） | 语义层不可复用（`ADR-RF-003`）：C 端没有经销商额度、在库台账、让渡担保、放车放证；服务名必须按 C 端上下文重定 |
| 一步到位每服务独立库 / 分库分表 | 跨库 join 与分布式一致性成本过高；对齐 floorplan「服务拆开、第一版共用一个库」的务实做法 |
| 把所有外部接入塞进 `retail-core` 加 `/external/**` 路径 | 网络隔离形同虚设（floorplan ADR-009 五层纵深） |
| 把核心账务也拆成多个服务 | 入金核销 + 余额 + 债权终态是强一致事务，拆开必须上分布式事务 —— 金融账务红线 |

## 权衡

放弃「起步最省进程数」，换取**与同公司中台完全对齐**与**按上下文的故障域/合规隔离**。
这里的代价是运维与联调复杂度，收益是：复用的是一套已经跑通 200+ 冒烟断言的工程体系，
而不是重新发明部署形态。核心账务仍留在 `retail-core` 不拆 —— 微服务不等于把事务边界也拆开。

## 服务边界再调整触发器（原「拆分触发器」改写）

方向从「何时拆」变为「何时**合并 / 再拆**」：
- 某两个服务每周因同一需求同步发布 ≥2 次且无独立伸缩需求 → 评审合并；
- 某服务峰值流量 ≥ 核心账务 5 倍且拖累共库 → 评审独立 schema / 只读副本；
- 合规要求 PII 独立网络分区 → `customer-service` 已在独立进程，进一步做独立密钥托管；
- 任何再调整的前置条件：**独立表组 + 已走事件/Feign（无共库直写）+ 独立验收断言**。
