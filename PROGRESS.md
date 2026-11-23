# PROGRESS.md · 唯一事实源

> **规则**：本文件是「做到哪一步」的唯一权威源。`README.md` 的模块/断言数、`docs/` 的目标态描述都可能滞后，
> 一律以本文件为准。每轮收口必须更新本文件，再提交。
> **当前状态**：计划内 S0–S5 全部完成（W9 放行，tag `w9-accepted`）· 17 模块代码 + Flyway V1–V20（62 表）· 构建 504 单测 0 失败（2026-11-23 W-SIM2 校正：此前记录 706/748 系统计口径虚高，权威口径=类级/模块级 surefire 汇总）· 走网关冒烟 33/33 · E2E 104/104 · MQ 削峰与密文信封已落地（默认关闭；关闭态冒烟 11/11 + 开启态活体联调 1/1）· 外部提议协议 v1 已定稿 · W-SIM2 外部模拟器六向对接全通 · W-CACHE 产品档位配置化 + 两级缓存 + 网关限流已落地 · **W-LOCK 分布式锁（防重复进件 / 回调幂等，fail-closed）已按用户指令合入 master** · 文档自检 PASS。详见 §0。

---

## 0. 状态横幅（每轮收口更新）

| 项 | 值 |
|---|---|
| 轮次 | **W-LOCK 分布式锁（2026-11-23，用户指示「在新的 worktree 里处理 Redisson 分布式锁防重复进件 / 回调幂等」并裁定 fail-closed；2026-11-24 用户「提交吧」→ 按用户指令合入 master）**：新增 `ADR-RF-015`（编号 013 归回调幂等 ADR、014 归 MQ ADR）；共享 `common/lock` 横切机制层（端口 `DistributedLockManager` + Redisson 适配器 + 锁键命名 + 装配），父 pom 统一 `org.redisson:redisson:3.27.2`；**进件**（`application-service` 受理按「客户 + 车辆」加锁、锁内重查去重窗口，`uk_app_idempotency` 改 `ON CONFLICT` 兜底）与**回调**（`external-api` 入站 + `title-release-service` 回执登记按「订单 + 回执号」加锁，**V19** `uk_receipt_order_authority_ref` 唯一索引兜底）两条链接锁；锁不可用 → `RF_LOCK_002`（503）**fail-closed 拒绝写**，不降级放行；锁在**事务提交后**释放、锁键不落明文业务标识；新增编排容器 `retail-finance-lock-redis`（6381）。此前：**W-MERGE 并行路线合并**（回调幂等 V19 / 状态机引擎模块 / 网关可信代理 IP / `nginx/`）+ W-MB 持久层改造（MyBatis 全量化）+ W-SIM2 终态 + W-FE 前端控制台 + W-SIM/W-SIM2 外部模拟器双向闭环。**实施于独立 worktree `retail-finance-lock`（分支 `wt/lock`）；已把 master 并入本分支并逐项解并共享文件冲突，迁移号与 ADR 号顺延避让（V19 / ADR-RF-015）；worktree 磁盘行尾已按仓库约定归一为 LF（§7 第 14 条）** |
| 轮次 | **W-MQ MQ 削峰与密文信封（2026-11-23，用户裁决「改口径合并 wt/mq」）**：**推翻 `AGENTS.md` B-10 原「不引入 MQ」红线**——引入 RocketMQ 作 outbox 之上的**削峰缓冲层**（`ADR-RF-014`；**不参与事务、不做真相源**、**MQ 上只允许密文信封**、**默认关闭** `retail.mq.enabled=false`）；新增迁移 **V20**（`integration.mq_envelope_inbox` + `core.outbox_events.relayed_at`），G-DB-7 审计授权顺延 **V21**。此前：**W-MERGE 并行路线合并（2026-11-23，用户裁决「要，按 §9 裁决合并（MQ 不合）」）**：①**`wt/lock` 只取回调幂等**——V19 建表达式唯一索引 `uk_receipt_order_authority_ref`，写入走 `ON CONFLICT DO NOTHING`，**不合入** Redisson 与 `common/lock`（正确性由唯一索引兜底，锁不作正确性来源，`ADR-RF-013`）；②**`wt/statemachine` 合入**——新增共享模块 `statemachine`（Spring StateMachine 4.0.0，进程内不引中间件），债权与解除链路 3 组状态机接线，**领域状态图仍是唯一事实源**（`R04 §5.3`、`AGENTS.md` B-14）；③**`wt/ratelimit` 只取增量**——`nginx/` 可选部署形态 + 可信代理 IP 解析（`trusted-proxy-cidrs` 默认空 = 不采信转发头）+ 按 master 实现改写的过滤器级测试，**master 的 Redis 令牌桶实现整体保留**；④**`wt/mq` 不合**（违 `AGENTS.md` B-10，**本轮已改口径合入**，见本轮 W-MQ 与 `ADR-RF-014`）。此前：**W-CACHE / W-RL 产品档位配置化 + 多级缓存 + 网关限流（2026-11-23，用户裁决「配置化完整落地，同时上 Caffeine L1 + Redis L2」+「网关限流补 Redis 令牌桶」）**：①**产品档位配置化完整落地**——V18 新增 `core.financial_products`（同码多版本 + 生效区间 + 乐观锁）与 `core.repayment_plans` **快照三件套**（`product_code`/`product_version`/`rate_bp`）；参数合成优先级**授信覆盖 > 档位默认**；改档位 = 旧版封口停用 + 新版插入，**改配置不回改历史计划**；②**两级缓存**——共享 `common` 机制件 `TwoLevelCache`（Caffeine L1 + Redis L2 + 空值哨兵 + Redis 不可用降级直读 + 事务提交后失效）；③**网关 Redis 令牌桶限流**（Lua 原子扣减、键取 Authorization 头哈希、fail-open、规则可配）；④新增 **ADR-RF-012** 固化 Redis 用途白名单（`AGENTS.md` B-13：只缓存只读配置与限流态）。此前：**W-MB 持久层改造（2026-11-23，用户指示「jdbc 都换成 MyBatis」）**：12 个部署单元的持久层由 `JdbcTemplate` + `RowMapper` **全量迁到 MyBatis 3.0.3 注解 SQL**（无 XML、无 JPA），红线「**SQL 语义 / 仓储方法签名 / 事务边界 / 对外接口一律不变**」（`AGENTS.md` B-12）；新增共享映射件 `common/.../persistence/RowValues.java`；各服务 `@MapperScan` 显式列单一 mapper 包（精确装配，未做全包扫描）；一级缓存显式降为 STATEMENT 级对齐原读语义；唯一键幂等统一走 `ON CONFLICT` 原子语义；**既有迁移字节未动**（本轮新增 V18 另计）、**未引 MQ/Seata/对象存储**。此前：W-SIM2 终态（计划内 S0–S5 全部完成，W9 放行，tag `w9-accepted`）+ W-FE 前端控制台 + W-SIM/W-SIM2 外部模拟器双向闭环；仓库单分支单 worktree，验证进程已全停 |
| 架构口径 | **Spring Cloud 微服务，直接对齐 floorplan**（用户 2026-11-22 指示，见 `ADR-RF-001`）；12 个部署单元 = 10 业务服务 + 网关 + DMZ |
| 数据库隔离口径 | **本地 11 个连库单元（10 业务服务 + DMZ；网关不连库）统一以 `postgres` 超级用户连库** → DB role 隔离**本地不生效**，是**生产侧物理兜底**；兑现前提 = **按角色分配连接账号**（`R06 §3.1`） |
| 文档语言 | **全文中文**（用户 2026-11-22 指示）：法律/机构名用中文译名，拉丁缩写与代码标识符保留 |
| 交付 | 根 5 文件（含 `.gitattributes` 行尾规则）；`docs/00` + `R00–R11` 共 12 份；`docs/adr/ADR-RF-001~014` 共 14 份；`scripts/`（check_docs / normalize_terms / gen_w2_services / smoke_w2 / smoke_mq）；`run-microservices.sh`；`.github/workflows/docs-check.yml`；**`frontend/`（W-FE）**；**`external-simulator/`（W-SIM：外部机构模拟器 simulator.js(18100, 零依赖 Node) + 模拟对接 E2E demo_e2e.py）** |
| 代码 | **W1**：`../company-platform-contracts` 19 主类 + 34 单测全绿；**W2（本仓）**：Maven 16 模块——四层共享（domain/application/common/infrastructure）+ 10 业务服务 + 网关 + DMZ（父 pom 为聚合器，不计入 16）；单测 16（JWT 双主体 8 + 事件白名单 8）+ DMZ 验签单测 4；Flyway V1–V9 已实测执行（49 张表）；`mvn -B clean package` 16 模块 BUILD SUCCESS |
| 验证 | `mvn -B clean package` → **BUILD SUCCESS（单测 0 失败；总数以 surefire 汇总为准，见顶部校正注）**；`run-microservices.sh smoke` → **33/33**；`scripts/smoke_mq.py` → **11/11**（MQ 关闭态零回归）；`MqEnvelopeLiveTest`（`-Drf.mq.live=true`，真实 RocketMQ broker）→ **1/1**；`scripts/e2e_w8.py` 全链路 → **104/104 EXIT 0**（可重复）；`external-simulator/demo_e2e.py` → **34/34**；`frontend/smoke_frontend.py` → **46/46**；`frontend/browser_e2e.py`（Playwright/Edge）→ **30/30**；`check_docs.py` **PASS 15/15**；floorplan 基线未触碰 |
| 业务阻塞 | ✅ 系统口径已全部定源（`C-01` 用户签；`C-02`~`C-12` AI 裁决，`R01 §6`）；外部核实项不阻塞 |
| 外部依赖 | 中台 jar ✅；`R07 §7`：DMS ✅ 回填（`ADR-RF-011`）；集成商选型与其余通道未回填（**不阻塞 S2 并行窗口**） |
| 下一轮 | **计划内 S0–S5 全部完成，W9 已放行（tag `w9-accepted`）**。后续进入外部对接阶段：集成商选型与征信/银行/身份核验/登记代办/通知通道调研回填（`R07 §7`）；**各通道字段级「我方提议协议 v1」已定稿（`R07 §4.1–§4.16`，用户授权）**，随调研发出作为谈判基线，回填前仍不实现自动对接（非阻塞） |

### 本轮两项方向修正（已落实）

1. **微服务**：初版按「模块化单体起步、按触发器演进」设计，用户指出 floorplan 已是微服务，
   已整体改为直接对齐其微服务形态（重写 `ADR-RF-001`、`R02`、`README`、`AGENTS`、本文、`00`、`R11`）。
2. **全中文**：初版混用日本业务术语，用户指出现阶段用中文写文档，
   已由 `scripts/normalize_terms.py` 全仓规范化，`check_docs.py` 加中文/架构口径护栏防回归。

---

## 1. 本轮已完成清单

| # | 事项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 摸清中台基线 | 实查 `../floorplan-system` 全库（约 280 Java / 26 迁移 / 90 文档），确认其**已是微服务架构** | `R03 §2`、`README §1` |
| 2 | 识别 B2B→C 端架构冲突 | 定位 9 类冲突，其中 3 处为「隐性合规事故点」（Q12 个人信息 / Q15 征信 / 法人假设） | `R03 §4` 切断点 |
| 3 | 核实法务术语并中文化 | 确认个品分期、所有权保留解除、CIC 双法指定、支付能力预估额调查义务、总量规制与除外贷款；全部转为中文定名 | `R00 §2`、`R01 §1 §3` |
| 4 | 领域发现与上下文映射 | 12 个限界上下文、5 类映射关系、销售店主数据交汇点、上下文→服务归属 | `R02 §5`、`ADR-RF-010` |
| 5 | 微服务架构设计 | 12 部署单元、精确装配、网关切流、零信任、Feign 门禁 + outbox、第一版共库、被拒方案 | `R02`、`ADR-RF-001` |
| 6 | ADR 决策记录 | 10 份，均含状态 / 背景 / 结论 / 后果 / 被拒选项 / 权衡 | `docs/adr/` |
| 7 | 命名定源与号段 | 端口 8090–8099 / 18081 / 18091、库 5435、前缀 `retail.*` / `RF_*`、Flyway V1 起 | `R00 §3.1 §6 §7` |
| 8 | 工程治理 | 反模式 16 条、共享禁区、12 进程启动编排、验收判据、开工检查单 | `AGENTS.md` |
| 9 | 一致性与术语自检 | **15 项**机器校验落地并实测 **PASS / FAIL 0 / EXIT 0**，替代本轮无代码可编译的「冒烟全绿」；其中 C14/C15 为 2026-11-23 新增（号段与测试数口径守护） | `scripts/check_docs.py` |
| 10 | 全中文 + 微服务口径回灌 | 全仓术语替换（假名 + 日文汉字） + 3 个 ADR 重命名 + 引用同步 + 10 份 ADR 节标题中文化；重写 `R00 §2 §3.1 §3.2` 与 `R09` 全文、修正 4 处端口段 | `scripts/normalize_terms.py` |
| 11 | C-01 定源 + DMS 对接定源 | C-01 双形态并行（`ADR-RF-002` Accepted）；DMS 初期走数据集成商、四类业务接口与安全规范回填 `R07`（`ADR-RF-011` Accepted）；`R06` 增假名姓名字段 | `R01 §6`、`R07 §3 §4 §7`、`ADR-RF-002`、`ADR-RF-011` |
| 12 | **持久层全量 MyBatis 化**（用户指示「jdbc 都换成 MyBatis」） | 12 个部署单元的持久层由 `JdbcTemplate` + `RowMapper` 全量迁到 **MyBatis 注解 SQL**（无 XML、无 JPA），SQL 语义 / 方法签名 / 事务边界 / 对外接口**一律不变**；新增共享 `RowValues` 行映射件；各服务 `@MapperScan` 精确装配；一级缓存 STATEMENT 级对齐原读语义；唯一键幂等走 `ON CONFLICT` 原子语义 | `AGENTS.md` §2 + B-12、`PROGRESS §2` 共享资源登记、`§6` 验证记录 |
| 14 | **并行路线合并收口**（W-MERGE，用户裁决「等各会话跑完提交后逐条评估合并」+「冲突处以 master 口径为准，只并入不冲突部分」） | 按 `PROGRESS §9` 裁决落地：①**`wt/lock` → 只吸收回调幂等**——迁移 **V19** 表达式唯一索引 `uk_receipt_order_authority_ref` + `ADR-RF-013`「回调幂等：以数据库唯一索引为权威，**不引入分布式锁**」，**Redisson 与 `common/lock` 不合入**；②**`wt/statemachine` → 合入**——新增独立 Maven 模块 `statemachine`（Spring StateMachine 4.0.0 引擎底座；**领域状态图仍是唯一事实源**，`domain` 零 Spring 注解不变），接线只放 retail-core / title-release 的 `*.application.statemachine` 包；③**`wt/ratelimit` → 只吸收三项增量**——`nginx/`（边缘层可选部署形态）、`ClientIpResolver`（可信代理 IP 提取口径）、`RateLimitFilterTest`（过滤器级测试），**master 的 Redis 令牌桶实现整体保留**（限流键仍用 Authorization 头哈希，不引入 IP 键）；④**`wt/mq` → 不合**（引 MQ 违反 `AGENTS` B-10，其 `V20__mq_envelope_channel.sql` 整条弃用） | `PROGRESS §9`、`ADR-RF-013`、`AGENTS.md` §2 与 B-13、§6 验证记录 |
| 13 | **产品档位配置化 + 多级缓存 + 网关限流**（用户裁决「配置化完整落地 + Caffeine L1 + Redis L2」+「网关限流补 Redis 令牌桶」） | ①**V18** 新增 `core.financial_products`（同码多版本 + 生效区间 + 乐观锁 + `uk_fin_product_ver`，种子 2 档位 `AUTO_LOAN_36_0` / `INSTALMENT_36_200`）与 `core.repayment_plans` **快照三件套**；参数合成优先级**授信覆盖 > 档位默认**（纯领域 `ProductTermsResolver`，零 Spring 依赖）；改档位 = 旧版封口停用 + 新版插入（同一本地事务 + **afterCommit** 缓存失效），乐观锁冲突 `RF_CONFLICT_001`；**改配置不回改历史计划**；②共享 `common` 两级缓存机制件 `TwoLevelCache`（Caffeine L1 + Redis L2 + 空值哨兵 + 降级直读 + 按前缀失效）；③网关 **Redis 令牌桶限流**（Lua 原子扣减 + 429 + `Retry-After` + `RF_GATEWAY_001`，Redis 不可用 fail-open）；④新增 **ADR-RF-012** 固化 Redis 用途白名单 | `ADR-RF-012`、`R00 §3.3 §6 §7`、`R02 §5`、`R06 §4.3 §6`、`AGENTS.md` B-13、`PROGRESS §2` 共享资源登记、`§6` 验证记录 |
| 15 | **Windows 行尾纪律固化**（用户指示「把解决的办法写到 agents.md 里」） | ①`AGENTS.md` 新增 **§4.1**：CRLF 现象 / WSL 活体复现的报错原文 / 根因（转换发生在检出期，**blob 本身是 LF**）/ 三层修复（`.gitattributes` → 本仓 `core.autocrlf=input` → 工作区重新检出）/ 判定命令 / 写入纪律；②`.gitattributes` 扩到 `*.py text eol=lf`（shebang 直接执行同样会被 CRLF 打死）；③新增反模式 **A-21**、§5 提交前行尾门禁、§11 检查单 | `AGENTS.md` §4.1 §5 §9 §11、`.gitattributes`、`PROGRESS §2` 变更记录、`§6` 验证记录 |
| 17 | **W-RL 网关限流双模式合入**（用户指令「把当前分支合并到 master」+「两套限流实现都合并，用一个配置作为开关，来采用哪种限流方式」） | ①**合并 `wt/ratelimit`**（`git merge --no-ff`，16 个冲突全部按「master 为底 + 只挑增量」手工解）；②**限流实现双轨 + 一个开关**：`retail.gateway.rate-limit.mode` = `redis`（**缺省**，Redis 令牌桶 `RateLimitFilter`）／`memory`（进程内令牌桶 `InMemoryRateLimitFilter`，零依赖、单实例口径），两套**互斥装配**（互补 `@ConditionalOnProperty`，同一时刻只注册一个限流过滤器 → **不双重限流**），新增 `RateLimitModeSwitchTest` 在**装配期**钉死互斥；③分支的 12 个独有文件（策略注册表 / 进程内令牌桶 / 2 个测试 / 3 个边缘验收脚本）并入，**类名去冲突**（`InMemory*` 前缀，参数前缀 `retail.gateway.rate-limit.memory.*`）；④`ADR-RF-012` 增补第 12/13 条（双模式 + 边缘 Nginx 为可选部署形态）；⑤`R02` 新增 **§3.3 限流设计（两层 + 双模式 + 一个开关）**、`R10` 新增 **K 组 A43–A49**、`R00 §3.8` 登记新配置键与边缘端口、`R07` 订正 partner 限流归属、`AGENTS.md` B-13 补互斥口径 | `ADR-RF-012` §12 §13、`R02 §3.3`、`R10` K 组、`R00 §3.8`、`AGENTS.md` B-13、`PROGRESS §2` 共享资源登记、`§6` 验证记录 |
| 16 | **W-MQ MQ 削峰与密文信封合入**（用户裁决「改口径合并 wt/mq」，推翻 `AGENTS.md` B-10 原「不引入 MQ」红线） | ①**红线改写**：B-10 改为「不引 Seata / 分布式事务 / 对象存储；**MQ 已引入**（`ADR-RF-014`）作 outbox 之上的**削峰缓冲层**」（**不参与事务、不做真相源**），新增 **B-15**（MQ 上只允许密文信封）、反模式 **A-18~A-20**；②**ADR 顺延**：MQ 的 ADR 由 013 顺延为 **ADR-RF-014**（013 归回调幂等，既有含义不动）；③**迁移**：**V20 = MQ 密文信封通道**（`integration.mq_envelope_inbox` + `core.outbox_events.relayed_at` + 索引，新增 1 张表），**G-DB-7 审计写入授权顺延 V21**（V1–V19 字节全部未动）；④**代码**（`git merge --squash wt/mq`，64 文件自动合并 + 16 冲突手工解，master 为底只挑增量）：`common/messaging` 9 机制件（AES-GCM 密文信封 + 8 键载荷白名单 + `NoopEnvelopePublisher`）、`infrastructure` 8 机制件（`MqOutboxRelay` / `RocketMqEnvelopePublisher` / `MqEnvelopeDispatcher` / `MqEventRelayConsumer` / `MqEnvelopeInboxMapper` / `EventConsumptionService`）、5 个服务的 MQ 接线与消费者、`scripts/smoke_mq.py`（11 断言）；⑤**默认关闭**（`retail.mq.enabled=false` → 走同步路径，行为与现网逐字一致，冒烟零回归） | `ADR-RF-014`、`AGENTS.md` §3 与 B-10/B-15、§9 A-18~A-20、`R00 §7`、`R02`、`R06 §6`、`PROGRESS §2` 共享资源与号段登记、`§6` 验证记录 |

