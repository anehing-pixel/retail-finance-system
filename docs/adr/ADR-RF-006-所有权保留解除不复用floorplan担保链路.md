# ADR-RF-006：所有权保留解除不复用 floorplan 担保链路

## 状态

**Accepted**（2026-11-22） · 影响：`R01 §3`、`R04 §4`、`R05 §6`、`R03 §3.4`

## 背景

- floorplan 的担保/放行链路建立在**在库 B2B 车辆**上：让渡担保、法务局登记
  （`collateral_registration_batches` V10 为唯一权威源，Q27）、Q21「无 `REGISTERED` 硬阻断放款」、
  `collateral-reg-service`、`release-service` 的**放车放证**（`release_order_items` 分 VEHICLE/CERTIFICATE/KEY、
  custodian 按项拆分、`ManualReleaseChannel`、V19/V24）。
- C 端新车已**售出并交付消费者**，不存在「在库」「放车」「交钥匙」「交合格证」；
  信贩控制手段是**所有权保留**（车辆检验合格证「所有者」栏），结清后走
  **解除文件出具 → 运输支局/軽自検協 名义变更 → 登记完成回执**。
- 实务中「所有权解除」一词常被销售店理解为「出具文件的准备」即止；
  若系统把「发解押书」当作「解押完成」，会出现「债权已结清、车辆检验合格证所有者仍是信贩」的账实不符，
  后续转卖/廃車全部卡住。
- 用户所说「解除抵押」是中文简化表述，日本汽车金融实务以所有权保留为主，非抵押权抹消。

## 结论

1. **整条废弃** floorplan 担保/放车放证链路在 C 端的对应物（`R03 §3.4` 🛑）：
   Q21 门禁、`collateral-reg-service` 语义、`release-service` 的放车放证明细与 custodian 模型、VIN 归户的担保台账。
   复用其**工程范式**（状态机、证据链思想、回执权威性分级），**不复用其领域语义**。
2. 新建 **BC-8「所有权保留/解除」**，两组状态机**两态分离**（`R04 §3.5`）：
   - `TitleRetentionStatus`：`PENDING_REGISTRATION → REGISTERED / CONTRACTUAL_ONLY → RELEASE_REGISTERED → CLOSED`；
   - `ReleaseOrderStatus`：`CREATED → DOCUMENTS_PREPARED → DOCUMENTS_ISSUED → EXTERNAL_SUBMITTED → REGISTRATION_CONFIRMED → CLOSED`。
3. **四条硬规则**（`R04 §4`）：
   - `DOCUMENTS_ISSUED` **不得**改写 `TitleRetentionStatus`；
   - `TitleRetention → RELEASE_REGISTERED` 唯一入口 = `ReleaseOrder=REGISTRATION_CONFIRMED`；
   - 回执权威分级 `EXTERNAL_REGISTRY` > `INTERNAL_WITNESS`（仅落 `MANUAL_RECORDED`）> `PARTNER_SELF_REPORT`（**拒采**）；
   - 未取得外部回执前，系统只能说「解除文件已出具」，**不得说「解押完成」**。
4. INV-3：`ReleaseOrder.CREATED` 要求 `Loan ∈ {SETTLED, EARLY_SETTLED}`；证据链八条闭合才可改写保留状态。
5. 登记范围（`REGISTERED` vs `CONTRACTUAL_ONLY`）由 `[待定源 C-04]` 定，**两种分支都建模**；
   确认权威方与回执效力由 `[待定源 C-11]` 定。

## 后果

**变容易**：账实一致（债权结清 ⇔ 车辆检验合格证所有者变更）有机制保证；
「发文书」与「登记完成」在接口/文案/审计上可区分；跨机构时延被显式建模而非假装同步。

**变难**：解除链路依赖外部机构回执，时延与丢失不可控，需要超时工单与人工见证；
两态分离让状态机比「一步置为已解押」复杂；证据链八条要逐条落库。

**明确不做**：不把 `PARTNER_SELF_REPORT`（经销店/消费者自证）当作完成依据；
不用 `MANUAL` 代录代替外部回执；不复用「放车/放证/合格证/钥匙」词汇描述 C 端业务（`R00 §2` 禁用词）。

## 被拒选项与理由

| 选项 | 理由 |
|---|---|
| 直接搬 `release-service` + 放车放证状态机 | 车已交付消费者，无车/证/钥匙可放；custodian 模型无对应物 |
| 复用 Q21「无登记硬阻断放款」 | C 端门禁是「同意 + 审查 + 合同 + 书面文件」，不是法务局让渡担保登记 |
| 把「所有权解除文件出具」当作解押完成 | 实务歧义 + 账实不符风险（`R01 §3`） |
| 按用户字面做「抵押权設定/抹消」 | 与日本汽车金融实务不符，登记载体与流程都不同 |

## 权衡

放弃「复用现成解押模块」的省事，换取**业务语义正确**与**可证明的完成态**。
这里复用思想（两态分离、回执权威）比复用代码更有价值。
