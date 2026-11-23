# ADR-RF-009：独立数据库与迁移号段

## 状态

**Accepted**（2026-11-22） · 影响：`R00 §6 §7`、`R06 §3 §6`、`R02 §3`、`PROGRESS §2`

## 背景

- 两项目同公司、共享中台，但**业务主体不同**（B 端法人 vs C 端自然人）、**数据敏感级别不同**
  （本项目有自然人 PII，floorplan 明确 Q12 不采集）。
- floorplan 当前是「服务已拆、数据未拆」的共享库 `floorplan:5434`（`public` schema，Flyway V1–V26 已执行、
  V27–V31 预分配、V40–V79 被 USED 域整段占用）。其 `AGENTS.md` 记录过**两个并行流各建 V24 导致 Flyway 拒启动**的事故。
- 若两项目共库：① 「每类数据一个权威写者」失效；② `pii` schema 无法独立授权给 `retail_pii_rw`；
  ③ 一个项目的迁移/锁表会影响另一个；④ 号段必须跨项目协商， conflict 风险高。
- 本机端口事实：5432 被 WSL 本机 PG 占用、5434 被 `floorplan-db2` 占用。

## 结论

1. **独立 PostgreSQL 实例**：容器 `retail-finance-db`，宿主端口 **5435**，库 `retail_finance`。
   与 floorplan 的 `floorplan:5434` **物理隔离**，不共享实例、不共享 schema、不跨库 join。
2. **4 schema 分区**：`core`（业务与账务）/ `pii`（个人敏感）/ `integration`（DMZ 集成）/ `audit`（审计）。
   各 schema 独立 DB role（`R06 §3`），`audit` 仅授 INSERT。
   **生效前提**：角色授权是**生产侧物理兜底**；本地统一以 `postgres` 超级用户连库故本地不生效，
   兑现需按角色分配连接账号（`R06 §3.1`）。
3. **Flyway 从 `V1` 起，独立号段**（因独立实例，与 floorplan V1–V26 不会撞号）：
   V1–V9 = S1 地基、V10–V19 = S2、V20–V29 = S3、V30+ 按波次登记、**V32–V39 有意留空**。
   脚本唯一目录 `infrastructure/src/main/resources/db/migration/`；只有 `retail-core` 跑 Flyway。
4. **共享资源仍需跨项目登记**：端口段（8090–8099 / 18081 / 18091 / 5435）、配置前缀 `retail.*`、
   错误码 `RF_*`、JWT 环境变量、中台 jar 版本 —— 全部登记 `PROGRESS §2`，改动前先登记再改 `R00`/`README`。
5. **已执行迁移字节不改**（Flyway checksum），调整一律走新迁移；开分支前查号段占用表。

## 后果

**变容易**：PII 独立授权有物理基础；两项目迁移/锁/备份互不影响；号段无需跨项目协商；
floorplan 基线不受本项目任何 DDL 影响；合规审计能明确「数据在哪、谁能访问」。

**变难**：两项目主数据同步需要额外接口/只读视图（销售店，见 `ADR-RF-010`）；
本机要多跑一个 PG 容器；跨项目报表需要各自导出后汇总（不做跨库 join）。

**明确不做**：不共享数据库实例、不共享 schema、不复用 `FLOORPLAN_JWT_SECRET`、
不在 `retail_finance` 库里建 floorplan 的表、不改 floorplan 任何已执行迁移。

## 被拒选项与理由

| 选项 | 理由 |
|---|---|
| 共用 `floorplan` 库，加 `retail_*` 前缀的表 | 「每类数据一个权威写者」失效；PII 无法独立授权；一个项目锁表拖垮另一个 |
| 共实例但分 schema | 仍是同一故障域与同一备份域；号段仍需跨项目协商；PII role 隔离不彻底 |
| 继续用 5434 / 复用 `floorplan-db2` 容器 | 端口与容器已占用；混合部署会让本地验证互相污染 |
| 本项目 Flyway 从 V80 起「避开」floorplan 号段 | 独立实例本就不需要；人为抬高版本号只会制造第二套编号体系 |

## 权衡

放弃「同库方便 join」，换取**故障域隔离、PII 授权清晰、号段自治**。
共享中台的正确粒度是**机制与契约**（`ADR-RF-003`），不是数据库。