---

## 2. 共享资源登记（跨项目，改动前必须登记）

| 资源 | 本项目占用 | floorplan 占用 | 状态 |
|---|---|---|---|
| 业务服务端口 | **8090–8099**（10 个） | 8080–8088 | 不冲突 ✅ |
| 前端控制台端口 | **18082**（frontend/serve.py，本地验证用，非部署单元） | — | 不冲突 ✅（用户 2026-11-23 指示「页面也要测试通」） |
| 分布式锁 Redis 宿主端口 | **6381**（容器 `retail-finance-lock-redis`；`ADR-RF-015`） | — | 不冲突 ✅（与并行会话的缓存 Redis `retail-finance-redis`:6382 分端口分容器名） |
| 外部机构模拟器端口 | **18100**（external-simulator/simulator.js，W-SIM；有 node 时编排自动拉起并将 CIC 出站切至模拟器） | — | 不冲突 ✅（用户 2026-11-23 指示「模拟一个外部服务回应你」） |
| 内部网关 | 18081 | 18080 | 不冲突 ✅ |
| 外部 DMZ | 18091 | 18090 | 不冲突 ✅ |
| 边缘入口（可选 Nginx 形态） | **18076**（前置内部网关）/ **18077**（前置外部 DMZ）；配置 `nginx/nginx.conf` | — | 不冲突 ✅（floorplan 无边缘层；**默认不启用**，W-RL 引入） |
| 数据库宿主端口 | 5435 | 5434 | 不冲突 ✅ |
| Redis（缓存 / 限流） | **6382**（容器 `retail-finance-redis`）：L2 只读配置缓存 + 网关限流状态 | — | 不冲突 ✅（6379 被本机 WSL 侧进程占用，未复用；用途白名单见 `ADR-RF-012` §2 / `AGENTS.md` B-13） |
| RocketMQ（削峰缓冲层） | **9876**（namesrv）/ **10911**（broker，本地单节点；`ADR-RF-014`） | — | 不冲突 ✅（本轮引入；**默认不启用**，`retail.mq.enabled=false` → 走同步路径） |
| MQ 配置键 / 环境变量 | `retail.mq.*`（`enabled` / `data-key` / `key-version` / `name-server` / `producer-group` / `consumer-group` / `consume-thread-max` / `pull-batch-size` / `relay-consumer-enabled` / `direct-consumers`）；环境变量 `RETAIL_MQ_ENABLED` / `RETAIL_MQ_DATA_KEY` / `RETAIL_MQ_KEY_VERSION` / `RETAIL_MQ_NAME_SERVER` / `RETAIL_MQ_CONSUME_THREADS` / `RETAIL_MQ_PULL_BATCH` / `RETAIL_MQ_RELAY_ENABLED` / `RETAIL_MQ_DIRECT_CONSUMERS` | — | 前缀 `retail.*` 与 `RETAIL_*` 一致，不冲突 ✅ |
| 数据库实例 | `retail_finance`（独立） | `floorplan`（独立） | 物理隔离 ✅ |
| Flyway | V1 起（独立库）；**V16–V17 已占用**（PII 授权收紧）；**V18 已占用**（产品档位目录 + 还款计划快照列，W-CACHE）；**V19 已占用**（外部登记回执幂等唯一索引，ADR-RF-013）；**V20 已占用**（MQ 密文信封通道，ADR-RF-014）；**V21 起预留**（G-DB-7 审计授权调整顺延至 V21） | V1–V26 已执行、V27–V31 预分配、V40–V79 已占用 | 不撞号 ✅ |
| 配置前缀 | `retail.*` | `floorplan.*` | 不冲突 ✅ |
| 网关限流配置键 | `retail.gateway.rate-limit.mode`（`redis` 缺省 / `memory`）+ `retail.gateway.rate-limit.*`（redis 模式）+ `retail.gateway.rate-limit.memory.*`（进程内模式）；环境变量 `RETAIL_GATEWAY_RATE_LIMIT_MODE` / `RETAIL_GATEWAY_RATE_LIMIT_MEMORY_*` | — | 前缀 `retail.*` 与 `RETAIL_*` 一致，不冲突 ✅（W-RL 引入；**两套互斥装配**，见 `ADR-RF-012` §12） |
| 错误码前缀 | `RF_*` | `ADVANCE_*` / `CREDIT_*` 等 | 不冲突 ✅ |
| JWT 环境变量 | `RETAIL_JWT_SECRET` / `RETAIL_CUSTOMER_JWT_SECRET` | `FLOORPLAN_JWT_SECRET` | 三把密钥互不相同 ✅ |
| 中台 jar | `com.company:company-platform-contracts:1.0.0-SNAPSHOT`（**✅ 已抽取**，独立仓 `../company-platform-contracts`，`mvn install` 到本地仓；34 单测全绿；RF 依赖 ≥ 此版本） | floorplan 不强制迁移（继续用 `com.company.floorplan.*` 现网类，基线全绿未动） | ✅ W1 完成 |
| Feign 契约 | `com.company.retail.common.contract`（**✅ W2 已冻结**：`FeignContracts` 代付前 3 门禁契约（contract/consent/underwriting，fail-closed）+ `OutboxPublisher` 17 事件常量 + `EventPayloadGuard` 白名单；`w2-frozen` 后并行包只读） | `com.company.floorplan.common.contract` | 各自常量表 ✅ |
| 持久层框架 | `org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.3`（**W-MB 本轮引入**：父 pom `dependencyManagement` 统一版本；业务服务 + `infrastructure` + `external-api` 各自显式声明） | floorplan 仍用 JdbcTemplate，未引入 MyBatis | 不冲突 ✅（本项目独立选型，互不影响） |

