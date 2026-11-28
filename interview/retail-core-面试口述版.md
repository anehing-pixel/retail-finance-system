# retail-core 面试口述版

> **用途**：面试口述 / 白板讲解，控制在 2–5 分钟。
> **完整版**：[retail-core-代码流程.md](retail-core-代码流程.md)
> **HTML 图集**：[retail-core-代码流程图集.html](retail-core-代码流程图集.html)
> **依据**：当前 `master` 工作区代码；数字与口径以 `PROGRESS.md` 为准。
> **原则**：先讲边界，再讲主流程，最后讲一致性与取舍；不把“目标态”说成“已实现”。

---

## 0. 30 秒版

> `retail-core` 是核心账务服务，端口 8090，负责债权、还款计划、代付、入金核销、结清和余额对拍。
> 它的特点是把**一个用例的账务写、状态迁移、outbox 事件放在同一个本地事务**里。
> 计息是在**还款计划生成时一次性算定全期利息**并计入应计利息与余额，贷后只做执行。
> 跨服务一致性不是靠分布式事务，而是靠 **outbox、唯一索引、幂等消费和余额对拍**。

---

## 1. 2 分钟版

### 1.1 定位与边界

`retail-core` 是“核心账务不拆”的那个服务：

- 负责：债权、还款计划、代付、入金核销、结清、余额对拍、产品档位
- 不负责：申请、审查、合同、征信查询、催收、所有权解除

为什么核心账务不拆？因为**入金核销 + 余额 + 债权终态必须在同一个本地事务**里完成，
拆开就会出现“钱扣了、余额没减”或“余额减了、期次没销”的半截状态（B-11、INV-2）。

### 1.2 计息

核心公式只有一行：

```text
期次利息 = floor(期初剩余本金 × 月利率基点 ÷ 10000)
```

`rate_bp = 200` 表示月息 2.0%。四种还款方式：

- 等额本息：年金公式定每期总额，逐期模拟本金
- 等额本金：本金均分，利息按递减余额
- 奖金还款：奖金月本金权重 ×2
- 一次性：单期还本付息

关键口径：**不是逐日计息**。计划生成时把全期利息一次性计入 `interest_accrued` 和 `balance`，
后续贷后只做“到期 → 核销 → 逾期 → 结清”。这样做的好处是账务简单、可复现；
代价是余额从第一天起就包含未到期利息，所以提前结清时要把未到期利息作为豁免冲销。

### 1.3 代付

代付入口是 `POST /api/disbursements`：

```text
Idempotency-Key → 合同/审查前置校验（fail-closed）→ 本地事务
  → 锁债权 → DISBURSING → 调银行 → 插代付记录 → ACTIVE → 写 outbox
```

三道幂等：`Idempotency-Key`、`uk_disb_idempotency`、`uk_disb_loan`。

### 1.4 入金核销

核销顺序固定：

```text
费用 → 按期次顺序 → 期内先利息、后本金
```

- 只有 `DUE / PARTIAL / OVERDUE` 可核销，`OPEN` 未到期不可核销
- 拆完还有剩余 → 超额入金，直接拒绝，转工单/退款，不自动分摊
- 核销后做 INV-2 三口径对拍，不一致直接回滚

### 1.5 结清

- 正常结清：末回核销完成、余额精确为 0，只做状态迁移 `SETTLED`
- 提前结清：`quote = 剩余本金 − 减免额 + 手续费`，未到期利息在同一事务内豁免，
  余额归零后进入 `EARLY_SETTLED`
- `SETTLED` 与 `EARLY_SETTLED` 是两个不同的债权终态，不能合并

### 1.6 一致性

项目明确不引入 Seata / 分布式事务。跨服务一致性靠：

- 本地事务 + `SELECT ... FOR UPDATE` + 乐观锁 `version`
- 唯一索引 + `ON CONFLICT`
- outbox 事件与业务写同事务
- `event_consumptions` 消费幂等 + 租约
- 余额对拍 INV-2

状态机只保证**单聚合状态迁移合法**，不负责分布式一致性。

---

## 2. 白板图