> 变更记录：
> - 2026-11-24：**据实订正 W-MB 轮遗留的一处真实缺陷 + `check_docs.py` 护栏补强**（随 W-MQ 收口一并回灌 master）——
>   ①**游标表名缺陷**：W-MB 持久层改造轮（`a8354a7`）把 `OutboxMapper` 三处**游标表名写成不存在的
>   `core.event_offsets`**（V1 建的表实为 `core.consumer_offsets`）→ `insertOffsetIfAbsent` 抛
>   `BadSqlGrammarException` → `RfEventDispatcher.poll()` 逐 consumer catch → **事件消费整条静默停摆**，
>   全部事件滞留 `PENDING`、征信完成事件不再推进审查状态机（前端页面冒烟级联失败 8 项）。
>   **该故障只在日志里留一行 WARN**，极易被误判为「环境数据脏 / outbox 积压」——本轮即先误判一轮。
>   修法：三处 SQL 对齐 master 写法（表名 `core.consumer_offsets` + `writeOffset` 补 `updated_at = now()`），
>   与 **W-MB 改造前的 JdbcTemplate 版逐字一致**（B-12：持久层改造不改语义），与 master 侧逐字相同
>   （合并零冲突）。验证记录见 §6「游标表名缺陷订正」行；并新增 §7 第 16 条警示（静默停摆的判别与排查顺序）。
>   ②**`scripts/check_docs.py` 护栏补强**（**共享禁区，已获用户授权**）：**C2** 由「硬编码 15 份 / 001–015」
>   改为「缺号只接受 §2 登记为预留的号段」；**C4** 跨文档引用的 ADR 登记表由硬编码 `range(1,16)` 改为
>   「磁盘实有 ∪ 已登记预留」；**C13** 由「根 5 + docs 13 + ADR 15 + 脚本 3 = 36」改为内部一致性校验
>   （根文件含 `.gitattributes`、核心脚本 2 份固定、并行流的冒烟脚本单独计数、ADR 取「索引声明 ≥ 磁盘实有」）。
>   **动机**：master 上仍有 `wt/statemachine` / `wt/ratelimit` 两条并行流在跑，它们各自落 ADR 与冒烟脚本——
>   硬编码期望值会在它们合入时**假失败**（这类护栏只认固定数字，就会在并行工作流下失去意义）。
>   **未改任何业务代码与已执行迁移字节**；`PROGRESS.md` / `scripts/check_docs.py` 属共享禁区，本轮经用户授权修改。
> - 2026-11-24：**用户指示**「把当前分支合并到 master」+「两套限流实现都合并，用一个配置作为开关，来采用哪种限流方式」
>   —— 实施 **W-RL 网关限流双模式合入**（合并 `wt/ratelimit`）。
>   改动面：`gateway/.../ratelimit/`（新增 `InMemoryRateLimitFilter` / `InMemoryRateLimitProperties` / `InMemoryRateLimitConfig` /
>   `RateLimitPolicyResolver` / `RateLimitRegistry` / `TokenBucket` 等；`RateLimitFilter` 去 `@Component` 改由条件装配的 `RateLimitConfig` 建 bean）、
>   `gateway/src/main/resources/application.yml`（新增 `retail.gateway.rate-limit.mode`，**共享禁区，已获授权**）、
>   `gateway/pom.xml`（描述补双模式）、`scripts/smoke_ratelimit.py` / `scripts/smoke_ratelimit_edge.py` /
>   `scripts/smoke_ratelimit_application.yml`（**`scripts/*` 共享禁区，已获授权**）、
>   `AGENTS.md`（B-13 补「限流双实现互斥」+ §4 端口行补边缘 Nginx，**共享禁区，已获授权**）、
>   `PROGRESS.md`（本文件，**共享禁区，已获授权**）、`docs/adr/ADR-RF-012`（**修订**：增第 12/13 条）、
>   `R00 §3.8 §6`、`R02 §3.3 §9 §12`、`R07`、`R10`（K 组 A43–A49）、`README`、`docs/00-文档索引.md`、`interview/` 三份求职材料。
>   **边界**：**不新增 Flyway 迁移**（V1–V20 字节未动）、**不改 `scripts/check_docs.py`**（C2/C13 期望值未变——
>   本轮修订既有 `ADR-RF-012` 而非新建 ADR，ADR 仍是 15 份）、**未改父 pom**（网关 Redis 依赖本轮前已在）。
>   **代价与风险**：两套实现都要维护；切模式会**静默降级**成单实例口径（`memory`），**切模式必须显式登记**。
> - 2026-11-23：**用户授权**（「把解决的办法写到 agents.md 里」）实施 **Windows 行尾纪律固化**——修掉 `core.autocrlf=true` 把 `.sh` 检出成 CRLF、导致 WSL 侧 bash 解析失败的问题。
>   改动面：`AGENTS.md`（**新增 §4.1 + 反模式 A-21 + §5 行尾门禁 + §11 检查单 + §12 自检**，**共享禁区，已获授权**）、
>   `.gitattributes`（扩到 `*.py text eol=lf`，并锁定 `.gitattributes` 自身）、
>   `PROGRESS.md`（§1 第 15 行 / §6 验证记录，本文件，**共享禁区，已获授权**）、
>   本仓 Git 配置 `core.autocrlf` 由 `true` 改为 `input`（**仅本仓 local**，**不动全局配置**）。
>   **边界**：**只对会被解释器直接执行的脚本类型设 `eol=lf`**（`.sh` / `.py`）；`.md` / `.java` / `.yml` 的 CRLF 无害，**不批量转换**；
>   **仓库 blob 一律 LF，未改任何已执行迁移字节、未改任何业务代码、未碰 `scripts/` 禁区文件**。
>   **纪律**：`.gitattributes` 是**规则本体**（随代码走、优先级高于 `core.autocrlf`），**必须随收口提交**，否则新克隆的机器会复发。
> - 2026-11-23：**用户授权改口径**实施 **W-MQ**（RocketMQ 削峰 + 密文信封投递）——**推翻 `AGENTS.md` B-10 原「不引入 MQ」红线**。
>   改动面：父 `pom.xml`（`rocketmq-client:5.0.0` 依赖管理，**共享禁区，已获授权**）、`infrastructure/pom.xml`、
>   新增共享底座 `common/.../common/messaging/`（9 个机制件：`CipherEnvelope` / `EnvelopeCipher` / `EnvelopePayloadGuard` /
>   `EnvelopePublisher` / `EnvelopeConsumer` / `EnvelopeTopics` 等）、
>   新增 `infrastructure/.../infrastructure/{messaging,config,mapper}`（RocketMQ 生产端 / 消费端 / 中继 / 幂等表 / 装配）、
>   6 个服务的 `application.yml`（新增 `retail.mq.*`，**默认全关**）与启动类 `@Import`、
>   新增迁移 **V20**（`integration.mq_envelope_inbox` + `core.outbox_events.relayed_at`）、
>   `AGENTS.md`（**B-10 改写 + 新增 B-15 + 反模式 A-18~A-20**，**共享禁区，已获授权**）、
>   `ADR-RF-008`（第 3 条 MQ 部分标注被取代）、**新增 `ADR-RF-014`**（原 `wt/mq` 分支的 ADR-013 顺延）、
>   `R00 §6 §7`、`R02 §3.2 §7.2 §9 §12`、`R06 §3.2 §6`、`R09 §5.5`、`R10`、`README`、
>   `docs/00-文档索引.md`、`scripts/check_docs.py`（C2 / C4 / C13 期望值 13→14、32→33，**仅更新期望值，未放松校验逻辑**）、
>   `scripts/smoke_mq.py`（新增 11 断言）+ `run-microservices.sh`（新增 MQ 冒烟块，**共享禁区，已获授权**）。
>   **边界**：MQ 只作 outbox 之上的**削峰缓冲层**——不参与事务、不做真相源；**MQ 上只允许密文信封**（明文个人信息一律不入队）；
>   **默认关闭**（`retail.mq.enabled=false` → 走同步路径，行为与现网逐字一致）。
>   **号段影响**：V20 = MQ 密文信封通道 → G-DB-7 审计写入授权调整**顺延 V21**（`R06 §6` / 本表已登记）。
>   **既有迁移字节未动**（V1–V19 全部未改）；**不引 Seata / 分布式事务 / 对象存储**。
> - 2026-11-23：**用户裁决**实施 **W-CACHE / W-RL**（产品档位配置化完整落地 + Caffeine L1 / Redis L2 两级缓存 + 网关 Redis 令牌桶限流）。
>   改动面：父 `pom.xml`（新增 `spring-boot-starter-data-redis` 与 `caffeine` 依赖管理，**共享禁区，已获授权**）、
>   `common/pom.xml`（两依赖置 `optional`，精确装配）、`retail-core/pom.xml` + `retail-core/application.yml`、
>   `gateway/pom.xml` + `gateway/application.yml`（**共享禁区，已获授权**）、`run-microservices.sh`（新增 `ensure_redis`，**共享禁区，已获授权**）、
>   `scripts/smoke_w2.py`（+7 断言：限流触发 429 / 首个请求放行 / 响应体含 `RF_GATEWAY_001` / 读档位 / 二次读走缓存 / 档位种子 / 快照三列已加）、
>   `scripts/e2e_w8.py`（+6 断言：建档位 → 按档位生成计划 → 快照落库 → 改版到版本 2 → 缓存已失效 → **历史计划快照与期次利息均不变**）、
>   `scripts/check_docs.py`（C13 期望 ADR 11→12、总数 30→31，**仅更新期望值，连续性与唯一性校验逻辑未放松**）、
>   `AGENTS.md`（B-10 补注 + 新增 **B-13** + §4 环境事实，**共享禁区，已获授权**）。
>   新增共享件：`common/.../common/cache/{TwoLevelCache,RfCacheProperties,RfCacheConfig,CacheInvalidator}.java`。
>   **新增迁移 V18**（既有迁移字节未动）；Redis **仅两类用途**（只读配置 L2 + 网关限流态），
>   **不缓存**债权余额 / 核销流水 / 个人信息 / 征信与审查结果（`ADR-RF-012` §2、`AGENTS.md` B-13）。
> - 2026-11-23：**用户指示**「jdbc 都换成 MyBatis」——持久层全量由 JdbcTemplate + RowMapper 改为
>   **MyBatis 注解 SQL**（无 XML、无 JPA）。红线：**SQL 语义、仓储方法签名、事务边界、对外接口一律不变**
>   （`AGENTS.md` B-12）。改动面：父 `pom.xml`（新增 mybatis 依赖管理，**共享禁区，已获授权**）、
>   `infrastructure/pom.xml` + `external-api/pom.xml`（显式声明依赖）、11 个服务的 `application.yml`
>   （`mybatis.configuration.local-cache-scope: STATEMENT`，对齐原「每次读都落库」的读语义）、
>   `AGENTS.md`（§2 持久层纪律 + B-12 红线，**共享禁区，已获授权**）。
>   新增共享件：`common/.../common/persistence/RowValues.java`（行映射口径统一，NULL 语义对齐 `ResultSet.getXxx`）。
>   **未新增 Flyway 迁移**（V1–V17 字节未动）、未引入 MQ / Seata / Redis / 对象存储。
> - 2026-11-23：**据实订正「网关限流」的未实现声明**（收口轮自查发现）——
>   核实 `gateway/` 仅有 `RequestIdFilter` + 启动类，**无限流实现**，但 `README`（架构图 + 服务表）、
>   `R02`（架构图 + 服务表 + 可伸缩性行）、`ADR-RF-001`（服务表）均把「限流」写成既有能力，
>   `R07` 的 DMZ 第四关也把「限流」与已实现的三关并列——属 `AGENTS.md` §7 所列「假完成」类漂移。
>   处理：这 6 处一律标注**「限流未实现」/「口径待定」**，不删设计意图（投产前仍需补）。
>   **未改任何业务代码、配置与迁移**；**求职材料本就不写限流**（`interview/` 三份均无该表述）。
> - 2026-11-23：**用户授权**实施 V16 迁移（撤销 `retail_app_rw` 对 `pii.customer_pii` 的 SELECT）：
>   原因——V1 的 `ALTER DEFAULT PRIVILEGES IN SCHEMA pii GRANT SELECT ON TABLES TO retail_app_rw`
>   把 `pii.customer_pii` 也授了业务角色，与 `R06 §3` 角色表口径「不含 `pii.customer_pii`」及 `R08` PII-5 不一致。
>   处理——新增 `V16__pii_customer_pii_revoke.sql`（只调授权，不改表结构与数据，V1–V15 字节未动），
>   同步 `R06 §3 §6` 与 README / R02 / `00-文档索引` 的号段口径 V1–V15 → V1–V16；
>   **本文件属共享禁区，本次改动已获用户明示授权**。
> - 2026-11-24：**用户指令「提交吧」**→ `wt/lock` **合入 master**（Redisson 分布式锁一并合入，修订 `ADR-RF-013` 的「不引入分布式锁」结论）。
>   合并协调：①master 期间又推进 3 个提交（W-MERGE 裁决回写 / Windows 行尾纪律固化 / W-MQ 合入 V20+ADR-RF-014），本分支先把 master 并入再逐项解冲突（**15 个冲突文件**，两侧改动均保留）；
>   ②**ADR 编号最终让位到 ADR-RF-015**（012 缓存/限流、013 回调幂等、014 MQ）；
>   ③**V19 回调幂等唯一索引已由 master 先落地**（本分支同名文件内容一致，合并零冲突），迁移号段仍为 V1–V20 连续；
>   ④共享禁区文件（父 pom / `common/pom.xml` / `domain/RFErrorCode.java` / `scripts/check_docs.py` / `run-microservices.sh` / `AGENTS.md` / `docs/00-文档索引` / R00 / R02 / R06 / R10 / PROGRESS / `interview/`×3）均为**两侧增量合并**，未整文件覆盖；
>   ⑤**R10 断言组顺延**：master 的「I 组」已占 A37–A38，锁专项改用 **J 组 A39–A42**（`scripts/smoke_w_lock.py` 同步改口径）；
>   ⑥**Redisson 用途白名单冲突**已消解：master 的 `ADR-RF-012` 只锁死「Redis **缓存**用途」，本轮锁是**独立的第三类 Redis 用途**（分布式锁），
>   已在 `AGENTS.md` 锁纪律条目与 `ADR-RF-015` 中写明「锁只收敛并发窗口、不是正确性来源、不可达即 fail-closed」。
> - 2026-11-23：**用户授权**实施 W-LOCK（Redisson 分布式锁；用户裁定 fail-closed）——改动面：父 `pom.xml`（Redisson 版本管理，**共享禁区，已获授权**）、
>   `common/pom.xml`（Redisson + spring-tx，optional；**共享禁区**）、`domain/RFErrorCode.java`（`RF_LOCK_001/002`；**共享禁区**）、
>   `scripts/check_docs.py`（ADR 11→12、文件计数 30→31；**共享禁区**）、`run-microservices.sh`（Redis 容器编排；**共享禁区**）、
>   新增 `V19__callback_receipt_idempotency.sql`（回执唯一索引）。
>   **与并行会话的号段冲突（收口轮已处理）**：并行会话的 `V18__financial_products_and_plan_snapshot.sql` 已于 2026-11-23 **提交进 master 并执行**（18/18 success，字节不可改）——
>   ① 本分支迁移**顺延至 V19**（与 master V18 连号，号段 V1–V19 连续；`check_docs.py` C14 实测通过）；
>   ② ADR 编号顺延（最终 **ADR-RF-015**：012 归 Redis 缓存/限流 ADR、013 归回调幂等 ADR、014 归 MQ ADR）；
>   ③ 父 pom / `common/pom.xml` / `domain/RFErrorCode.java` / `scripts/check_docs.py` / `run-microservices.sh` 同文件改动 → 本分支已把 master 并入并**逐项解冲突**（两侧改动均保留）；
>   ④ 对方 `retail-finance-redis` 容器（6382，缓存用途），本轮锁用 `retail-finance-lock-redis`（6381），端口与容器名均不冲突。
>   **未改主工作区任何文件**（`AGENTS.md` §7：不暂存、不代提交、不覆盖），全部改动只发生在 `wt/lock` 分支的独立 worktree 内。
> - 2026-11-23：**用户授权**实施 V17 迁移（撤销 `retail_app_rw` 对 `pii.consents` / `pii.consent_withdrawals` /
>   `pii.identity_verifications` / `pii.identity_documents` 的 SELECT）：
>   原因——V16 只撤了 `pii.customer_pii`，其余 pii 敏感表仍沿用 V1 建表时的默认授权，与 `R06 §3` 角色表口径不一致。
>   读路径核实——4 张表仅 customer-service 直连（PII 归属方，生产走 `retail_pii_rw`，且该服务不写 core 表）；
>   报发送量锚定走 `core.v_recon_inv5_consent` 视图（owner = postgres，以视图属主校验底层表权限），业务角色不受影响。
>   处理——新增 `V17__pii_schema_revoke_app_role.sql`（只调授权，不改表结构与数据），同步 `R06 §3 §6` 与各文档号段口径。
> - 2026-11-23：业务端口段由 8090–8098 扩为 **8090–8099**（新增 `recon-service :8099`），
>   同步 `R00 §6`、`README §2`、`R02 §3`、`AGENTS §4`。
> - 2026-11-23：**用户授权**清理收口残留的陈旧表述（§0「下一轮」、§3 旧编号阶段行、§7 假完成警示），
>   按 W9 终态现实回填；事实（验证记录）未改，只清文字残留。
> - 2026-11-23：**用户授权**外部协议先行定源：`R07 §4.1–§4.16` 新增「我方提议协议 v1」字段级规格
>   （通用报文规范 + P-01~P-14，覆盖全部未回填通道）；未新增文档文件与错误码家族，
>   `check_docs.py` 文件计数与 C-xx 登记不受影响。
> - 2026-11-23：**用户授权**（2026-11-23 11:0x「好的」确认）修正文档口径漂移：
  改动 `AGENTS.md`（共享禁区）与 `scripts/check_docs.py`（共享禁区）——
  理由：本轮全量实测证明二者与代码/实测脱节（模块数多算父 pom；§2 分层图描述的共享 jar 实际近乎空壳），
  且 `check_docs.py` 查不出「测试数 / 迁移号段」漂移，需加 C14/C15 守护防复发。
  同步修正 README / PROGRESS / R02 / R03 / R06 / R10 / R11 / 00-文档索引 / `external-api/application.yml` 注释；**未改业务代码**。
- 2026-11-23：**用户授权**全仓文档与 W9 终态对齐：清除 README / `00` / R00 / R02 / R10 / R11 /
>   AGENTS（§4 `run-microservices.sh` 标注）中「零代码/零迁移/零进程、jar 未抽取、服务未联调」等
>   文档初始化时代的陈旧表述，改按实测终态回填；实现事实（`PROGRESS §6` 验证记录）未改。
> - 2026-11-23：**用户口径**——本地各服务统一以 `postgres` 超级用户连库，DB role 隔离**本地不生效**，
>   它是**生产侧物理兜底**；真正兑现要等**按角色分配连接账号**。
>   落地：`R06 §3.1`（新增「连接账号口径：隔离何时真正兑现」）· `R08 PII-5` 与 `R10 A23` 加生效前提标注 ·
>   `ADR-RF-004 §结论 3` 与 `ADR-RF-009 §结论 2` 加限定 · `PROGRESS §7` 加第 7 条警示 · `§6` V16 行加注脚。
>   **未改任何业务代码、配置与已执行迁移**；验证结论不变（授权位配置正确，但**不等于**越权拦截已验证）。
> - 2026-11-23：**用户授权**（「连上一轮 WIP 一起收口提交」）——把上一轮已执行但未纳入版本管理的 **V16 / V17 迁移脚本**与全仓号段口径校正（V1–V15 → V1–V17）一并提交，
>   并提交本轮「DB 隔离口径 + 投产门禁 G-DB-1~G-DB-7」文档改动。
>   提交前按 `AGENTS §5` 复验：构建 BUILD SUCCESS（surefire 类级汇总 0 失败 0 错误，测试数口径以顶部横幅为准）· 冒烟 26/26 + 前端 46/46 · E2E 98/98 · DB 17/17 迁移 + 60 表 · `check_docs.py` 15/15 PASS。
>   复验中暴露**编排脚本两处缺陷**（PID 记账错位导致孤儿占端口、90s 就绪上限偏紧），已记入 §7 第 8 条；**未改该脚本**（属 `AGENTS §7` 共享禁区），修复建议同条列出。
> - 2026-11-23：**用户决策**——只补**上线门禁**，本地维持超级用户连库（不在本地按角色分配账号）。
>   落地：`R06 §3.2` 新增「投产门禁 G-DB-1~G-DB-7」（fail-closed）· `R11 §2` 新增「投产准备」阶段行 ·
>   `R08 PII-5` 与 `R10 A23` 挂门禁引用 · `PROGRESS §3` 后续阶段门禁 · `§4` 新增「投产门禁」待办段。
>   **未改任何业务代码、配置与已执行迁移**；门禁为**投产前**执行项，本地不触发、不阻塞当前验证结论。

---

## 3. 阶段路线（S0–S4，详见 `R11`）

| 阶段 | 内容 | 门禁 | 状态 |
|---|---|---|---|
| **S0** | 业务与法务定源、外部方调研清单回填 | — | ✅ **系统口径完成（2026-11-22）**：`C-01` ✅ 用户签（双形态）；`C-02`~`C-12` 🟦 AI 裁决（`R01 §6`）；DMS 调研 ✅ 回填（集成商模式）；外部核实项转非阻塞跟踪 |
| **S1** | 地基：抽取中台 jar、父 pom、四层共享 jar、网关/DMZ、双主体认证、Flyway V1–V9、12 进程编排 | C-01 签字 ✅ + 号段登记（`R11 §6` 其余项） | ✅ **完成（2026-11-22）**：W1+W2 全绿（tag `w2-frozen`） |
| **S2** | 并行窗口 W3‖W4‖W5（客户与申请 / 审查与征信 / 合同与账务前半） | W2 冻结（`w2-frozen`） | ✅ **完成（2026-11-22）**：三包全合，`mvn clean package` 332 测试 0 失败，走网关冒烟 26/26，Flyway 12/12 迁移、51 张表实测 |
| **S3** | W6 账务闭环与解除 ‖ W7 贷后对拍合规（写入集互斥沿用并行纪律） | S2 全合 | ✅ **完成（2026-11-23 凌晨）**：V13/V14 落地（14 迁移 60 表实测）；合并后 538 测试 0 失败 + 走网关冒烟 26/26 |
| **S4** | W8 收口：全链路 E2E 走网关 + 文档回灌 + 放行 | S3 全合 | ✅ **完成（2026-11-23）**：`scripts/e2e_w8.py` **98 断言 PASS / 0 FAIL / EXIT 0**（可重复跑），一镜到底覆盖 注册→核验→同意→申请→征信→审查→合同→冷静期→债权→三门禁代付→核销→结清→解押闭环→DPD/对拍→客户自助 |
| **S5** | W9 收尾三包：报送执行闭环 / DMZ 三关 / 本地附件存储+日批 | S4 全合 | ✅ **完成（2026-11-23）**：三包合入，终态构建 0 失败 + 冒烟 26/26 + E2E 98/98 无回归；tag `w9-accepted` |
| **后续** | 外部对接与合规验收：DMS 直连演进、征信/银行/身份核验/登记代办/通知通道、个人信息保护控制、访问审计、职责分离、全量回归；**投产准备**（按角色分配连接账号、DB 隔离在运行期生效） | `R07 §7` 调研清单回填 + 外部签约；**投产门禁 G-DB-1~G-DB-7 全满足**（`R06 §3.2`，fail-closed） | ⬜ 未启动（非阻塞，回填前走人工路径） |

---

## 4. 待办清单（按工作包，勾选状态以本表为准）

**S0（业务定源）**
- [x] C-01 交易形态 —— ✅ 已裁决（2026-11-22 业务方：双形态并行；适用法规与登记形态仍属 C-02 法务意见书）
- [x] C-02 适用法规清单与经营资质登记形态 —— 🟦 AI 裁决（双登记建模 + 缺证禁用兜底）
- [x] C-03 总量规制 / 支付能力预估额 的适用与算法口径 —— 🟦 AI 裁决（A 预估额框架 / B 除外贷付）
- [x] C-04 所有权保留登记范围 —— 🟦 AI 裁决：REGISTERED
- [x] C-05 征信机构加盟组合与报送周期、异议申诉时限 —— 🟦 AI 裁决（CIC+JICC / T+1 / 30 日）
- [x] C-06 使用目的、向第三方提供同意、保留期限、查阅请求流程 —— 🟦 AI 裁决（APPI 5 年 + 单独同意）
- [x] C-07 身份核验 / 线上核验 / 反社会势力排查落地方式 —— 🟦 AI 裁决（文件+人工起步）
- [x] C-08 法定书面文件与冷静期 —— 🟦 AI 裁决（模板可配 + 8 日占位）
- [x] C-09 收款渠道组合与首期兜底 —— 🟦 AI 裁决（账户自动扣款主 + 缴款单兜底 / 60 日）
- [x] C-10 提前结清计算与解除触发 —— 🟦 AI 裁决（剩余本金 − 减免额 + 手续费）
- [x] C-11 所有权解除的确认权威方与回执效力 —— 🟦 AI 裁决（外部回执唯一权威）
- [x] C-12 销售店主数据共享方式与加盟店合同口径 —— 🟦 AI 裁决（共享主数据、隔离角色账户）
- [x] `R07 §7` 外部方调研清单 —— **DMS 项 ✅ 已回填**（2026-11-22：数据集成商模式 + 四类接口 + 技术规范，`ADR-RF-011`）；集成商选型与其余通道（征信 / 银行 / 身份核验 / 登记代办 / 通知）仍待回填，**但我方提议协议 v1 已定稿（2026-11-23，`R07 §4.1–§4.16`）**，调研时随清单发出作为谈判基线

**S1–S4**：见 `R11 §3` 工作包 W1–W8，本表不重复罗列（避免第二份会过期的清单）。

**投产门禁（外部对接阶段收口前执行，`R06 §3.2`）**
- [ ] **G-DB-1~G-DB-7 全部满足**：四角色独立登录账号（口令密钥托管，弃用 V1 的 `*_dev`）· 运行连接按角色分配 · 迁移账号与运行账号分离 · 默认权限绑定迁移账号 · 越权拦截实测（`R10 A23` 转正）· 业务流量禁用超管账号 · **审计写入账号归属落地（G-DB-7 已知缺口：当前单连接池 + `retail_app_rw` 无 audit INSERT 权限，切受限账号会写失败）**
- [ ] G-DB-7 的授权调整走**新迁移**（V20–V21 号段；V18 = 产品档位目录、V19 = W-LOCK 回执唯一索引，均已占用）：授 `retail_app_rw` 对 audit 两表的 `INSERT`/`SELECT`（仍禁 UPDATE/DELETE），不改已执行迁移字节（方案见 `R06 §3.2` 架构侧裁决）
- [ ] 在**具备受限账号的类生产环境**执行一次完整实测并留证；**本地全绿不构成门禁通过证据**（本地统一超级用户，`R06 §3.1`）
- [ ] 门禁 **fail-closed**：任一项不满足，生产部署不予放行（`AGENTS §5`）

---

## 5. 业务阻塞与非阻塞区分

| 类型 | 项 | 说明 |
|---|---|---|
| ✅ **已解除** | C-01 | 2026-11-22 业务裁决「双形态并行」；形态差异由 4 策略点承接（`ADR-RF-002` Accepted） |
| ✅ **已解除** | C-05 / C-06 / C-07 | AI 裁决定源（CIC+JICC / APPI 5 年+单独同意 / 文件+人工核验）；系统实现业务规则，**法务效力判断不实现**（留痕+闸门）；核实不符走配置覆盖 |
| 🟡 **可并行** | 中台 jar 抽取、四层骨架、网关/DMZ、通用错误码、审计与加密底座、服务精确装配 | 与法务形态无关，**但按 §0 门禁本轮仍不提前开工** |
| 🟢 **已定（内部架构决策）** | 表/字段/状态机/事件名/端口/号段/错误码/权限点命名、服务清单与网关路由 | 由 `R00` / `R02` 直接裁决，不等业务签字 |
| 🌐 **外部事实** | DMS 协议、征信机构接入周期、文件格式、身份核验供应商 | 系统不凭空对接；内部默认值 + 人工留痕，接口预留 |

> 口径原则沿用公司定源 ADR：**内部架构决策直接裁决，真正的外部事实不凭空对接，不把占位说成已实现**。

---

## 6. 验证记录（本轮实测结果，非预期值）