```text
HTTP（网关 18081 → retail-core:8090）
  │
  ▼
Controller（权限点 / Idempotency-Key / traceId）
  │
  ▼
ApplicationService（用例编排 / 幂等 / 参数合成）
  │
  ├─► TxExecutor（@Transactional）
  │     ├─ SELECT ... FOR UPDATE（债权行锁）
  │     ├─ Domain（计息 / 核销 / 结清 / 状态机）
  │     ├─ Repository / Mapper → PostgreSQL core schema
  │     └─ outbox（DISBURSEMENT_PAID / REPAYMENT_RECEIVED / LOAN_SETTLED）
  │
  └─► ApiResponse<T>

事务外：
  RfEventDispatcher（1s）→ core.outbox_events → 消费者
  ScheduleDueRollupScheduler（03:00）→ OPEN → DUE → OVERDUE
  产品档位读取 → Caffeine L1 → Redis L2 → DB
```

如果面试官只给你一块白板，画这四层就够了：

```text
Controller → ApplicationService → Domain
                    ↓
             Repository/Mapper → DB
                    ↓
                  outbox → 消费者
```

---

## 3. 五个必讲点

### 3.1 为什么核心账务不拆

- 入金核销、余额更新、期次状态、债权终态必须同一本地事务
- 拆成多个服务就只能上分布式事务，成本和失败模式都不可接受
- 所以 `retail-core` 是“账务不拆”，其他能力（申请/审查/合同/征信/解除）才拆

### 3.2 计息为什么是“一次性计提”

- 公式简单：`floor(余额 × rate_bp / 10000)`
- 计划生成时把 `Σ interestDue` 一次性计入 `interest_accrued` 和 `balance`
- 改产品配置不回改历史计划：`product_code / product_version / rate_bp` 落快照
- 代价：余额包含未到期利息；提前结清要豁免未到期利息
- 这是产品形态决定的：个品分期是固定期次、固定利率，不是短期周转/罚息产品

### 3.3 入金核销顺序

```text
费用 → 期次升序 → 期内先息后本
```

- 费用是债权级 `fee_amount`
- 期次按 `period_no` 升序
- 每期先冲利息，再冲本金
- `OPEN` 不可核销；超额入金拒绝，不自动分摊

### 3.4 提前结清

```text
提前结清金额 = 剩余本金 − 减免额 + 事务手续费
```

- 未到期利息不进报价，而是在同一事务内作为豁免冲销
- 三步：手续费计提 → 结清入金核销 → 豁免记账
- 结果是余额精确归零、期次关闭、`EARLY_SETTLED`
- 正常结清不走这套，只做状态迁移

### 3.5 状态机怎么用

- `LoanStatus` 是领域状态图，Spring StateMachine 只做执行器
- 迁移边由 `LoanStatus.graph()` 生成，引擎侧不另写一份状态图
- 未接线边显式登记（`ACTIVE → DELINQUENT`、`ACTIVE → WRITTEN_OFF` 等）
- 状态机不落库，数据库仍是状态权威
- 它解决“能不能从 A 到 B”，不解决“跨服务数据什么时候一致”

---

## 4. 高频追问

### Q1：为什么不用分布式事务 / Seata？

> 核心账务都在 `retail-core` 一个本地事务里，不需要分布式事务。
> 跨服务用 outbox 做最终一致；MQ 只是削峰层，默认关闭，outbox 仍是真相源。
> 引入 Seata 会把账务一致性押在中间件上，成本和故障面都更大。

### Q2：利息为什么不是按日计？

> 产品是固定期次、固定月利率的个品分期，不是短期周转或逾期罚息产品。
> 按日计提需要每天重算期次、处理幂等补偿，收益很低。
> 当前口径是计划生成时一次性计提，提前结清时豁免未到期利息。

### Q3：利率后来改了，历史计划会变吗？

> 不会。`core.repayment_plans` 有 `product_code / product_version / rate_bp` 快照；
> 改档位是“旧版封口 + 新版插入”，历史计划不重算。
> 这也是为什么不能只存 `productCode` 引用。

### Q4：重复代付 / 重复回调怎么挡？

> 三道幂等：`Idempotency-Key`、`uk_disb_idempotency`、`uk_disb_loan`；
> 状态机再挡一次 `PENDING_DISBURSEMENT / DISBURSE_FAILED → DISBURSING`。
> 外部回调幂等以数据库唯一索引为权威，不靠内存锁。

### Q5：超额入金怎么办？

> 拆完还有剩余，直接拒绝 `RF_PAY_002`，转工单/退款，不自动分摊。
> 这是 X-8 的口径：不替客户猜“多出来的钱算哪期”。

### Q6：提前结清为什么不是“剩余本金 + 剩余利息”？