| 日期 | 命令 | 结果 |
|---|---|---|
| 2026-11-22 | `mvn -B clean package`（floorplan，W1 前基线） | **BUILD SUCCESS，EXIT 0**（1m14s），V-9 对拍基线确立 |
| 2026-11-22 | `mvn -B clean package`（RF 全仓 16 模块，W2） | **BUILD SUCCESS**：父 pom + 四层共享 + 10 业务服务 + 网关 + DMZ；单测 16（common）+ 4（external-api）全绿 |
| 2026-11-22 | `bash run-microservices.sh smoke`（W2） | **26 断言 PASS / 0 FAIL / EXIT 0**：网关 10 路由段切流、DMZ 健康、员工登录（admin/admin123 BCrypt + core.users 种子）、whoami 三态（无 token 401 / 员工 200 / 客户 token 401）、伪造 token 401、X-Request-Id 贯穿、DMZ 验签四态（合法 200 / 篡改 401 / 过期 401 / 未登记 403）、Flyway V1–V9 = 9 条迁移记录、四 schema 49 张表、种子员工存在 |
| 2026-11-22 | Flyway 实测（retail_finance:5435，独立容器 retail-finance-db） | **V1–V9 9 条迁移全部 success**；期间修复 V1 `retail_audit_ins` LOGIN+NOLOGIN 冲突选项（SQL 修正后重建容器重跑） |
| 2026-11-22 | `mvn -B clean install`（company-platform-contracts） | **BUILD SUCCESS**：34 用例 / 0 失败 / 0 错误，`1.0.0-SNAPSHOT` 已装本地仓（切断点 ①③ 单测覆盖） |
| 2026-11-22 | grep 业务包引用（platform 仓） | **零命中**：不 import `com.company.floorplan.*` / `com.company.retail.*` |
| 2026-11-22 | `git status`（floorplan） | **干净**：W1 未触碰 floorplan 任何文件，基线即终态（V-9 达成） |
| 2026-11-22 | `python scripts/normalize_terms.py` | PASS：扫描 26 份 `.md`，重命名 3 个 ADR 文件，完成全仓日文术语 → 中文替换与引用同步，**残留假名 0** |
| 2026-11-22 | `python scripts/normalize_terms.py --check` ×2 | PASS：连续两次 **0 变更 / 0 残留**，幂等已验证 |
| 2026-11-22 | `python scripts/check_docs.py` | **PASS（13 项 / FAIL 0 / EXIT 0）**：编号连续唯一、索引引用可达、跨文档引用可达、`C-xx` 登记完整、端口不冲突、全文中文、禁用日文词零命中、微服务口径、R 三节 / ADR 四节、术语映射幂等、文件计数 29 |
| 2026-11-22 | C-01 定源落地 | **PASS**：`ADR-RF-002` Proposed→Accepted（双形态并行）；`R00`、`R01 §2 §6 §7`、`R02`、`R04`、`R10`、`R11`、`README`、`00` 全部同步；`check_docs.py` 复跑 PASS |
| 2026-11-22 | DMS 对接定源落地 | **PASS**：新增 `ADR-RF-011`（数据集成商起步 / 核心直连演进，Accepted）；`R07 §0 §2.1 §3 §4(I-11/I-12) §6 §7 §8 §9` 回填；`R06` 增假名姓名加密列；`check_docs.py` 升级为 11 ADR / 30 项并复跑 PASS |
| — | `check_docs.py` 接入 CI | **已落地**：`.github/workflows/docs-check.yml`（check_docs + normalize --check 两步） |
| 2026-11-22 | S2-W3 合并验收（`wt/w3` `233d7d4`） | **PASS**：合后 `mvn clean package` 全绿；走网关冒烟 **26/26**（V10 套用，10/10 迁移）；W3 自验 29 单测 + 临时库冒烟全绿 |
| 2026-11-22 | S2-W4 合并验收（`wt/w4` `ac7af5f`） | **PASS**：合后 `mvn clean package` 244 测试 0 失败；走网关冒烟 **26/26**（V11 套用，11/11 迁移、50 表）；W4 自验 73 单测 + 25 冒烟全绿（期间一次编排就绪轮询被 cygwin fork 失败卡死，止损重跑） |
| 2026-11-22 | S2-W5 合并验收（`wt/w5` `5caf329`） | **PASS**：合后 `mvn clean package` **332 测试 0 失败**；走网关冒烟 **26/26**（V12 套用，12/12 迁移、51 表）；三 worktree/分支全部清理 |
| 2026-11-23 | S2 遗留共享区收口（`dc57c1f`） | **PASS**：common 双拦截器（客户自助路径挂客户验签器，员工拦截器放行）+ customer-service `/api/customer-self/me`（客户 token 查本人脱敏视图）；构建 EXIT 0 |
| 2026-11-23 | S3-W6 合并验收（`wt/w6` `eb37f15`） | **PASS**：合后 `mvn clean package` 444 测试 0 失败；走网关冒烟 **26/26**（V13 套用，13/13 迁移、52 表）；W6 自验 88 单测 + 69 冒烟断言（核销顺序/余额对拍/C-10 公式/解除八步/两态分离/回执权威） |
| 2026-11-23 | S3-W7 合并验收（`wt/w7` `e3a1cae`） | **PASS**：合后 `mvn clean package` **538 测试 0 失败**；走网关冒烟 **26/26**（V14 套用，14/14 迁移、60 表）；W7 自验 47 单测 + 26 冒烟（DPD/催收状态机/报送触发/INV-1~5 对拍/审计留痕） |
| 2026-11-23 | W8 E2E 定稿 | **PASS**：`python scripts/e2e_w8.py` **98/98 全绿 × 多轮可重复**；期间修复 ①网关 `/api/jobs/**` 通配误劫持 retail-core `schedule-due-rollup`（改 servicing 只精确收 `dpd-snapshot`）②`/api/customer-self/**` 路由段补齐 ③建库固化 `max_connections=300`；复跑 W2 冒烟 26/26 无回归 |
| 2026-11-23 | W9 三包合并终态验收 | **PASS**：A（bureau 报送执行闭环：DPD_CHANGED/LOAN_SETTLED 消费→SENT+CREDIT_BUREAU_REPORT_SENT+报文留痕，人工 MANUAL 通道，日批默认关）/ B（DMZ 三关：nonce 原子防重放 409/scope/IP 白名单可配/call_logs append-only）/ C（本地附件存储：SHA256+预签名+防穿越+受控下载；到期滚转/DPD 日批 batch_runs 抢占锁防重）→ 终态 `mvn clean package` **706 测试 0 失败**；重启栈后冒烟 **26/26** + E2E **98/98** 无回归 |
| 2026-11-23 | W-SIM 模拟对接双向闭环 | **PASS**：①出站——`HttpCicAdapter`（双模 `RETAIL_BUREAU_CIC_URL`，提议协议 v1 信封）接 Node 模拟器，查询/报送真 HTTP 往返；②入站——模拟器按 DMZ 签名规范回调 `/external/registry/receipts`（过三关+call_logs）→ 系统服务 token 转发 title-release → EXTERNAL_REGISTRY 自动 CLOSED；`demo_e2e.py` **28 断言 PASS/0 FAIL**（含未签名/坏签名/nonce 三关负例）；配套修复真实缺陷：事件驱动报送在调度线程无请求上下文 → consent gate 401 fail-closed 报送永远发不出（e2e_w8 未覆盖该断言，单测 mock 了 gate）→ `ConsentGateFacade` 机器场景签系统服务 token（svc-bureau） |
| 2026-11-23 | W-SIM2 外部模拟器全通道化 | **PASS**：模拟器扩至六向对端（+银行代付 P-09/eKYC P-12/DMS 结果 I-02/送达 P-14/入金文件 P-11）；Java 侧四服务双模接线（retail-core `BankPayAdapter` 替换内联 PAID、customer `KycProvider` 脱敏号出站、underwriting `DmsResultNotificationConsumer`+outbound_tasks 兑底、contract `DeliveryChannelAdapter` 留痕旁路）；`demo_e2e.py` **34 断言 PASS/0 FAIL**；`smoke_frontend.py` 46/46；`browser_e2e.py` 30/30；全量 `mvn clean package` **BUILD SUCCESS 单测 353/0**（本轮校正：此前记录 706/748 系统计虚高，权威口径=surefire 类级/模块级汇总）；配套修复：①customer 构造器歧义导致启动失败（删冗余构造器）②`registerRetention` 与 LOAN_SETTLED 兜底建单竞态 409 → 幂等返回既有记录 |
| 2026-11-23 | W-FE 浏览器点击级验收 | **PASS**：`browser_e2e.py`（Playwright 驱动真实 Edge headless）逐按钮点击 S1–S12 全链路 + 假合同负例 + 客户自助脱敏/主体隔离负例 + Tab 切换/ID chips/计划表/请求日志面板/无 JS 错误/无失败请求，**30 断言 PASS/0 FAIL ×2 轮可重复**；发现并修复真实 bug：GET 请求携带 null body 违反浏览器 fetch 规范（仅真实浏览器触发，requests 复现不了）→ `app.js` call() 修复；截图留证 `frontend/screenshots/` |
| 2026-11-23 | W-FE 前端页面级验收 | **PASS**：`frontend/`（index.html + app.js 运营台/客户自助两页 + serve.py 同源代理 /gw→18081、/svc→8098、/cust→8092）；配套补 title-release 只读端点 `GET /api/release-orders?loanId=`（页面定位事件建单，42 单测 0 失败）；`python frontend/smoke_frontend.py` **46 断言 PASS / 0 FAIL / EXIT 0 × 2 轮可重复**（静态页/登录/注册/核验/同意/申请+幂等/审查+G3 负例/合同/三门禁+G8 负例/核销对拍/结清/解押闭环+X-10 负例/DPD/recon/客户自助脱敏+主体隔离负例，全部经 18082 同源代理走真实链路） |
| 2026-11-23 | PROGRESS 陈旧表述清理（用户授权） | **PASS**：§0「下一轮」改写为 W9 放行后的外部对接阶段；§3 删除旧编号 S2/S3/S4 残留行，合并为「后续」行；§7 六条假完成警示按现实回填；`check_docs.py` 复跑 PASS |
| 2026-11-23 | 外部提议协议 v1 定稿（用户授权） | **PASS**：`R07 §4.1–§4.16` 新增通用报文规范（信封/TLS/编码/JST/BIGINT 金额/错误体/重试基线）+ P-01~P-14 字段级协议（资质预审/授信申请/审查结果/交车代付/物权同步/征信查询报送/异议/银行代付/扣款与入金文件/缴款单/eKYC/解除回执/书面送达）；I-01~I-12 状态列同步 🟦；`check_docs.py` PASS 13/13 EXIT 0 |
| 2026-11-23 | 仓库收尾（用户指示：仅保留 master） | **PASS**：master HEAD `8ded5ea`（E2E 截图产物刷新提交）；全仓仅 `refs/heads/master` 一个分支、主目录一个 worktree（`wt/w3~w9` 已随历次合并删除，`git worktree prune` 无残留）；验证进程全停（前端 18082 / 模拟器 18100），端口释放；仓库无远程（无推送步骤） |
| 2026-11-23 | 文档终态更新（本节） | **PASS**：§0 轮次/验证行对齐 W-SIM2 终态；§6 补收尾记录；新增 §8 工期与运维投入评估；`check_docs.py` 复跑 PASS |
| 2026-11-23 | **口径漂移复验与修正**（用户授权「修正文档口径不一致」） | **PASS**：本轮全量实测 `mvn -B -o package` → **BUILD SUCCESS / 353 测试 0 失败**（49 个测试类，surefire 类级汇总实测）；`check_docs.py` **PASS 15/15 EXIT 0**；DB 实查 `flyway_schema_history` → **15 条迁移全部 success**，四 schema **60 表**（core 43 / integration 9 / pii 6 / audit 2）；`run-microservices.sh smoke` **EXIT 0**（前端页面 46/46）；`scripts/e2e_w8.py` **98/98**；`external-simulator/demo_e2e.py` **34/34**。修正 4 类漂移：①**测试数 706→353**（README ×2 / R02 / R10 / 00-文档索引 ×2 共 7 处）；②**迁移号段 V1–V14→V1–V15**（README / PROGRESS ×2 / R02 / R06 ×2 / 00-文档索引 ×2 / R11 历史行 / `external-api/application.yml` 注释）；③**`AGENTS.md` §2 分层图与代码实况不符**——共享 `domain`(3 文件)/`application`(1 文件) 近乎空壳，真实分层是**服务内** `*.domain`/`*.application` 包，已补「实际落点」表与 53 文件零 Spring 实测说明；④**`AGENTS.md` 模块数分解式多算父 pom**。新增守护：`check_docs.py` **C14**（号段上限必须 == 磁盘迁移数）+ **C15**（测试数口径唯一 == 353），两项均经**注入反向验证**（改回 V1–V14 / 706 → 分别 FAIL EXIT 1，恢复后 PASS）。**未改任何业务代码**；AGENTS.md 与 scripts/check_docs.py 属共享禁区，本轮经用户授权修改，理由已记入 §2 变更记录 |
| 2026-11-23 | **V16 迁移实施与验证**（用户授权：撤销 `retail_app_rw` 对 `pii.customer_pii` 的 SELECT） | **PASS**：`mvn -B -o package` → 17 模块 BUILD SUCCESS（构建 353 单测 0 失败，原记录口径；V17 轮修复后为 359）；`bash run-microservices.sh smoke` → **26/26 EXIT 0**（含「Flyway 迁移全部执行 16 / 16 条」「四 schema 60 张」）+ 前端页面 46/46；DB 实查 `flyway_schema_history` → **16 条全部 success，V16 = pii customer pii revoke**；权限位实查：`retail_app_rw`→`pii.customer_pii` SELECT = **false**、`retail_app_rw`→`pii.customers` SELECT = true、`retail_pii_rw`→`pii.customer_pii` SELECT = true、`retail_dmz_rw`→`pii.customer_pii` SELECT = false；`pg_default_acl` 实查 pii schema 默认授权只剩 `retail_pii_rw`（今后新建 pii 表默认不授予业务角色）；`check_docs.py` PASS 15/15。**未改任何业务代码，已执行迁移字节未动**；残余：`retail_app_rw` 对 `pii.consents` / `identity_*` 的 SELECT 沿用 V1 既有授权，本次未收（要一并收紧走下一号迁移）；**授权位级实测 ≠ 越权拦截实测**（本地统一超级用户连库，生效边界见 §7 第 7 条与 `R06 §3.1`） |
| 2026-11-23 | **V17 迁移实施与验证**（用户授权：撤销 `retail_app_rw` 对 `pii.consents` / `pii.consent_withdrawals` / `pii.identity_verifications` / `pii.identity_documents` 的 SELECT） | **PASS**：`mvn -B -o package` → 17 模块 BUILD SUCCESS（构建 359 单测 0 失败，含新增 6 个幂等单测；原记录口径）；`bash run-microservices.sh smoke` → **26/26 EXIT 0**（含「Flyway 迁移全部执行 17 / 17 条」）+ 前端页面 46/46（复跑轮）；`scripts/e2e_w8.py` → **98/98 EXIT 0**（含 S12 recon INV-5 同意锚定对拍）；DB 实查 `flyway_schema_history` → **17 条全部 success**（V17 = pii schema revoke app role），`retail_app_rw` 在 pii schema 实测只剩 `pii.customers : SELECT`；**越权拦截实测（`R06 §3.1` G-DB-5）本地已实测**：用角色口令直连容器执行——`retail_app_rw`→`pii.customer_pii`/`consents`/`consent_withdrawals`/`identity_verifications`/`identity_documents` 全部 permission denied，`pii.customers`（45 行）/ `core.v_recon_inv5_consent` 视图 / `core.loans` 可读；`retail_pii_rw` 可读 `pii.consents`/`customer_pii`/`identity_verifications`；`retail_dmz_rw` 对 pii schema 无权、`integration.nonces` 可读（13/13 符合设计；服务自身连接池仍为超管，边界见 `R06 §3.1`）。**未改任何业务代码，已执行迁移字节未动**。另记：首轮前端冒烟 1 项失败（F11 保留登记撞 `uk_title_loan`）系 title-release 既有「先查后插」竞态（W-SIM2 幂等修复只覆盖「查时已存在」），与本次授权变更无关，复跑轮与 E2E 全绿；修复方案已实施（见下一行记录） |
| 2026-11-23 | **保留登记竞态修复**（用户授权「现在修：加幂等兜底+单测」） | **PASS**：根因——`registerRetention` 先查后插，`uk_title_loan` 唯一键异常直接冒泡 → HTTP 500（前端冒烟 F11 保留登记偶发失败，复跑才绿）。修法——①抽端口 `TitleRepositoryPort`（对齐 bureau `BureauReportRepository` 范式）+ `JdbcTitleRepository` 改 `INSERT ... ON CONFLICT (loan_id) DO NOTHING RETURNING title_id`（冲突返回 0，不抛异常、不使 PG 事务 aborted）②`registerRetention` 增冲突分支：幂等返回既有记录、不重复发布登记事件 ③LOAN_SETTLED 兜底建单下沉为 `ensureRetentionForSettledLoan`，冲突幂等跳过。**验证**：`mvn -B -o package` → 17 模块 BUILD SUCCESS（**构建 359 单测 0 失败**；新增 `TitleRetentionIdempotencyTest` 6 例全绿，测试数 353→359；原记录口径）；真实 PG 语义实测（冲突 → `INSERT 0 0`、无异常、事务未 aborted、行数仍 1）；**真实竞态应用级实测**（备用端口段 18190/18197 + BEFORE INSERT 触发器放大窗口，服务 INSERT 真撞唯一键）→ **HTTP 200 而非 500**，返回兜底既有行，DB 行数=1、outbox 事件=1；备用端口段全栈：走网关冒烟 **26/26**，E2E 走网关断言 **91/91 全绿**（含 S11 保留登记→解除八步→外部回执闭环、outbox 无 PII）。**注册端口段标准验收本轮未能复跑**：并行会话在 WSL 侧持续占用 8090–8099/18081/18091（其 `.cleanup-rf.sh` 按命令行匹配杀进程），本轮以备用端口段等效验证；E2E 余 7 项失败为 `scripts/e2e_w8.py` 硬编码直连 8092/8098（`scripts/e2e_w8.py:719,759`）在备用段不存在所致，非代码回归。**未改迁移字节，未改其他服务** |
| 2026-11-23 | **DB 隔离口径 + 投产门禁收口验收**（本轮：口径固化 + 投产门禁 G-DB-1~G-DB-7；未改业务代码） | **PASS**：整树 `mvn -B -o package` → **BUILD SUCCESS**（surefire 类级汇总 359 测试 0 失败 0 错误）· `scripts/e2e_w8.py` → **98/98 EXIT 0**（含 S14 outbox 12 类齐全、INV-5 同意锚定对拍）· DB 实查 `flyway_schema_history` → **17 条全部 success**、四 schema **60 张**、`retail_app_rw` 在 pii schema 实测仅剩 `pii.customers : SELECT` · `python scripts/check_docs.py` → **PASS 15/15 EXIT 0**。**过程记录**：① `run-microservices.sh smoke` 首轮 25/26（`title-release` 一次性 socket 层抖动：Tomcat acceptor `Error setting socket options` + 网关 `Connection reset by peer`），复跑轮 **26/26 + 前端 46/46 EXIT 0**；② 满负载下（本机 31.8GB 内存仅余 4.9GB、421 进程、floorplan 栈并行）出现网关就绪需 105s > 脚本 90s 上限、路由检查超时等抖动，**非代码缺陷**——编排缺陷已记入 §7 第 8 条（`run-microservices.sh` 属共享禁区，本轮**未改**） |
| 2026-11-23 | **注册端口段标准验收复跑**（本轮：补跑上一轮缺失的注册端口段验收；未改业务代码） | **PASS**：`mvn -B -o package` → **17 模块 BUILD SUCCESS（构建 359 单测 0 失败 0 错误，原记录口径**，50 个测试类，surefire 类级汇总实测）；`run-microservices.sh smoke` → **26/26 EXIT 0**（含「Flyway 迁移全部执行 17 / 17 条」「四 schema 建表 60 张」）+ 前端页面 **46/46 EXIT 0**；`scripts/e2e_w8.py` → **98/98 EXIT 0**（含 S11 保留登记→解除八步→外部回执闭环、S14 outbox 12 类齐全）；`check_docs.py` → **PASS 15/15 EXIT 0**；DB 实查 `flyway_schema_history` → **17 条全部 success**、`retail_app_rw` 在 pii schema 仅剩 `pii.customers : SELECT`、`pg_default_acl` 的 pii 默认授权仅 `retail_pii_rw`。**执行位置变更（环境事实，见 §7 第 9 条）**：Windows 侧**绑不上** 8090–8099/18081/18082/18091（`netstat` 查不到占用者，但 `bind()` 一律返回 WSAEADDRINUSE），故注册端口段验收改在 **WSL 侧执行**——`.wslconfig` 为 `networkingMode=mirrored`，WSL 与 Windows 共享同一网络命名空间，所绑即真实注册端口（Windows 侧可经 127.0.0.1 访问）；WSL 侧 JDK 为 **25.0.5.1**（项目目标 17），12 个部署单元实测全部正常启动。**过程记录**：首轮 E2E 出现 2 项失败（PASS 96 / FAIL 2，均为 S11「outbox 发出 TITLE_RETENTION_REGISTERED」），根因是 outbox 积压的 `LOAN_SETTLED` 被消费者抢先消费 → 兜底建单先于显式登记落库 → 显式登记命中「已存在」幂等分支不重复发事件（**既有语义、非本轮回归**，见 §7 第 10 条）；清空积压后复跑 **98/98** |
| 2026-11-23 | **登记订正 + 登记事件补发**（用户裁决「补发事件并订正VIN」） | **PASS**：根因——`LOAN_SETTLED` 兜底建的是**挡板占位行**（`vin=UNKNOWN-<loanId>`），占位行先于操作员正式登记落库时，显式登记命中「已存在 → 幂等返回」→ **登记事件永不发出**，证据链缺「所有权保留登记」这一环（E2E S11 的 `TITLE_RETENTION_REGISTERED` 断言失败，实测 PASS 96 / FAIL 2）。修法（口径已同步 `R04 §6` / `R09 §5.1`）：①领域 `TitleRetention` 增占位 VIN 约定（`placeholderVin` / `isPlaceholder`）与**登记订正**方法（`correctRegistration`）——只改 VIN / 登记模式 / 登记时间，**不改状态**，跨态订正一律 `RF_VALID_002` 拒绝（INV-3 唯一入口不可旁路）②端口 `TitleRepositoryPort.correctPlaceholderRegistration` + JDBC `UPDATE ... WHERE title_id=? AND version=? AND vin=?`（**三重并发守卫**：乐观锁 + 占位 VIN），**不写 status** ③用例层 `registerRetention` 命中占位行 → 订正并**补发一次** `TITLE_RETENTION_REGISTERED`（影响行数为 1 者才补发；并发/重放落到 0 行 → 幂等返回、不重复发事件）。**验证**：`mvn -B -o package` → 17 模块 BUILD SUCCESS（**构建 365 单测 0 失败 0 错误（原记录口径）**，50 个测试类；`TitleRetentionIdempotencyTest` 6→12 例，覆盖订正/补发/并发只补发一次/跨态被拒）；**真实 PG + 应用级实测 8/8 全绿**（走网关 18081 实调 `POST /api/title-retentions`：占位行 `UNKNOWN-999901` → 订正为 `VIN-REAL-999901`、`version` 0→1、登记事件 37→**38** 且仅此 1 条对应该 `titleId`、**重复登记后事件数不变**（38）、陈旧版本订正**影响 0 行**、测试数据清理残留 0）；注册端口段走网关冒烟 **26/26** + 前端页面 **46/46**、`scripts/e2e_w8.py` **98/98**、`check_docs.py` **PASS 15/15**。**未改迁移字节，未改 `scripts/` 禁区文件** |
| 2026-11-23 | **持久层全量 MyBatis 化（W-MB，用户指示「jdbc 都换成 MyBatis」）** | **PASS**：12 个部署单元的持久层由 `JdbcTemplate` + `RowMapper` **全量迁到 MyBatis 3.0.3 注解 SQL**（无 XML、无 JPA）。红线：**SQL 语义、仓储方法签名、事务边界、对外接口一律不变**（`AGENTS.md` B-12）。改动面——①父 `pom.xml` 加 `mybatis-spring-boot-starter` 依赖管理，`infrastructure`/`external-api` 显式声明依赖；②新增共享映射件 `common/.../common/persistence/RowValues.java`（`asLong`/`asLongOrNull`/`asInt`/`asString`/`asBoolean`/`asInstant`/`asLocalDate`/`asBigDecimal`/`asTimestamp`/`asBytes`，NULL 口径对齐 `ResultSet.getXxx`：`getLong`→0、`getString`→null）；③各服务新增 `*.mapper` 包 + `@MapperScan` **显式列单一包**（精确装配，不做全包扫描，也不依赖 starter 自动扫描），共享 `infrastructure` 的 mapper 由 `RfOutboxConfig` 上的 `@MapperScan` 单独注册；④11 个服务 `application.yml` 加 `mybatis.configuration.local-cache-scope: STATEMENT`，**对齐原 JdbcTemplate「每次读都落库」的读语义**，避免同一事务内重复 SELECT 命中 MyBatis SESSION 级本地缓存旧行；⑤唯一键幂等统一走 `ON CONFLICT` 原子语义（`ON CONFLICT DO NOTHING`，**不采用「捕获唯一键异常再重读」**——PG 事务内一旦触发唯一键错误，整笔事务即被置为 aborted），已对**真实 PG 实测**三处：`collection_cases` 部分唯一索引推断（`ON CONFLICT (loan_id) WHERE status <> 'CLOSED'` 首次 INSERT 0 1 / 再插 INSERT 0 0）、`bureau_report_triggers`（1 → 0）、`batch_runs` upsert（两次均 INSERT 0 1）；⑥MyBatis `@Options` 属性为 `keyColumn`（**单数**，无 `keyColumnNames`），自增主键统一「单 Map 参数 + `keyProperty` 回填」，等价原 `RETURNING` 空集语义。**验证**：`mvn -B clean package` → 17 模块 **BUILD SUCCESS（surefire 类级汇总 365 测试 0 失败 0 错误）**；`bash run-microservices.sh smoke` → **26/26 EXIT 0** + 前端页面 **46/46**；`scripts/e2e_w8.py` → **98/98 EXIT 0**；`external-simulator/demo_e2e.py` → **34/34**；`frontend/browser_e2e.py`（Playwright + 系统 Edge，逐按钮点击）→ **30/30**；`python scripts/check_docs.py` → **PASS 15/15 EXIT 0**（C15 权威口径 365）。**未改 Flyway 迁移字节（V1–V17 全部 success、60 表）**、未引 MQ/Seata/Redis/对象存储、未改 `scripts/` 与 `run-microservices.sh`；文档口径同步回灌 `README` / `R00` / `R06` / `R10` / `AGENTS` 与 `interview/` 三份求职材料 |
| 2026-11-23 | **W-CACHE / W-RL 产品档位配置化 + 两级缓存 + 网关限流**（用户裁决「配置化完整落地 + Caffeine L1 / Redis L2」+「网关限流补 Redis 令牌桶」） | **PASS**：`mvn -B clean package` → **BUILD SUCCESS（surefire 类级汇总 398 测试 0 失败 0 错误，55 个测试类）**；`bash run-microservices.sh smoke` → **33/33 EXIT 0**（原 26，+7：限流触发 429 / 首个请求放行 / 响应体含 `RF_GATEWAY_001` / 读档位 / 二次读走缓存 / 档位种子 / 快照三列已加）+ 前端页面 **46/46**；`scripts/e2e_w8.py` → **104/104 EXIT 0**（原 98，+6：建档位 → 按档位生成计划 → 快照落库 → 改版到版本 2 → 缓存已失效 → **历史计划快照与期次利息均不变**）；`external-simulator/demo_e2e.py` → **34/34**；`frontend/browser_e2e.py` → **30/30**；DB 实查 `flyway_schema_history` → **18 条全部 success**，四 schema **61 张表**，产品档位种子 2 条、`core.repayment_plans` 快照三列已加；`check_docs.py` → **PASS 15/15 EXIT 0**（C14 号段上限 = 18、C15 权威口径 = 398）。**关键断言实测**：改档位到版本 2（rateBp 100）后**历史计划快照仍为 `1|0`**、**历史期次利息仍为 0（不重算存量）**；限流探针路径首个请求放行、超限返回 429 且响应体含 `RF_GATEWAY_001`。**既有迁移字节未动、未引入 MQ/Seata/对象存储**；Redis 仅两类用途（只读配置 L2 + 网关限流态） |
| 2026-11-23 | **生产就绪清单并入 R11 §2.1**（用户指示「将两轮生产就绪清单合并到 docs/R11 的投产准备阶段，并运行文档自检」） | **PASS**：两轮审计（主题分级 P0 10 项 / P1 9 项 / P2 9 项 + 模块视角 16 个 Maven 模块 + 端到端横切 8 项 + 「与现实差最远的六件事」+ 一处不要误改）合并进 `docs/R11-演进路线图与开工门禁.md` **§2.1「生产就绪清单（投产门禁展开）」**，同步 §0 文档控制（v2.1）、§1 引用口径、§2 阶段总览「投产准备」行判据、§9 自检表；**审计基线按当前 HEAD `3c46876` 复核**（Flyway 18 条迁移已执行 / 16 个 Maven 模块 / 四 schema 61 表），复核中**订正审计旧结论 2 处**：①网关**征信路径已有**专属限流规则（10 rps / 突发 20），真实缺口是**登录路径无专属规则**（落 `/api/**` 兜底 500 rps / 突发 1000）②出站 HTTP 计 **3 处裸客户端完全无超时 + 6 处只有连接超时、无读超时**（非「7 处全裸」）；另新发现 `external-api/application.yml` 的迁移条数注释与磁盘实际不符，已改写为「条数以迁移目录为准」。`python scripts/check_docs.py` → **PASS 15/15 EXIT 0**（C13 文件数 31 项不变、C14 号段上限 = 18、C15 权威口径 = 398）——**隔离副本实测同结果**（剔除并行会话未提交的 `PROGRESS §9` 后复跑仍为 15/15）。**当前工作树整体 C4 FAIL 1 项，唯一原因是并行会话未提交的 `PROGRESS §9` 引用了超出 001–012 登记表的 ADR 编号**，按 `AGENTS.md §7` 不覆盖他人 WIP，待其补齐该 ADR 或改写引用即恢复。**本轮只改 1 份文档 + 1 处配置注释，未改业务代码、未改迁移字节、未碰 `scripts/` 禁区** |
| 2026-11-24 | **W-RL 网关限流双模式合入 master**（用户指令「把当前分支合并到 master」+「两套限流实现都合并，用一个配置作为开关，来采用哪种限流方式」） | **PASS**：①`mvn -B -o package` → **BUILD SUCCESS**（surefire 类级汇总 **504 测试 / 74 个测试类 / 0 失败 0 错误 0 跳过**；较合入前 484 增 20 = 进程内过滤器 8 + 令牌桶 6 + 策略解析 3 + **模式开关互斥 3**）②**限流专项活体验收**：`scripts/smoke_ratelimit.py --mode redis` → **29/29 EXIT 0**；`--mode memory` → **33/33 EXIT 0**（两套实现各起真实网关进程打真实 HTTP；`mode=memory` 下「换 Authorization 头不换桶」、429 带 `X-RateLimit-*` 与 `details.policy`；`mode=redis` 下按调用方指纹分桶、429 响应体 `details.path`）③走网关冒烟 **33/33 EXIT 0** + 前端页面 **46/46 EXIT 0** + `scripts/e2e_w8.py` **104/104 EXIT 0**（**零回归**）④`python scripts/check_docs.py` → **PASS 15/15 EXIT 0**（C2 = 15 份 ADR、C13 = 36 项、C14 = 20 条迁移、C15 权威口径 = 504）⑤**合并协调**：`git merge --no-ff wt/ratelimit`，**16 个冲突**（8 文档 + 5 代码 + 3 求职材料）全部按「**master 为底、只挑增量**」手工解；分支 12 个独有文件并入并**去类名冲突**（`InMemory*` 前缀，参数前缀 `retail.gateway.rate-limit.memory.*`）；**互斥装配**用互补 `@ConditionalOnProperty`（`redis` 缺省 `matchIfMissing=true` ／ `memory`）→ **同一时刻只注册一个限流过滤器**，`RateLimitModeSwitchTest` 在**装配期**钉死（叠加即双重限流）。**未新增 Flyway 迁移**（V1–V20 字节未动）、**未改 `scripts/check_docs.py`**（修订既有 `ADR-RF-012` 而非新建 ADR，ADR 仍 15 份） |
| 2026-11-23 | **W-MERGE 并行路线合并收口验收**（`cf78529`，用户裁决「要，按 §9 裁决合并（MQ 不合）」） | **PASS**：①`mvn -B -o package` → **BUILD SUCCESS**（父 pom + 17 个 Maven 模块；surefire 类级汇总 **434 测试 / 61 个测试类 / 0 失败 0 错误 0 跳过**；本轮新增测试类：`statemachine` 引擎 1、`LoanStateMachineEngineTest`、`ReleaseStateMachineEngineTest`、`RegistryReceiptIdempotencyTest` 4 例、`RateLimitFilterTest` 6 例、`ClientIpResolverTest` 7 例）②注册端口段走网关冒烟 **33/33 EXIT 0** + 前端页面 **46/46 EXIT 0**（8090–8099 / 18081 / 18091，**WSL 侧**执行，见 §7 第 9 条）③`scripts/e2e_w8.py` → **104/104 EXIT 0**（走网关 18081，`run=e2e8-1795447783`）④`python scripts/check_docs.py` → **PASS 15/15 EXIT 0**（C2 = 13 份 ADR、C13 = 32 项、C14 = 19 条迁移、C15 权威口径 = 434）⑤DB 实查 → `flyway_schema_history` **19 条全部 success**（installed_rank 1..19 连号）、四 schema **61 张表**（core 44 / integration 9 / pii 6 / audit 2）、`uk_receipt_order_authority_ref` 存在。**合并内容**：①`wt/lock` **只取回调幂等**（V19 表达式唯一索引 + `ON CONFLICT DO NOTHING` + 写入前查；**不合** Redisson / `common/lock`——锁不作正确性来源，`ADR-RF-013`）②`wt/statemachine` **合入**新增共享模块 `statemachine`（Spring StateMachine 4.0.0 进程内引擎；**领域状态图仍是唯一事实源**、`domain` 层零 Spring 不变）③`wt/ratelimit` **只取增量**（`nginx/` 可选部署形态 + `ClientIpResolver` 可信代理 IP 口径 + `trusted-proxy-cidrs` 默认空 = 不采信转发头；**master 的 Redis 令牌桶实现整体保留**）④`wt/mq` **不合**（违 `AGENTS.md` B-10，其 `V20__mq_envelope_channel.sql` 整条弃用）。**过程记录（三次起栈失败均为环境污染 / 并发编排，非代码缺陷）**：①共享验证库被 `wt/mq` 路线迁移污染 → Flyway 报 `Detected resolved migration not applied to database: 19`，**重建库**后 19 条干净重跑（**未用 `outOfOrder=true` 绕过**）②两个会话**同时起栈抢同一注册端口段**（8090 / 18081），双方共用 `.microservice-pids` 目录互删 pid 文件 → 孤儿 JVM 占端口③`run-microservices.sh` 的 `port_in_use()` 用 Windows 版 `netstat -ano`，在 WSL 下恒为空 → 占用拦截在 WSL 永不生效（**脚本属共享禁区，本轮未改**，仅记录，见 §7 第 13 条）；**串行化后一次跑通**。**未改已执行迁移字节**；G-DB-7 审计写入授权调整顺延 V20（`R06 §6` / `PROGRESS §2` 已登记） |

| 2026-11-23 | **Windows 行尾纪律固化 + WSL 活体复现**（用户指示「把解决的办法写到 agents.md 里」） | **PASS**：①**WSL 活体复现**（Ubuntu / bash 5.3.9）——CRLF 脚本 `bash x.sh` → `line 2: set: pipefail\r: invalid option name`；CRLF 脚本直接执行 → `env: $'bash\r': No such file or directory`；CRLF `.py` 直接执行 → `env: $'python3\r': No such file or directory`，改用 `python3` 走解释器 → 正常；LF 同款脚本 → 全部正常。**关键实测：`bash -n` 对 CRLF 脚本不报错** → 该问题**只能靠 `git ls-files --eol` 判定**，语法检查检不出。②**仓库状态实测**：`git ls-files --eol` 索引侧**全为 `i/lf`**（blob 本身是 LF，**仓库没被提交坏**，坏的是工作区副本）；工作区侧 `.sh` 因 `.gitattributes` 已是 `w/lf`，`.py` 此前为 `w/crlf` → 本轮随属性扩围转回 LF。③**修复落盘**：`.gitattributes` 增 `*.py text eol=lf`；本仓 `core.autocrlf` 由 `true` 改为 `input`；`AGENTS.md` 新增 §4.1（现象 / 根因 / 三层修复 / 判定命令 / 纪律）+ 反模式 A-21 + §5 提交前行尾门禁 + §11 检查单。④`python scripts/check_docs.py` → **PASS 15/15 EXIT 0**。**未改任何业务代码、未改已执行迁移字节、未碰 `scripts/` 禁区文件**；`AGENTS.md` / `PROGRESS.md` 属共享禁区，本轮经用户明确授权修改，理由已记入 §2 变更记录 |

| 2026-11-24 | **W-MQ MQ 削峰与密文信封合入验收**（用户裁决「改口径合并 wt/mq」） | **PASS**：①`mvn -B -o package` → 17 模块 **BUILD SUCCESS**（surefire 类级汇总 **468 测试 / 68 个测试类 / 0 失败 0 错误 0 跳过**）②注册端口段（8090–8099 / 18081 / 18091，**WSL 侧**执行）走网关冒烟 **33/33 EXIT 0** + 前端页面 **46/46 EXIT 0** + **MQ 关闭态 `scripts/smoke_mq.py` 11/11 EXIT 0**（默认关闭 → 中继未接管、`relayed_at` 全 NULL、事件仍留在 outbox、信封表只存元数据无载荷列）③`scripts/e2e_w8.py` → **104/104 / SKIP 0 EXIT 0** ④`python scripts/check_docs.py` → **PASS 15/15 EXIT 0**（C2 = 14 份 ADR、C13 = 34 项、C14 = 20 条迁移、C15 权威口径 = 468）⑤**MQ 开启态活体联调**（`mvn -B -o -pl infrastructure -am test -Dtest=MqEnvelopeLiveTest -Drf.mq.live=true`）→ **1/1 PASS（14.8s）**：真实 RocketMQ 5.3.1 broker 真发真收 + **独立消费组回读 broker 原始消息体断言无明文 PII** + 重复投递幂等（broker 不在则直接失败，禁止静默跳过）⑥DB 实查 → `flyway_schema_history` **20 条全部 success**（V20 = mq envelope channel）、四 schema **62 张表**。**合入方式**：`git merge --squash wt/mq`（64 文件自动合并 + **16 冲突全手工解**，master 为底只挑增量，保持线性历史单父提交）。**过程记录（三处与 MQ 无关的真实缺陷，均已修）**：①`run-microservices.sh` 被检出成 CRLF → WSL bash 报 `set: -\r: invalid option` → 新增 `.gitattributes`（`*.sh text eol=lf`）固化；②`auth-service` 启动报 `ConflictingBeanDefinitionException: bean name 'authController'` → 根因 `AuthController.java` 声明 `package com.company.retail.auth.controller` 却放在 `auth/` 根目录，**VSCode JDT（按目录输出）与 Maven（按声明包输出）产出两个同名 Bean** → 包声明对齐到 `com.company.retail.auth`（全仓 466 个 Java 文件包/路径一致性全扫：仅此 1 处不一致），jar 内已确认只剩一份 `AuthController`；③`retail-core` + `servicing-service` 两处调度测试把业务日**硬编码为 `2026-11-23`** 去断言生产代码的 `LocalDate.now()` → **跨零点必挂**（既有测试缺陷，非本轮回归）→ 改为执行前后区间断言。**已执行迁移 V1–V19 字节全部未动**；MQ 端口（namesrv 9876 / broker 10911）与 `retail.mq.*` 配置键已登记 `PROGRESS §2` |

| 2026-11-24 | **W-LOCK 分布式锁合入 master**（用户指令「提交吧」） | **PASS**：①`mvn -B package` → **18 模块 BUILD SUCCESS**（surefire 类级汇总 **484 测试 / 70 个测试类 / 0 失败 0 错误 0 跳过**；= master 的 468 + 本轮锁单测 16：`DistributedLockManagerTest` 11 + `ApplicationIntakeLockTest` 5）②`python scripts/check_docs.py` → **PASS 15/15 EXIT 0**（C2 = 15 份 ADR、C13 = 36 项、C14 = 20 条迁移、C15 权威口径 = 484）③**锁专项应用级实测 20/20 PASS**（`scripts/smoke_w_lock.py`；**合并后的代码** + **全新隔离库 `retail_finance_lock`**（Flyway **V1–V20 干净应用**，20 条全 success）+ 真实 Redis `retail-finance-lock-redis`:6381；备用端口段 19xxx）：并发 5 条同客户同车只受理 1 条、其余 `RF_APP_001`、库内 1 行事件 1 条；同幂等键并发重放返回同一申请号；Redis 不可达 → 503 `RF_LOCK_002` 且未落库、恢复后可写；重复回执 + DMZ 换 nonce 重推均幂等、库内 1 行事件 1 条。④**合并协调**：master 期间再推 3 个提交（W-MERGE 回写 / Windows 行尾纪律固化 / W-MQ 合入 V20+ADR-RF-014），本分支**先并入 master 再逐项解冲突**（**15 个冲突文件，两侧改动均保留**）；**ADR 编号让位到 ADR-RF-015**（012 缓存/限流、013 回调幂等、014 MQ），`ADR-RF-013` 加**修订注**（原裁决原文保留，仅结论 4 按用户指令修订，结论 1–3/5–6 继续有效）；**R10 断言组顺延为 J 组 A39–A42**（master 的 I 组已占 A37–A38），`scripts/smoke_w_lock.py` 同步改号；**V19 回调幂等唯一索引与 master 字节一致 → 合并零冲突**，号段仍 V1–V20 连续，**已执行迁移字节未动**。⑤**未复跑**：走网关冒烟 33/33 与 E2E 104/104 为 master 的实测记录（原记录），本轮因注册端口段 8090–8099/18081/18091 被并行会话占用未复跑；**默认库 `retail_finance` 已由 master 重建并应用 20 条迁移**，但本轮锁专项在隔离库完成（**隔离库跑绿 ≠ 默认库验收**，默认库回跑待端口释放后补） |
| 2026-11-23 | **W-LOCK 分布式锁实施与验证**（用户指示「在新的 worktree 里处理 Redisson 分布式锁防重复进件 / 回调幂等」+ 裁定 fail-closed） | **PASS**：`mvn -B package` → 17 模块 BUILD SUCCESS（surefire 类级汇总 **418 测试 0 失败 0 错误**，58 个测试类；新增锁单测 20 例：`DistributedLockManagerTest` 11 + `ApplicationIntakeLockTest` 5 + `RegistryReceiptIdempotencyTest` 4）；**真实 Redis + 真实 PG 应用级实测 20/20 全绿**（`scripts/smoke_w_lock.py`，走网关 19181 + DMZ 19191，**备用端口段**——注册段 8090–8099/18081/18091 被并行会话的栈占用）：①**并发 5 条同客户同车进件 → 只受理 1 条**，其余 4 条 `RF_APP_001`，库内该客户该车申请行数 = 1、`APPLICATION_SUBMITTED` 事件数 = 1；②**同幂等键并发 → 后到者 `replayed=true` 返回同一申请号**，库内 1 行；③**Redis 宕机 → 503 `RF_LOCK_002` fail-closed 拒写且未落库**，Redis 恢复后写路径恢复（200）；④**同一业务回执重复登记 + DMZ 换 nonce 重推 → 幂等返回**，库内回执行数 = 1、`TITLE_RELEASE_REGISTERED` 事件数 = 1；**Flyway V1–V19 干净应用**（19 条全部 success，含 V19 `uk_receipt_order_authority_ref`）。**实测暴露并修复 1 个真实缺陷**：Redis 宕机时 Redisson 抛的是包装异常 `RedisException("Unexpected exception while processing command")` 而非 `RedisConnectionException`，原实现漏判 → 落成 500 `SYS_001`；已改为按异常链判定 Redis 类故障（`isRedisFailure`），补 2 例单测（包装异常 → 503；非 Redis 异常原样抛出），并加固释放路径（释放失败只告警，不把已成功的写翻成 500）。**未改已执行迁移字节**（V1–V18 不动），新迁移落 **V19**（V18 已被 master 的产品档位迁移占用并执行）；**走网关冒烟 33/33 与 E2E 104/104 为 master 的实测记录（原记录），本轮因注册端口段被并行会话占用未复跑**；**默认库 `retail_finance` 本轮未回跑**——并行会话的 `wt/mq` 已把 **V20（mq envelope channel）** out-of-order 应用进默认库（V19 缺失），Flyway 校验直接拒绝启动，故本轮应用级实测在**隔离库 `retail_finance_lock`** 完成（隔离库跑绿 ≠ 默认库验收，默认库回跑待号段状态理顺后补） |
| 2026-11-24 | **游标表名缺陷订正（W-MB 轮遗留回归的据实订正；随 W-MQ 收口一并回灌 master）** | **PASS**：根因——W-MB 持久层改造轮（`a8354a7`）把 `OutboxMapper` 三处**游标表名写成不存在的 `core.event_offsets`**（V1 建的表实为 `core.consumer_offsets`）→ `insertOffsetIfAbsent` 抛 `BadSqlGrammarException` → `RfEventDispatcher.poll()` 逐 consumer catch → **事件消费整条静默停摆**（全部事件滞留 `PENDING`、征信完成事件不再推进审查状态机 → 前端页面冒烟级联失败 8 项）。**该故障只在日志留一行 WARN**（`事件消费轮询异常 … relation "core.event_offsets" does not exist`），极易被误判为「环境数据脏 / outbox 积压」——本轮即先误判一轮。修法——三处 SQL 对齐 master 写法（表名 `core.consumer_offsets` + `writeOffset` 补 `updated_at = now()`），与 **W-MB 改造前的 JdbcTemplate 版逐字一致**（B-12：持久层改造不改语义），且与 master 侧逐字相同（合并零冲突）。**验证**（W-MQ 分支收口轮实测，当时口径）：修后游标实测推进（`underwriting-service` 935 → **954**，SENT 事件 197 → 199，积压开始消化）；`mvn -B package` → BUILD SUCCESS；走网关冒烟 **26/26** + 前端页面 **46/46** + **MQ 通道 11/11 EXIT 0**（前端 46/46 是修复前后的关键分水岭）；`scripts/e2e_w8.py` → **98/98 EXIT 0**；`check_docs.py` → **PASS 15/15 EXIT 0**。另加一道自查：脚本比对**全部 Mapper / Repository 引用的表名与数据库实有表清单**，确认除本处外无同类错名（`core.v_recon_inv` 与 `set` 为正则截断误报）。**未改任何已执行迁移字节** |