> 提前结清是提前终止，未到期利息不再向客户收取；
> 所以报价只算剩余本金 − 减免 + 手续费，未到期利息在同一事务内豁免冲销。
> 三段式：手续费计提 → 结清入金 → 豁免记账，最终余额精确归零。

### Q7：余额怎么保证不会错？

> INV-2 三口径对拍：
> 存量余额、流水推导余额、义务面余额（期次未清偿 + 费用未清偿）。
> 核销/结清事务内调用 `assertConsistent`，不一致就 `RF_SYS_001` 回滚。

### Q8：状态机用来干什么？

> 保证单聚合状态迁移合法：不能从终态再迁移、不能重复结清、不能非法代付。
> 它是进程内执行器，不是分布式协调器；跨服务一致性靠 outbox 和幂等。

### Q9：outbox 和 MQ 是什么关系？

> outbox 是真相源，事件与业务写同一本地事务；
> MQ 是 outbox 之上的削峰层，默认关闭，开启后只投递密文信封；
> MQ 不参与本地事务，也不替代 outbox。

### Q10：这套实现有什么边界/不足？

> - 计息不是逐日计提，逾期没有罚息/滞纳金
> - 一份债权只有一个计划版本，不支持计划改版/重算
> - `ACTIVE → DELINQUENT` 状态机未接线，逾期由 servicing 的 DPD/催收承载
> - 征信报送默认是挡板/模拟器，真实机构对接留到联调
> - 银行外呼在本地事务内，外部已受理而本地提交失败时靠幂等键 + 对账收敛

---

## 5. 数字与代码坐标

| 主题 | 数字 / 口径 | 代码 |
|---|---|---|
| 计息公式 | `floor(余额 × rate_bp / 10000)` | `RepaymentScheduleGenerator.interestOf` |
| 月利率基点 | `200 = 2.0%/月` | `PlanTerms.rateBp` / `core.financial_products.rate_bp` |
| 等额本息年金 | `P × r × (1+r)^n ÷ ((1+r)^n − 1)` | `RepaymentScheduleGenerator.annuityPayment` |
| 期次不变式 | `Σ principalDue == principal` | `RepaymentScheduleGeneratorTest` |
| 核销顺序 | 费用 → 利息 → 本金 | `PaymentAllocationPlanner` |
| 提前结清 | 剩余本金 − 减免 + 手续费 | `SettlementCalculator` |
| 余额对拍 | INV-2 三口径精确相等 | `BalanceReconciliation` |
| 代付幂等 | 3 层 | `DisbursementApplicationService` / V6 |
| 债权状态 | 9 态，4 个终态互斥 | `LoanStatus` |
| 债权利率快照 | `product_code + product_version + rate_bp` | `core.repayment_plans`（V18） |
| 事件 | `DISBURSEMENT_PAID` / `REPAYMENT_RECEIVED` / `LOAN_SETTLED` | `OutboxPublisher` |

---

## 6. 不要说的几句话

| 不要说 | 应该说 |
|---|---|
| “这是信用贷” | “个品分期 / 汽车贷款，担保是所有权保留” |
| “用了分布式事务 / Seata” | “本地事务 + outbox 最终一致，不引 Seata” |
| “利息是每天算的” | “计划生成时一次性计提，不是逐日计息” |
| “状态机保证分布式一致性” | “状态机保证单聚合迁移合法；一致性靠 outbox/唯一索引/对拍” |
| “征信报送已经真实对接” | “业务闭环已实现，默认是挡板/模拟器，真实机构待联调” |
| “服务之间都走 HTTP/MQ” | “核心账务在 retail-core 本地事务；贷后靠共享库只读 + outbox” |
| “12 个进程都起了就说明微服务落地” | “走网关冒烟 + Feign 透传 + outbox 消费 + E2E 才能证明链路通” |

---

## 7. 一分钟收尾

> `retail-core` 的核心不是“拆了多少服务”，而是**把该在一个事务里的账务守住**：
> 计息在计划生成时一次性算定，核销按“费用→利息→本金”执行，结清把余额精确归零；
> 代付/核销/结清都走本地事务 + 行锁 + 乐观锁 + 唯一索引；
> 跨服务用 outbox 做最终一致，状态机只管单聚合迁移合法。
> 这套设计的取舍是“账务简单、可复现、可对拍”，代价是计息不逐日、逾期无罚息、
> 计划只支持单版本——这些边界在文档里都写清楚了。