> 本轮唯一可跑的验收是 `check_docs.py`。在 S1/S2 之前，任何「构建全绿」「12 个服务已联通」的表述都是假的。

---

## 7. 已知「假未完成 / 假完成」警示

1. `R02` 的服务边界定义**已由 W2 落地并经网关冒烟验证**；但「路由可达」≠「业务能力完整」，
   能力完备性以 `scripts/smoke_w2.py`（33 断言）、`scripts/smoke_mq.py`（11 断言，MQ 关闭态零回归）、
   `scripts/smoke_w_lock.py`（锁专项 20 断言）与 `scripts/e2e_w8.py`（104 断言）为准，新增能力必须补断言；MQ **开启态**另由 `MqEnvelopeLiveTest`
   （真实 broker 活体联调）承担，单测与关闭态冒烟**都不等于** MQ 链路已通。
2. `R06` 的表清单是**字段级规格**；实际 DDL 以 `infrastructure/` 下已执行的 Flyway 迁移为准
   （V1 起共 20 条迁移、62 张表；V19 只建唯一索引不建表，V20 新增 1 张信封表）。改表一律走新迁移，不许改已执行迁移的字节。
3. `R10` 的断言清单是**验收设计**；实际通过情况以构建测试数（当前 504，见顶部校正注）+ 冒烟 + E2E 实测记录（§6）为准。
4. `README §4` 的文档编号齐全 ≠ 内容已实现；各文档「交付性质」见各自 §0 文档控制。
5. 与 floorplan 的对应关系是设计对齐（同构微服务），代码层面通过中台 jar 复用
   （`../company-platform-contracts`，W1 已抽取并 `mvn install`，34 单测全绿），其余不直接复用。
6. **12 个服务都起了但彼此零调用 ≠ 微服务已落地**：必须用 Feign 门禁 + outbox + 走网关冒烟证明链路（A-16）——
   此判据已由 W2–W9 的冒烟与 E2E 满足，后续新增服务/链路仍按同判据验收。
7. **本地全绿 ≠ DB role 隔离已生效**：本地 11 个连库单元（网关不连库）统一以 `postgres` 超级用户连库，超级用户绕过全部 ACL，
   `retail_app_rw` / `retail_pii_rw` / `retail_dmz_rw` / `retail_audit_ins` 的授权位**在本地不被服务连接池行使**。
   `R10 A23`（越权直查被拒）**本地可执行**——用角色口令直连容器即可实测（V17 轮已实测通过），
   但**服务自身连接池**仍是超管，故**端到端的越权拦截**仍须**按角色分配连接账号**后才兑现
   （含迁移账号与运行账号分离）。引用 V16/V17 的权限位结论时，只能表述为**授权位配置正确**，
   不能表述为**越权拦截已验证**（`R06 §3.1`）；投产门禁见 `R06 §3.2`（G-DB-1~G-DB-7，fail-closed）。
8. **编排脚本（`run-microservices.sh`）的 PID 记账与就绪上限有缺陷**（2026-11-23 收口提交时实测暴露）：
   `start_one` 记录的 `$!` 与**实际 JVM 进程号不一致** → `do_stop` 的 `kill` 打空 → **孤儿 JVM 继续占端口**，
>   下一轮 `start` 直接报 `Port 8090 already in use`（本轮连踩 2 次）；`wait_one` 的 **90s 上限在满负载下不够**（网关实测需 **105s** 才就绪）。
>   **规避方式**：① 跑验证前先按端口清理残留（`ss -tlnp` / `fuser` 取 PID 后 `kill -9`）；② 就绪判据用**真实 HTTP 探活**（网关 `/api/health` 返回 200），
>   **不要用日志关键字**（被孤儿进程写过的日志会出现稀疏空洞，`grep` 会命中上一轮残留字节而误判）；③ 起栈前先删日志。
>   **修复建议（本轮未改，脚本属共享禁区）**：① `start_one` 记录真实 JVM PID，或 `do_stop` 改为按端口清理；② `wait_one` 上限提到 180s。
9. **Windows 侧绑不上 RF 注册端口段 ≠ 有别的进程在占**：2026-11-23 实测——`netstat -ano`（含 `-a` 全状态）对 8090–8099/18081/18082/18091 **无任何记录**，但 `bind()` 一律返回 WSAEADDRINUSE；同机 8080–8089、18078–18080、18083–18090 实测可绑。根因：`.wslconfig` 的 `networkingMode=mirrored` 下 **HNS 为 WSL 端点保留的端口分配**（`netsh int ipv4 show excludedportrange` 查不到，须管理员权限查 HNS；而 WSL 侧可正常 bind 同端口）。
   **规避**：注册端口段验收放到 **WSL 侧**执行（mirrored 共享同一网络命名空间，所绑即真实注册端口，Windows 侧可经 127.0.0.1 访问）。
   **根治**：`wsl --shutdown` 清 HNS 端口分配——但会连带停掉跑在 WSL 内的 Docker（须 `docker start retail-finance-db` 复原），属环境操作，本轮**未做**。
10. **E2E S11「outbox 发出 TITLE_RETENTION_REGISTERED」断言对时序敏感**：`LOAN_SETTLED` 消费会先按挡板补一条 `vin=UNKNOWN-<loanId>` 的保留登记行（`ensureRetentionForSettledLoan`；旧 `LoanSettledConsumer` 同款语义，**改动前既有**）；若该兜底行先于操作员显式登记落库，显式登记命中「已存在 → 幂等返回」分支（**不重复发布登记事件**，`R04 §6` 语义）→ 该断言失败，但业务状态仍为 REGISTERED、S11 其余断言全绿。实测：outbox 有积压导致消费者抢跑时出现（PASS 96 / FAIL 2），清空积压后复跑 98/98。**这是既有语义、不是回归**。**处置（2026-11-23 用户裁决「补发事件并订正VIN」）**：正式登记命中挡板占位行 → **订正 VIN + 补发一次**登记事件（乐观锁 + 占位 VIN 条件 → 并发/重放只订正一次、只补发一次）；登记订正**只支持同态订正**（C-04 裁决口径 REGISTERED），跨态一律 `RF_VALID_002` 拒绝、**不改状态**（INV-3 唯一入口不可旁路）。口径已同步 `R04 §6` / `R09 §5.1`，验收断言补 `R10` A31–A32；`scripts/e2e_w8.py` 属共享禁区，未改。
11. **Redis 只缓存只读配置，不缓存业务事实**：`TwoLevelCache` 的用途被 `ADR-RF-012` §2 与 `AGENTS.md` B-13 **锁死为两类**——① 只读业务配置（产品档位等）的 L2 缓存；② 网关限流状态。
    **债权余额、核销流水、个人信息、征信结果、审查结果一律不得进缓存**（缓存数据会被读到过期、会被复制进 Redis 落盘、事后也难撤回）。
    另有三条边界：**改配置不得改历史计划**（计划侧落快照三件套）；**Redis 锁不作正确性来源**（正确性仍由唯一键 + 乐观锁 + 本地事务保证）；
    **Redis 不可用时缓存降级直读、限流 fail-open**（可用性优先；代价是 Redis 故障期间超限请求会放行，属已知取舍，生产可切 fail-closed）。
12. **共享验证库会被并行路线的迁移污染**（2026-11-23 W-MERGE 实测）：`wt/mq` 的 `V20__mq_envelope_channel.sql`
    在 22:22 被执行进了**共享**的 `retail_finance` 库（`flyway_schema_history` installed_rank 19、success=t），
    而该路线的代码按 §9.2 裁决**不合入 master** → 磁盘上是 V1–V19，库里却是 V1–V18 + V20（**缺 V19、多 V20**），
    Flyway 校验直接失败：`Detected resolved migration not applied to database: 19`，**retail-core 起不来**。
    **处置**：重建数据库容器（`docker rm -f retail-finance-db`，随后由 `run-microservices.sh` 重建），
    让 19 条迁移从零执行——**不用 `outOfOrder=true` 绕过**（那会让 master 号段错位、且把不合路线的迁移永久留在库里）。
    **纪律澄清**：「已执行迁移字节不改」只适用于 **master 自己的**迁移；
    **不合入路线的迁移进共享库属于污染，必须清理**，不能因「已执行过」就留在库里。
    **教训**：并行路线共用同一验证库时，其迁移必须与 master 号段隔离；
    裁决落地时若发现库里已有不合路线的迁移，先清库再验收。
13. **多会话同时起栈会互撞端口，编排脚本的占用检查在 WSL 下失效**（2026-11-23 W-MERGE 实测，本条与 §7 第 8 条同源）：
    `run-microservices.sh` 的 `port_in_use()` 用 **Windows 版 `netstat -ano`** → 在 WSL 侧恒为空，
    「端口已被占用」的拦截**永不生效**，起栈失败只剩 `Port 8090 already in use` 这类事后症状；
    且多个会话共用 `.microservice-pids/` 目录会**互删 pid 文件** → `do_stop` 漏停 → **孤儿 JVM 继续占端口**，
    下一轮起栈再撞。本轮三次起栈失败（8090 ×2、18081 ×1）全部由此而来，**非代码缺陷**，串行化后一次跑通。
    **规避**：① 同一工作树**只允许一路起栈**；验收前后按端口（`ss -ltnp` / `fuser`）清理孤儿 JVM。
    ② 编排脚本属共享禁区，**本轮未改**；修复建议 = `port_in_use()` 改用 WSL 可用的 `ss` / `lsof`，
    或 `do_stop` 改为按端口清理，且 pid 目录按会话隔离。
14. **worktree 磁盘行尾与仓库 blob 行尾可能不一致 → diff 出现上万行噪声（2026-11-23 W-LOCK 收口实测）**：
    本机 `git config --show-origin` 显示 **全局 `C:/Program Files/Git/etc/gitconfig` 的 `core.autocrlf=true`**，
    而**仓库所有 blob 一律是 LF**（`git grep -P '
' master/HEAD` 仅命中 3 个 PNG 二进制，无任何文本命中）。
    linked worktree 检出时按生效值写盘 → **磁盘整树被写成 CRLF**（实测 543 个受控文件中 **536 个是 CRLF**），
    表现为 `git diff` 出现**上万行整文件增删噪声**（如 `ReleaseOrderRepository.java` 164/164、`R09` 280/280），
    极易被误判成「本轮大规模重写了这些文件」。**辨别方法**：`git diff --numstat` 与 `git diff --ignore-cr-at-eol --numstat` 对比，
    **两者完全一致即纯行尾差异**（本轮归一前：前者 12k 行、后者仅数百行）。**处置**：按字节把 CRLF 还原为 LF
    （不改内容、不动编码、不碰二进制），归一后两者完全一致。**提交前必查这一项**，否则会把 12k 行噪声 diff 提交进去；
    附带收益：`run-microservices.sh` 等 **shell 脚本在 CRLF 下 bash 会报错**，LF 归一同时消除该隐患
    （`run-microservices.sh` 归一后 diff 由 310/281 收敛为 38/9）。**未改仓库配置**——`core.autocrlf` 属共享环境配置，
    其他并行会话在用，不擅自改（`AGENTS.md` §7）。**master 已于 2026-11-23 固化 `.gitattributes` 行尾纪律**（`AGENTS.md` §4.1 + 反模式 A-21），本条是该纪律在本 worktree 的补充实证。
15. **注册端口段被并行会话占用 → W-LOCK 轮全栈验收走备用端口段**：Windows 与 WSL 共享网络命名空间（mirrored），并行会话的栈占着 8090–8099 / 18081 / 18091，该轮实测在 **19xxx 备用段**（core 19090 / auth 19091 / customer 19092 / application 19093 / title-release 19097 / DMZ 19191 / 网关 19181）起「锁相关最小栈」，启动器 `logs/launch_lock_stack.py`（在 gitignore 的 `logs/` 下，不入库）。**注册端口段的标准走网关冒烟 33/33 与 E2E 104/104 该轮未复跑**（端口被占，非代码回归），待端口释放后补。
16. **「轮询 catch 住异常」会把整条链路变成静默停摆**：调度线程的异常若被兜底成一行 WARN，
    表面看是「服务正常、事件积压」，实际是**消费端一次都没跑过**（W-MQ 实测：游标表名写错 →
    `insertOffsetIfAbsent` 每轮抛 `BadSqlGrammarException` → 逐 consumer catch → 全部事件滞留 `PENDING`、
    状态机不推进）。**判别方法**：`core.consumer_offsets` 有没有行 / 游标是否在推进——
    游标不动 = 停摆，而不是「积压」。**排查顺序**：先看该服务日志里的 `事件消费轮询异常`，
    再看 `core.consumer_offsets` 的 `last_event_id` 是否推进；**别先怀疑数据脏**。
    另：SQL 里的**表名写错不会编译报错、不会启动失败**，只会在第一次执行时炸——
    持久层改造轮（W-MB）就栽在这一处（见 §6「游标表名缺陷订正」行）。
    自查手法：脚本比对**全部 Mapper / Repository 引用的表名与数据库实有表清单**。


---

## 8. 工期与运维投入评估（2026-11-23 · 评估非承诺）

> `R11 §1` 声明不写工期估算（属路线图纪律）；本节是 PROGRESS 对**投入量级**的评估记录，
> 供排期参考，不构成承诺。依据为 2026-11-23 终态实测：330 个 Java 文件 / 约 2.6 万行、
> 17 模块 12 部署单元、62 表 / 20 迁移、504 单测、约 3.1k 行验证脚本、约 5k 行设计文档。

### 8.1 口径假设

- 5 人 = 1 技术负责人（兼架构）+ 3 后端 + 1 前端/全栈，均为熟手；QA 与 DevOps 由团队兼任。
- 目标分两档：①达到本仓当前状态（全链路对模拟器验证通过）；②真实生产上线。

### 8.2 分阶段估算

| 阶段 | 内容 | 工期 |
|---|---|---|
| P0 设计定源 | 领域发现（12 限界上下文）、法务/合规裁决、架构与 ADR、库设计、外部协议定稿 | 4–5 周 |
| P1 平台底座 | 契约模块、网关+DMZ（验签/防重放/白名单）、双主体 JWT、outbox、CI、Flyway 骨架 | 3–4 周 |
| P2 业务服务 | 10 个服务（进件→征信→授信→合同→放款→账务→贷后→解约登记→对账），状态机+合规门禁+单测 | 10–14 周 |
| P3 前端 | 运营控制台+客户自助页（与 P2 重叠推进） | 3–4 周 |
| P4 外部对接 | CIC/银行/eKYC/登记机关/通知渠道调研、签约、联调 | **8–16 周（日历时间为主）** |
| P5 验收上线 | 全链路 E2E、安全审计、性能/日批压测、监控告警、合规检查 | 4–6 周 |

**结论**：

- 达到本仓当前状态（对模拟器全通）：约 **4.5–6 个月**（纯人力工作，5 人可充分并行）。
- 真实生产上线：约 **7–9 个月**。多出的 2–3 个月主要耗在外部机构日历时间
  （CIC 加入审查 / 银行代付开户 / eKYC 商谈），**加人不可压缩**；P4 必须与 P1/P2 并行启动，不能等系统做完再开始。
- 人力总量约 **800–1000 人日**（5 人 × 8 个月 ≈ 840 人日，口径互洽）；若压缩到 6 个月上线，
  需砍范围（先做 6 个核心服务，登记/对账后置）或提前并行 P4。
- 若从本仓基线直接开工（设计文档 + 字段级协议 v1 已定稿），P0 可缩至 2 周以内，总工期相应提前约 1 个月。

### 8.3 运维投入（进入生产后的长期成本）

| 层面 | 投入 |
|---|---|
| 基线仓库维护（演示态） | 约 1 人 × 20–30%：依赖升级与 CVE 补丁、文档同步（R00–R11/ADR 随演进回填）、待办清单消化 |
| 生产期长期负载 | 外部对接变更（每年数次，协议 v1→v2 适配+回归）、贷后异常处理（对账 UNMATCHED / 报送 DEAD 补发 / 放款失败人工介入）、监管应对（总量规制口径调整、检查资料输出）、安全审计与密钥轮换（三把 JWT secret）、日批窗口监控与 62 表索引/附件清理 |
| 合理配置 | 约 **2 人长期 + 1 人按需**，约为开发团队 40% 的持续人力；低于该比例通常意味着贷后异常无人值守，风险在监管检查或对账事故中兑现 |

---

## 9. 并行路线合并裁决清单（W-MERGE · 2026-11-23 · 已执行）

> **状态**：**已合并并完成全量验收（2026-11-23 23:20 合并收口 · 23:30 验收收口）**。四条路线会话全部收口并把落地成果写入 master 工作区，
> 23:18 复核：4 条分支均已停止写入（7–22 分钟无改动），会话状态 idle → 判定为**已收口**，进入合并执行。
> **⚠️ 后续变更（2026-11-23 当日更晚）**：用户裁决「**改口径合并 wt/mq**」，本节 `wt/mq` 行「不合 MQ 本身」的裁决**已被推翻**——
> MQ 通道按 **`ADR-RF-014`** 合入 master（默认关闭、只作削峰缓冲层），详见 §0 轮次、§2 变更记录与 §6 的 W-MQ 验证行。
> **本节保留裁决原文作为合并依据**；实际执行结果与实测数据见 **§6 验证记录（W-MERGE 行）**——
> 代码合并提交 `cf78529`、收口回灌提交 `be856e6`，验收判据 = `mvn -B -o package`（当前 504 测试 0 失败）·
> 走网关冒烟 33/33 · 前端页面 46/46 · `scripts/e2e_w8.py` 104/104 · `scripts/check_docs.py` PASS 15/15 EXIT 0 ·
> Flyway V1–V20 共 20 条全部 success、四 schema 62 表。
>
> **补充（2026-11-24，用户「提交吧」）**：`wt/lock` 已按用户指令**合入 master**——本节原裁决「`wt/lock` 只取回调幂等、Redisson 不合」
> 被用户指令覆盖：Redisson 分布式锁（`ADR-RF-015`）与 `common/lock` 机制件**一并合入**，`ADR-RF-013` 的「不引入分布式锁」结论
> 同步加**修订注**（唯一索引仍是正确性来源、锁只收敛并发窗口）。合并提交与实测数据见 §6 验证记录（W-LOCK 合入 master 行）。

### 9.1 四条路线的归属与判活

| 分支 | worktree | 归属会话（peer，非本会话子会话） | 提交 | 判活 |
|---|---|---|---|---|
| `wt/lock` | `../retail-finance-lock` | 「在 worktree 中用 Redisson 锁实现进件防重与回调幂等」 | **4**（`1b97244` + `5dff59f` + `a788505` + 合入 master `7663bd4`） | ✅ 已收口并**合入 master**（2026-11-24 用户「提交吧」） |
| `wt/mq` | `../retail-finance-mq` | 「在worktree实现 Kafka/RocketMQ 削峰解密」 | **1**（`a3e9057`） | ✅ 已收口（**不合并**，违 B-10） |
| `wt/ratelimit` | `../retail-finance-ratelimit` | 「实现 Nginx 网关令牌桶限流」 | **4**（`ec9fcf3` + `0ec3fc1` + `b7a3852` + `8d1a6de`） | ✅ 已收口 → **2026-11-24 已整支合入 master**（用户改口径：两套限流实现 + 一个开关，不再「只取增量」） |
| `wt/statemachine` | `../retail-finance-statemachine` | 「在 worktree 中处理 Spring StateMachine 引擎」 | **2**（`b815d3e` + `a5c7fbb`） | ✅ 已收口（**已合并**） |

分支创建于 2026-11-23 21:29–21:41（基线 `1422b55`），master 随后提交 `3c46876`（W-CACHE/W-RL）。
**用户裁决（2026-11-23）**：①**等各会话自己跑完并提交后，再逐条评估合并**（不打断在跑的会话、不合半成品）；
②**冲突处以 master 现有口径为准，只并入不冲突的部分**。

### 9.2 逐条裁决（合并已按此执行，结果见 §6 验收记录）

| 路线 | 冲突点 | 裁决 | 处置动作 |
|---|---|---|---|
| `wt/ratelimit` | 6 个类**同名同路径**（`gateway/.../ratelimit/*`）；限流键口径不同：分支用**客户端 IP**，master 用 **Authorization 头 SHA-256 哈希** | **保留 master 实现**（哈希键按调用方限流，IP 键在 NAT/代理下会把不同调用方挤在一起；且 master 版本已过 33/33 冒烟 + 104/104 E2E） | ①master 6 个类**整体保留**，分支版本不并入；②**吸收**分支的 `RateLimitFilterTest`（master 缺过滤器级测试，需按 master 实现改写后并入）；③`nginx/` 目录为**可选部署形态**，不默认启用，可作为独立目录并入 |
| `wt/lock` | ①ADR 编号与 master 同号；②回调幂等迁移与 master 已执行的 V18 同号；③引 Redisson 与 master「Redis 仅两类用途（只读配置缓存 + 限流态）」的用途白名单冲突 | **原裁决（2026-11-23，保留原文）**：**拆开裁决**——**回调幂等能力合入**，**Redisson 不合**。<br>**2026-11-24 用户指令修订**：用户指示「在新的 worktree 里处理 Redisson 分布式锁防重复进件 / 回调幂等」并裁定 **fail-closed**，其后于 2026-11-24 指示「提交吧」→ **按用户指令采纳 Redisson 分布式锁**（用户指令晚于本裁决，优先） | ①**回调幂等唯一索引** `uk_receipt_order_authority_ref`（**V19**）已在 master 落地（`ADR-RF-013`），写入走 `ON CONFLICT DO NOTHING`；②**新增** `RedissonLockManager` + Redisson 依赖 + `common/lock` 机制件（`ADR-RF-015`，编号顺延至 015），**锁只收敛并发窗口，正确性仍由唯一索引 + 乐观锁保证**；③`DistributedLockManager` 端口 + `InProcessLockManager`（单测用单机实现）与 master 的到期批量抢占锁**不重复**（后者是作业抢占，不是业务写路径互斥）；④**fail-closed** 语义 = Redis 故障期间受保护写路径拒写（`RF_LOCK_002`/503），可用性代价已向用户明示并经用户确认 |
| `wt/mq` | 引 MQ **直接违反 `AGENTS.md` B-10**；改写 `ADR-RF-008` §4（master 口径仍是「不引 MQ」）；改 `AGENTS.md` / `scripts/check_docs.py` 等共享禁区文件（45/7 行） | **不合 MQ 本身** | ①MQ 客户端、`common/messaging`、`*Envelope*`、密文信封投递**全部不合**（与 master 的本地附件存储 ADR-RF-008 冲突）；②`EventConsumptionService` 与 master 既有 `core.event_consumptions` + outbox 消费幂等**重复**，不合；③**不得**用该分支的 `AGENTS.md` / `check_docs.py` 覆盖 master 本轮改动 |
| `wt/statemachine` | 新增独立 Maven 模块 `statemachine/`；改 `LoanStatus` / `ReleaseOrderStatus` / `TitleRetentionStatus` 与 retail-core 三个 TxExecutor | **可合（架构不冲突）** | ①该路线把 **Spring StateMachine 引擎与手写领域状态机解耦**（引擎置于独立模块 `com.company.retail.statemachine`，`domain` 包仍零 Spring 依赖、仍可纯 JUnit 跑），不违反 `AGENTS §2` 分层纪律 → **具备并入条件**；②但它改了 `retail-core` 三个 TxExecutor 与三个状态枚举，与 master 本轮的 `RepaymentPlanApplicationService` 改动同区域，需**逐文件解冲突**；③合入后必须重跑全量构建 + 冒烟 + E2E |

### 9.2.1 合并冲突实测（2026-11-23 22:38 · `git merge-tree --write-tree` 只读预演，未触碰工作区）

| 分支 | 提交 | 冲突文件数 | 冲突文件 | 自动合并成功（无冲突） |
|---|---|---|---|---|
| `wt/lock` | 1（`1b97244`） | **13** | `AGENTS.md` · `PROGRESS.md` · `README.md` · `common/pom.xml` · `docs/00-文档索引.md` · `docs/R00` · `docs/R10` · `domain/RFErrorCode.java` · `interview/`×3 · `run-microservices.sh` | **`V19__callback_receipt_idempotency.sql` · 改号后的 Redisson ADR（013，文件在分支侧） · `common/lock/*` · application/title-release/external-api 的 Java 改动 · `pom.xml` · `docs/R06` 均无冲突** |
| `wt/ratelimit` | 2（`ec9fcf3` + `0ec3fc1`） | **13** | `PROGRESS.md` · `README.md` · `docs/00` · `docs/R00` · `docs/R02` · `docs/R07` · `docs/R10` · `gateway/pom.xml` · **`gateway/.../RateLimitFilter.java`（add/add）** · **`gateway/.../RateLimitProperties.java`（add/add）** · `interview/`×3 | `nginx/`·`scripts/smoke_ratelimit.py`·其余 10 个限流类均无冲突 |

**两条由此确认的事实**：

1. `wt/lock` **已按裁决改号**——迁移从 V18 改为 **`V19__callback_receipt_idempotency.sql`**，ADR 从 012 改为**下一个可用号（013）**（该 ADR 文件目前只在分支侧，落盘后才进入 master 的 `docs/adr/`，故本节不写全称以免 `check_docs.py` C4 判越界），且新增文件与 master **零冲突**；
   冲突全部落在共享文档与共享配置，**可按「以 master 为底、只挑增量」手工解**。
2. `wt/ratelimit` 为规避撞名**重命名了大部分类**（`RateLimitConfiguration` / `RateLimitPolicy` / `RateLimitPolicyResolver` / `RateLimitRegistry` / `RateLimitRule` / `RateLimitDecision` / `ClientIpResolver` / `TokenBucket`），
   但 **`RateLimitFilter.java` 与 `RateLimitProperties.java` 仍与 master 同名 → add/add 冲突**。
   因此该分支**不可整体 merge**：master 这两个文件必须保留分支外的版本（分支版实现与 master 版口径不同）。
   按 §9.2 裁决，该分支只吸收：`nginx/`（可选部署形态）· `scripts/smoke_ratelimit.py`（独立冒烟脚本）· `ClientIpResolver` 的**可信代理 IP 提取口径**（master 键解析无头退路目前只取 `getRemoteAddress()`，可作增量）。

> ⚠️ **两套限流实现并存的后果**（须在合并时避免）：分支重命名后的 12 个类与 master 的 6 个类**类名不冲突但语义重叠**，
> 且都用 `gateway/.../ratelimit/` 同一包 → 若整体合入，网关将出现**两套限流配置键与两套过滤器**。
> 裁决不变：**master 实现保留，分支实现不合入**，只取上述三项增量。

### 9.3 合并执行前置条件（缺一不可）

> **执行结果（2026-11-23）**：以下 5 项前置条件**全部满足**——第 0 项的 WIP 已由其所有者会话自行提交（`483f6a7`），
> 工作区恢复干净（**未用 `stash` / `checkout` 绕过**）；第 1 项四条分支各自提交并收口；
> 第 2 项号段与编号让位已定（见下条第 2 条的订正）；第 3 项共享禁区文件已逐个手工合（**以 master 为底、只挑增量**，未整文件覆盖）；
> 第 4 项全量验收已跑通（§6 W-MERGE 行）；第 5 项合并顺序按 `wt/lock` → `wt/statemachine` → `wt/ratelimit` → `wt/mq` 执行。
> 以下条目**保留原文**作为当时的执行依据，冲突处以本节执行结果与 §6 为准。

0. **🔴 当时的阻塞（已解除）**：master 主工作区被他人未提交 WIP 占用（`docs/R11-演进路线图与开工门禁.md` +128 行生产就绪审计、
   `external-api/src/main/resources/application.yml` 2 行号段注释）——`git merge` 会因「本地修改将被覆盖」被拒。
   **处置**：该 WIP 由其所有者会话自行提交后，master 工作区恢复干净，合并方可进行；
   **不得**用 `stash` / `checkout` 绕过（= 动或覆盖他人 WIP，违反 `AGENTS.md §7`）。
1. **各会话自行提交**：分支出现提交后（`git log master..<branch>` 非空）才进入评估，
   **不得**代提交他人未完成的工作区状态（`AGENTS.md §7`）。
   **附加判据**：分支**提交后仍持续写入**（文件 mtime 持续变化）时视为**中间态**，此时合入的是半成品——
   2026-11-23 22:38 实测：4 条分支全部仍在写入（`wt/lock` 提交后 1 分钟仍在改 `RedissonLockManager` 与其测试），
   故**该轮**只做冲突量化与裁决回写、**不执行实际 merge**——**其后 23:20 四条分支收口，已按本节裁决实际执行合并**（结果见本节执行结果与 §6 验收行）。
2. **号段与编号让位**：ADR 编号 `012` 归 master；`wt/lock` 的 ADR 走 `013`（`wt/mq` 若不合则 `013` 让出）
   ——**已落地**：`docs/adr/ADR-RF-013-回调幂等唯一索引与不引入分布式锁.md`（`wt/mq` 不合，013 未让出）。
   迁移号：master `V18` **已执行**（18/18 success，字节不可改），`wt/lock` 的回调幂等索引走 **`V19`**；
   `wt/mq` 的 `V20__mq_envelope_channel.sql` 若不合 MQ 则**整条弃用**。
   ✅ **V19 已定（2026-11-23 实测落地）**：**V19 = `wt/lock` 的回调幂等唯一索引** `uk_receipt_order_authority_ref`
   （`infrastructure/src/main/resources/db/migration/V19__callback_receipt_idempotency.sql`，实测 19 条全 success、该索引已在库中存在）；
   `R06 §3.2` 的 **G-DB-7 审计写入授权调整顺延 V20**（`R06 §6` 与 `PROGRESS §2` 号段占用表均已登记）。
3. **共享禁区文件逐个手工合**：父 `pom.xml` / `common/pom.xml` / `run-microservices.sh` / `scripts/check_docs.py` / `AGENTS.md` / `docs/00-文档索引.md`
   ——**以 master 版本为底**，只挑拣需要的增量，不整文件覆盖。
4. **全量验收**：`mvn -B clean package`（0 失败）· `run-microservices.sh smoke` · `scripts/e2e_w8.py` · `python scripts/check_docs.py`；
   **C2（ADR 编号连续唯一）与 C14（迁移号段 == 磁盘脚本数）必须仍 PASS**。
5. **合并顺序建议**：`wt/lock`（回调幂等，V19）→ `wt/statemachine`（独立模块，冲突最少）→ `wt/ratelimit`（仅取测试与 `nginx/`）→ `wt/mq`（预期不合，仅留 ADR 留痕）。

### 9.4 合并后的 master 状态（2026-11-24 00:5x 复核）

> **后续取代（2026-11-24）**：§9.2 对 `wt/ratelimit` 的「只取三项增量、保留 master 实现」裁决，已被**用户新裁决取代**——
> 「两套限流实现都合并，用一个配置作为开关，来采用哪种限流方式」。`wt/ratelimit` 已于 2026-11-24
> 以 `git merge --no-ff` 合入 master（限流实现**双轨** + `retail.gateway.rate-limit.mode` 开关 + 互斥装配）。
> §9.2 其余裁决（`wt/lock` 只取回调幂等、`wt/mq` 不合、`wt/statemachine` 合入）**仍然有效**。

- master HEAD `b0bd5c3`（**W-RL 网关限流双模式合入 master**，`git merge --no-ff wt/ratelimit` 的合并提交；双父：`fe118b3` = W-LOCK 合入与 §9.4 订正、`8d1a6de` = `wt/ratelimit` 分支头）；**领先 `origin/master` 6 个提交**（合并提交 + `wt/ratelimit` 4 个提交 + 本行订正提交），**未推送**（推送属对外可见动作，需用户明示）。
- 本次合入为**快进合并**（`git merge --ff-only wt/lock`）：master 已是 `wt/lock` 的祖先，无额外合并提交、无历史改写。
- 工作树干净；`check_docs.py` **PASS 15/15 EXIT 0**（C2 = 15 份 ADR、C13 = 36 项、C14 = 20 条迁移、C15 = 504）；Flyway **V1–V20 共 20 条全部 success**、四 schema **62 张表**。
- 4 条分支 worktree **均已收口**（工作区干净），合并动作已由收口轮执行完毕；**`wt/lock` 已于 2026-11-24 按用户指令「提交吧」合入 master**（Redisson 分布式锁一并带入，`ADR-RF-015`；`ADR-RF-013` 加修订注）——`wt/lock` worktree 与分支**保留待清理**。合并期暴露的两类工程问题记入 **§7 第 12 条**（共享库被不合路线迁移污染 → 重建库）与 **§7 第 13 条**（多会话并发起栈互撞端口、`run-microservices.sh` 的 `netstat -ano` 占用检查在 WSL 恒为空）。
