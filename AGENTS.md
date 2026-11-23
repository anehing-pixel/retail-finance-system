# AGENTS.md · 仓库作业手册（新车零售金融 RF）

> 适用对象：在本仓库写代码 / 改文档的任何人与任何 Agent。
> **事实源优先级**：`PROGRESS.md`（做到哪一步） > `AGENTS.md`（怎么干活） > `docs/`（为什么这么设计） > 代码与记忆。
> 三者不互替：进度不写进设计文档，设计理由不写进进度，作业纪律不写进 README。
> 本手册沿用 `../floorplan-system/AGENTS.md` 的范式，并按微服务架构与 C 端个人金融业务做了增删。

---

## 0. 三条铁律

1. **先读 `PROGRESS.md`**，再动手。它是唯一事实源；文档里的「计划 / 目标态」不等于「已实现」。
2. **验证判据是「验收清单全绿」，不是「编译通过」**。floorplan 的历史教训：编译过了、端点存在、网关仍 404；
   建了服务骨架、能力却没迁移。本项目额外增加一条：**文档自检 `python scripts/check_docs.py` 必须 PASS**。
3. **不猜业务口径，不冒充法务结论**。凡 `R01` 标 `[待定源 C-xx]` 的事项，签字前只标注影响、不实现效力判断；
   不许「先按默认值实现、以后再改」。内部架构决策（表、字段、阈值建议值、状态映射）由架构侧直接裁决并标注，
   不包装成「等业务方定」——但**法律适用与合规效力判断不属于内部架构决策**。

---

## 1. 文档分工与阅读顺序

| 文件 | 回答的问题 | 谁维护 |
|---|---|---|
| `AGENTS.md` | 怎么干活、哪些是禁区、什么叫验收通过 | 架构侧 |
| `PROGRESS.md` | 现在做到哪一步、下一步是什么、什么被阻塞 | 每轮收口者 |
| `docs/R00–R11` | 为什么这么设计、口径是什么、边界在哪 | 设计轮 |
| `docs/adr/ADR-RF-*` | 重大决策的取舍与被拒选项 | 架构侧 |
| `README.md` | 项目是什么、怎么导航 | 设计轮 |

**新人第一天**：`README.md` → `docs/00-文档索引.md` → `docs/R00` → `docs/R01` → `docs/R03` → `docs/R02`。
**接手开发**：`AGENTS.md` → `PROGRESS.md` → `docs/R02` → `docs/R04` → `docs/R06` → `docs/R09` → `docs/R07`。

---

## 2. 分层依赖与精确装配（与 floorplan 同构）

> ⚠️ **落地实况（2026-11-23 实测）**：本仓的四层是**服务内分层**，不是**四层共享 jar 分层**。
> `domain/` `application/` 两个共享模块目前**几乎是空壳**——`domain` 只有 3 个文件
> （`RFErrorCode` / `Permission` / package-info），`application` 只有 1 个 package-info。
> 真实业务逻辑（聚合、状态机、用例编排）落在**各服务模块自己的** `*.domain` / `*.application`
> 包内；`common`（横切机制）与 `infrastructure`（Outbox / Flyway / Jdbc 基建）才是真正被复用的 jar。
> **写新代码时以本节末尾的「实际落点」为准，不要往共享 `domain/` `application/` 里堆业务。**

```
gateway / external-api / 10 个业务服务
        → application（用例编排，事务边界唯一允许层）
        → domain（聚合、状态机、不变量、领域策略；零 Spring 注解）
        ← infrastructure（Jdbc 仓储、Flyway、外部适配器、Outbox 投递）
        + common（横切：响应/异常/拦截/审计/契约）
        + statemachine（状态机引擎底座：事件编排 / 守卫 / 动作；不落库）
        + statemachine（可选引擎底座：Spring StateMachine 4.0.0；**领域状态图仍是唯一事实源**）
```

- `domain` **不得 import Spring / common / infrastructure**，必须可用纯 JUnit 跑。
  （实测：全仓 53 个 domain 主文件对 `org.springframework` 零命中，纪律成立。）
- `application` **不得 import `common`**（否则 Maven 循环依赖）；需要发布事件时定义端口接口，适配器放在服务模块内。
  （注：这条约束只针对**共享 `application` jar**；服务内 `*.application` 包经由服务模块依赖 `common`，可用 `OutboxPublisher` 等契约。）
- `common` 里放机制、不放业务语义：本项目 `Permission` / `RFErrorCode` 的**枚举内容**属业务语义，放 `domain`。
  （`UserRole` 目前无 Java 枚举类——角色清单落在 `core.user_roles` 表 + `auth-service` 的 `RolePermissions`。）
- **精确装配**：每服务 `@SpringBootApplication(scanBasePackages = 自身包)` + `@Import` 按类注入，
  不做全包扫描；共享 jar 被复用，隔离靠装配实现，不靠复制代码。
- 只有 `retail-core` 跑 Flyway，其余服务 `flyway.enabled=false`。
- 服务间**同步只走 Feign 门禁（fail-closed）、异步只走 outbox**，禁止跨域直写他表。
- **持久层统一 MyBatis 注解 SQL**（无 XML、无 JPA）：Mapper 接口放各服务 `*.mapper` 包，
  `@MapperScan` 显式列出**单一** mapper 包（精确装配，不做全包扫描）；
  共享 `infrastructure` 的 mapper 由 `RfOutboxConfig` 上的 `@MapperScan` 单独注册。
- **行映射显式化**：Mapper 一律返回 `Map<String,Object>`，由共享 `common` 的 `RowValues` 按列名取值；
  NULL 口径对齐原 `ResultSet.getXxx`（`getLong`→0、`getString`→null），不依赖 ORM 隐式类型推断。
- **一级缓存降为 STATEMENT 级**：各服务 `application.yml` 写 `mybatis.configuration.local-cache-scope: STATEMENT`，
  对齐原 JdbcTemplate「每次读都落库」的读语义，避免同一事务内重复 SELECT 命中本地缓存旧行。
- **唯一键幂等走 `ON CONFLICT` 原子语义**，不采用「捕获唯一键异常再重读」——
  PostgreSQL 事务内一旦触发唯一键错误，整笔事务即被置为 aborted，无法继续读库。
- **状态机纪律（Spring StateMachine 4.0.0，进程内、不引外部中间件）**：
  **领域是状态图唯一事实源**——迁移边一律由领域枚举的 `graph()` 生成，引擎侧**不得另写一份状态图**；
  `domain` 仍**零 Spring**（引擎在共享 `statemachine` 模块 + 各服务 `*.application`，不进领域层）；
  领域尚未提供变更入口的边必须**显式登记为「未接线」**，既不登记也不接事件 → **启动装配期 fail-fast**；
  引擎**不落库**（状态权威始终在数据库），领域方法仍是合法性的最终裁决者，对外错误码与消息一字不改。
- **状态机引擎（可选底座，`statemachine` 共享模块）**：引擎**只做执行器，不做事实源**——
  领域状态图（`LoanStatus` / `ReleaseOrderStatus` / `TitleRetentionStatus` 等枚举 + 允许迁移）仍是唯一事实源；
  引擎不得侵入 `domain`（`domain` 继续零 Spring 注解、纯 JUnit 可跑），接线只放服务内 `*.application.statemachine` 包。
  引擎**不引入外部中间件**（进程内，不违反 B-10「不引 Seata / 分布式事务 / 对象存储」）。
- **分布式锁（`ADR-RF-015`）只收敛并发窗口，不是正确性来源**：锁不可用一律 **fail-closed 拒绝写**
  （`RF_LOCK_002` 503），不降级放行；锁在**事务提交后**释放（`TransactionSynchronization#afterCompletion`），
  临界区**锁内重查**；锁键 `retail:lock:{用途}:{摘要}` 不落明文业务标识；
  锁分域不共用（DMZ 入站与回执登记是两把锁，否则持锁调下游自死锁）。

### 实际落点（按包名前缀找代码）

| 层 | 放哪 | 实例 |
|---|---|---|
| 领域模型 / 状态机 / 领域策略 | 各服务 `*.domain` 包 | `retail-core/.../core/domain`、`underwriting-service/.../underwriting/domain` |
| 用例编排（事务边界） | 各服务 `*.application`（或 `*.app`）包 | `retail-core/.../core/application`、`customer-service/.../customer/app` |
| 持久化 | 各服务 `*.mapper`（SQL）+ `*.repo` / `*.persistence` / `*.infrastructure`（行映射与仓储） | `application-service/.../appservice/{mapper,repo}`、`title-release-service/.../titlerelease/{mapper,persistence}` |
| 横切机制（响应/异常/鉴权/审计/契约） | 共享 `common` jar | `common/.../common/{config,security,web,contract}` |
| Outbox / Flyway / 持久层基建 | 共享 `infrastructure` jar | `infrastructure/.../infrastructure/{outbox,config,persistence,mapper}` |
| 状态机引擎（编排，不放业务语义） | 共享 `statemachine` jar（`com.company.retail.statemachine`）+ 各服务 `*.application.statemachine`（图配置与接线） | `statemachine/.../statemachine`、`retail-core/.../core/application/statemachine` |
| 状态机引擎底座（可选） | 共享 `statemachine` jar + 服务内 `*.application.statemachine` 接线包 | `statemachine/.../statemachine/{DomainStateMachineConfigurer,StateMachineRunner}`、`retail-core/.../core/application/statemachine/` |
| MQ 削峰通道（密文信封 / RocketMQ 适配） | 共享 `common` 端口 + 共享 `infrastructure` 适配 | `common/.../common/messaging`（端口 / 信封）、`infrastructure/.../infrastructure/messaging`（生产端 / 消费端 / 中继） |

> 命名不统一是**已知现状**（各服务用 `application` / `app`、`repo` / `persistence` / `infrastructure` 混用），
> 本轮不改动；新代码**跟随所在服务既有命名**，不要跨服务强行统一。

---

## 3. 业务口径红线（C 端专属）

| # | 红线 | 落点 |
|---|---|---|
| B-1 | **个人信息绝不进入 outbox / 日志 / 对外报文** | `R09 §5` 事件白名单、`R08 §5` 七条控制 |
| B-2 | **客户身份与员工身份两套主体，不得混用 token / 签发方 / 受众** | `ADR-RF-004` |
| B-3 | **禁止直接复用 floorplan 的「载荷必含 VIN」校验与 VIN 事件约定** | `R03 §4` 切断点 ① |
| B-4 | **禁止把 `Dealer` / `CreditLine` 的法人额度语义套到自然人** | `R03 §4` 切断点 ② |
| B-5 | **征信查询必须先有同意留痕，且查询/报送全过程可审计** | `R07 §4`、`R08 §6` |
| B-6 | **所有权保留解除必须「出具文件」与「登记完成」两态分离**，不得把发文件当解除完成 | `R04 §4`、`R05 §6` |
| B-7 | **审查拒绝 / 撤回 / 提前结清 / 正常结清是四个不同的债权终态**，不得合并 | `R04 §3` |
| B-8 | 金额一律 `BIGINT` 整数日元；时间 `TIMESTAMPTZ` 全系统 JST；禁止 `double` 参与金额 | `R00 §5` |
| B-9 | 审计 `actor` 取验签身份；禁止从客户端头或请求体取 | `R00 §5` |
| B-10 | **不引入 Seata / 分布式事务 / 对象存储**；文件本地。**MQ 已引入**（`ADR-RF-014`，2026-11-23 用户授权改口径）：仅作 outbox 之上的**削峰缓冲层**，不参与任何本地事务，**outbox 仍是唯一真相源**（Redis 的**窄用途**限定见 B-13） | `ADR-RF-008`（第 3 条 MQ 部分已被取代）、`ADR-RF-014` |
| B-11 | **核心账务不拆**：入金核销 + 余额 + 债权终态同一本地事务，留在 `retail-core` | `ADR-RF-001` |
| B-12 | **持久层改造不改语义**：SQL 语义、仓储方法签名、事务边界、对外接口一律不变 | `R02 §3.1`、本文件 §2 |
| B-13 | **Redis 用途白名单只有两类**：① **只读业务配置**的 L2 缓存（产品档位等）；② **网关限流状态**。**绝不缓存**债权余额 / 核销流水 / 个人信息 / 征信结果 / 审查结果。**改配置不得改历史计划**（计划侧落快照）；Redis 锁**不作正确性来源**；缓存不可用 → 降级直读数据源，限流不可用 → fail-open。**网关限流双实现互斥**：`retail.gateway.rate-limit.mode` = `redis`（缺省，Redis 令牌桶）／`memory`（进程内令牌桶），**同一时刻只装配一个**，叠加即双重限流 | `ADR-RF-012` §2 §3 §12、`R02 §3.3` |
| B-14 | **状态机：领域为状态图唯一事实源**——引擎迁移边必须由领域状态图生成，**禁止在引擎侧另写一份状态图**；未提供变更入口的边必须显式登记为「未接线」，未登记即启动装配失败；**接入引擎不得改变对外语义**（错误码、消息、事务边界一律不变） | `R04 §5.3`、本文件 §2 §9 |
| B-15 | **MQ 上只允许出现密文信封**：含个人信息的载荷必须先加密成信封再入队（信封 8 键白名单 + 明文 PII 键默认拒绝）；**明文载荷一律不入队**，broker 落盘行永久保留、事后撤回不可能 | `ADR-RF-014`、`R09 §5.5` |

---

## 4. 环境事实（需实测后更新本节，不写死未验证结论）

| 项 | 约定 | 备注 |
|---|---|---|
| JDK / Maven | 17 / 3.9.x | 与 floorplan 一致 |
| PostgreSQL | 16，容器 `retail-finance-db`，宿主端口 **5435** | 5432/5434 已被占用，**不得复用** |
| Redis（分布式锁） | 7，容器 `retail-finance-lock-redis`，宿主端口 **6381** | 分布式锁依赖（`ADR-RF-015`）；**与缓存/限流用的 6382 是两套 Redis、两种用途**（锁 = 写路径互斥，不缓存任何东西）；Redis 不可达 → 受保护写路径 fail-closed 拒写 |
| Redis（缓存 / 限流） | 7，容器 `retail-finance-redis`，宿主端口 **6382** | 6379 被本机 WSL 侧进程占用，**不得复用**；`--save "" --appendonly no` 不持久化；只存缓存与限流态（B-13），丢了可重建 |
| Nacos | 本地不启；`discovery.fail-fast=false` | 网关用 `ROUTE_*` 覆盖为直连 |
| `SERVER__PORT` 坑 | 本机注入会覆盖 `server.port` | 启动一律 `env -u SERVER__PORT` |
| 后台进程 | 随 shell 会话被回收 | 启动 + 测试 + 清理必须放**同一条命令**内 |
| 进程数 | 网关 1 + DMZ 1 + 业务服务 10 = **12 个 JVM** | 用 `run-microservices.sh start/stop/smoke` 编排（✅ W2 已交付） |
| 端口 | 业务 8090–8099 / 网关 18081 / DMZ 18091 / Redis 6381（锁）/ 6382（缓存·限流） / 边缘 Nginx 18076·18077（可选形态，默认不启用） | 与 floorplan 8080–8088 / 18080 / 18090 不冲突 |
| Flyway | 只有 `retail-core` 跑迁移 | 避免重复迁移 |
| RocketMQ | 本地单节点：namesrv **9876** / broker **10911**，运行台在 `data/mq-broker`（不入版本库） | **本轮口径「先不用 MQ 集群」**；Docker Hub 拉取超时，broker 由 Maven Central 的 `rocketmq-namesrv/broker:5.0.0` 直接起；启动须带 `ROCKETMQ_HOME` 与 JDK17 `--add-opens` |
| VSCode Java 语言服务器 | 会锁 `target/classes` 导致 `mvn clean` 失败 | 勿误杀，改用增量 `package` |
| 换行符 | 仓库 blob **一律 LF**；`.gitattributes` 强制 `.sh` / `.py` 为 `eol=lf`；本仓 `core.autocrlf` 不得为 `true` | 见 §4.1 |
| 脚本执行位置 | `run-microservices.sh` / `scripts/dev-stack.sh` 在 **WSL 侧**执行（Windows 侧绑不上 8090–8099/18081/18091） | `PROGRESS §7` 第 9 条；行尾纪律见 §4.1 |

> 本节在 S1 首次实测后必须回填真实值；在此之前任何「已验证」表述都是假的。

### 4.1 Windows 行尾转换与 WSL 执行（2026-11-23 实测）

**现象**：Windows 侧 `core.autocrlf=true` 时，`.sh` 检出到工作区会被转成 CRLF；
本仓启动与验收脚本在 WSL 侧以 bash 执行，CRLF 会让 bash 解析失败。WSL 活体复现的报错原文：

- `bash x.sh` → `line 2: set: pipefail\r: invalid option name`
- 直接执行（走 shebang）→ `env: $'bash\r': No such file or directory`
- Python 脚本直接执行 → `env: $'python3\r': No such file or directory`（改用 `python3 脚本.py` 走解释器则正常）

**根因**：转换发生在**检出**环节，**仓库 blob 本身是 LF**（`git ls-files --eol` 中文本文件一律为 `i/lf`；截图等二进制显示 `-text` 属正常）。
所以「仓库被提交坏了」是误判——坏的是工作区副本，索引与远端都是干净的。

**判定**（不要靠 `bash -n`：实测 CRLF 脚本 `bash -n` **不报错**，检不出该问题）：

```bash
git ls-files --eol | grep -v 'i/lf' | grep -v '\-text'   # 期望：无输出（文本文件索引必须全为 i/lf）
git ls-files --eol | grep 'w/crlf'       # 期望：无输出（工作区也必须为 w/lf）
git check-attr text eol -- run-microservices.sh   # 期望：text: set / eol: lf
```

**修复**（三层，缺一层就复发）：

1. `.gitattributes`（仓库内、随代码走，**最关键的一层**）：
   ```
   *.sh  text eol=lf
   *.py  text eol=lf
   ```
   属性优先级高于 `core.autocrlf`，新克隆的机器不用再单独配置。**规则本体只写在 `.gitattributes`，
   别处禁止再复制一份通配符**（两处漂移 = 又一次复发），且**必须随收口提交**，否则新克隆的机器会复发。
2. 本仓 Git 配置：`git config core.autocrlf input`（入库存 LF、检出不再转换）。
   本仓已实测从 `true` 改为 `input`（**仅 local**，不动全局配置）。
   **不得**设为 `true`；也不依赖全局配置——全局为 `true` 时靠第 1、2 层兜住。
3. 已被转换的工作区文件改回 LF：
   ```bash
   git add --renormalize .     # 按新属性重新规范化索引
   git checkout-index -f -a    # 按属性重新检出工作区
   ```
   ⚠️ **工作区有未提交改动时禁用第 3 步**：两条命令都作用于整棵树（`--renormalize` 会把未暂存的改动一起暂存，
   `checkout-index -f -a` 会覆盖工作区内容）。先提交或 stash 再执行；本仓并行轮次多，改前先 `git status` 看清楚。

**纪律**：

- 新增或改写 `.sh` / `.py` **一律 LF**。Windows 侧写文件时，`Set-Content` / `Out-File` / `>` 重定向
  默认写 CRLF，**不要用**；用 `[System.IO.File]::WriteAllText($path, $content)`（内容原样写出，保持 LF）。
- 提交前跑一次 `git ls-files --eol | grep 'w/crlf'`，非空即中止提交。
- `.sh` 只在 WSL 侧执行；Windows 侧的 bash（Git Bash / cygwin）不作为验收环境。
- `.md` / `.java` / `.yml` 用 CRLF 无害，**不必批量转换**；只对会被解释器直接执行的脚本类型设 `eol=lf`。
- 本仓 `.sh` 的权威入口是 `run-microservices.sh` 与 `scripts/dev-stack.sh`，两者都必须保持 `i/lf w/lf`。

---

## 5. 构建与验收判据

```bash
# 1) 文档自检（本轮唯一可跑的验收）
python scripts/check_docs.py          # 必须输出 PASS、EXIT 0

# 2) S1 起（有代码后；run-microservices.sh 由 W2 交付，本轮不存在，勿提前执行）
mvn -B clean package                 # 全模块 SUCCESS，单测 0 failures / 0 errors
bash run-microservices.sh smoke      # 启动 12 个进程 → 走网关 18081 冒烟 → 自动清理
                                     # 判据：EXIT 0 且 FAIL=0（不写死断言总数）
```

- `-DskipTests` **不算验证**；「编译通过」「端点存在」「服务起来了」均不等于交付。
- 隔离 schema 跑绿 ≠ 交付，必须回默认 schema 跑全量。
- 新增/修改服务必须补对应冒烟断言，且**必须验证从网关可达、返回的是目标服务的业务响应而非兜底 404**。
- Feign 门禁必须验证 JWT 透传生效（缺失时 fallback 拒付，而不是静默放行）。
- 改服务必须补 `scripts/smoke_<agent>.py` 断言；`scripts/check_docs.py` 是文档轮的等价冒烟，同样不许跳过。
- **MQ 通道的验收**（`ADR-RF-014`）：默认关闭时只验零回归；开启后必须跑**真实 broker 活体联调**
  `mvn -B test -pl infrastructure -am -Dtest=MqEnvelopeLiveTest -Drf.mq.live=true`，
  判据含「broker 上回读的原始消息体不含明文个人信息」——只跑单测不算 MQ 链路已通。
- **行尾门禁**（§4.1）：提交前跑 `git ls-files --eol | grep 'w/crlf'`，非空即中止提交——
  行尾转换发生在**检出**环节，编译、单测、冒烟**全都测不出来**（CRLF 脚本 `bash -n` 也不报错）。

---

## 6. 迁移号段与共享资源登记

- 本项目**独立数据库实例**（`retail_finance`），Flyway 从 `V1` 起，与 floorplan 的 `V1–V26` 物理隔离、不会撞号。
- 但**端口段、配置键前缀、JWT 密钥环境变量、中台 jar 版本、Feign 契约**是跨项目共享资源，
  改动前必须在 `PROGRESS.md §2` 登记。
- 号段规划见 `R06 §6`：V1–V9 起步地基、V10–V19 段（**V18 已占用** = 产品档位目录 + 还款计划快照列；**V19 已占用** = 外部登记回执幂等唯一索引）、**V20 已占用** = MQ 密文信封通道（`ADR-RF-014`）、V21–V29 预留（**V21 = G-DB-7 审计写入授权调整**，因 V20 归 MQ 而顺延）、V30+ 按波次分配、V32–V39 有意留空。
- **已执行的迁移字节不改**（Flyway 校验和失配会让服务起不来）；调整一律走新迁移。
- 开新分支前先查 `PROGRESS.md §2` 的号段占用表；两个并行流建同号迁移 = 启动失败（floorplan 真实事故）。
- 新增服务端口必须从 8090–8099 未占用位分配，并同步 `R00 §6` / `README §2` / 网关路由表。

---

## 7. 共享文件禁区（任何 Agent 不得擅自修改）

`PROGRESS.md` · `AGENTS.md` · 父 `pom.xml` · `gateway/src/main/resources/application.yml` ·
`run-microservices.sh` · `scripts/*` · 任何已执行 Flyway 迁移 · `scripts/check_docs.py` ·
`common/contract/OutboxPublisher`（事件名常量表，收口方维护）。

需要改动时，由收口轮统一提交，并在 `PROGRESS.md` 写明授权与理由。
提交一律用 **pathspec 形式**，避免共享工作树卷入他人 WIP：

```bash
git commit -m "..." -- docs/R04-领域模型与状态机设计.md
```

发现他人 WIP：不暂存、不代提交、不覆盖，按时间戳判活并报告。

---

## 8. 编码与命名约定

- 类 / 方法 / 表名 / 枚举英文（值用大写下划线），注释与提交信息**中文**；业务术语沿用 `R00 §2` 中文定名表。
- 包名根 `com.company.retail.*`；错误码 `RF_<域>_<3位序号>`；权限点 `<域>_<动作>`；事件名大写下划线。
- 配置键一律 `retail.*`；JWT 密钥环境变量 `RETAIL_JWT_SECRET` / `RETAIL_CUSTOMER_JWT_SECRET`。
- 响应统一 `ApiResponse<T>`；写接口必带 `Idempotency-Key` + 数据库唯一索引双兜底。
- 状态变更走聚合状态机方法，**禁止直接 `UPDATE ... SET status`**；不物理删除、不软删标记。
- 权限端点必标 `@RequirePermission`；401 认证失败与 403 授权失败严格区分。
- 外部报文**必须经防腐层转换**成内部命令再进 application，禁止外部 DTO 直落领域对象。
- Feign 契约一律返回 `ApiResponse<T>`，属性键小写单词，调用方启动类开启契约包扫描。
- `R00` 是命名唯一权威源；任何新表/枚举/事件/错误码/端口/号段先登记再使用。
- **文档全文中文**：法律与机构名用中文译名，拉丁缩写（CIC、JICC、DMS、VIN、JWT 等）与代码标识符保留原样。

---

## 9. 反模式清单（沿用 floorplan 9 条 + 本项目 12 条）

| # | 反模式 | 实际后果 / 正确做法 |
|---|---|---|
| A-1 | **建目录 / 建启动类就算拆了服务** | floorplan Wave 0 的四个服务只有启动类、能力未迁移 → 必须有真实端点 + 网关路由 + 冒烟断言 |
| A-2 | 写 Feign 契约就算通了 | 必须有实现方 + JWT 透传 + 网关路由 + 断言 |
| A-3 | 网关加路由就算接上了 | 精确路由必须在兜底之前，且实测非核心服务兜底响应 |
| A-4 | 表建了就算功能有了 | 表要有读写方、状态机、断言 |
| A-5 | 并行各建同号迁移 | Flyway 拒绝启动 |
| A-6 | 可配置白名单放宽安全策略 | 硬红线不可做成开关（`R08 §9`） |
| A-7 | 靠共库跨域直写维持原子性 | 每类数据只有一个权威写者 |
| A-8 | 用编译通过当验收 | 见 §5 |
| A-9 | 分两次命令起服务再测试 / 隔离 schema 跑绿就报交付 | 见 §4 §5 |
| **A-10** | **把 `[待定源 C-xx]` 的默认值当成已签字并据此实现法务效力判断** | C-01 形态不同会导致合同、报送、额度、书面文件全部返工；签字前只做「与效力无关的骨架」 |
| **A-11** | **把客户当内部员工发角色 token，或把自然人操作记成员工 `actor`** | 审计链与个人信息保护同时失效；双主体见 `ADR-RF-004` |
| **A-12** | **把姓名/生日/收入/证件号写进 outbox 载荷或应用日志** | outbox 跨服务共享且已发送行永久保留 = 合规事故；见 `R09 §5` |
| **A-13** | **直接复用 floorplan 的「载荷必含 VIN」校验 / 以 VIN 为键的命令** | C 端事件主键是申请号/合同号/债权号，无 VIN 业务主键；见 `R03 §4` |
| **A-14** | **把「所有权解除文件已出具」当作「解除完成」** | 出具文件 ≠ 车辆登记机关登记完成；两态分离见 `R04 §4`、`R05 §6` |
| **A-15** | **把 floorplan 的担保登记 / 放车放证 / 担保门禁直接搬过来** | C 端是已交付车的所有权保留，不是让与担保登记；整条链废弃见 `ADR-RF-006` |
| **A-16** | **12 个服务都起了但彼此零调用，就报「微服务已落地」** | 必须用 Feign 门禁 + outbox 事件 + 走网关冒烟证明链路已通 |
| **A-17** | **引擎侧另写一份状态图**（领域一份、引擎一份，两份漂移） | 迁移边必须由领域 `graph()` 生成；未接线边显式登记，否则启动装配 fail-fast（B-14） |
| **A-18** | **把明文载荷（含个人信息）直接塞进 MQ** | broker 落盘行永久保留、明文不可撤回 = 合规事故；必须先封**密文信封**再入队，见 B-15、`ADR-RF-014` |
| **A-19** | **用 MQ 取代 outbox 当唯一真相源** | 「先写库还是先发消息」的原子性没有保证，等于把金融账务一致性押在时序上；outbox 必须留在本地事务里，MQ 只做其后的削峰缓冲 |
| **A-20** | **broker 不可用时静默降级为同步执行** | 调用方以为已入队、实际同步跑出重复投递与不可预期时序；宁可显式失败 |
| **A-21** | **在 Windows 侧把 `.sh` / `.py` 写回成 CRLF**（`Set-Content` / `Out-File` / `>` 重定向默认写 CRLF） | 仓库 blob 是 LF，坏的是工作区副本；WSL 侧 bash 直接解析失败（`set: pipefail\r: invalid option name`）；写文件用 `[System.IO.File]::WriteAllText`，提交前核对 `git ls-files --eol`，见 §4.1 |

---

## 10. 已裁决口径索引（不在此复述内容，只给坐标）

| 主题 | 坐标 |
|---|---|
| 微服务架构与服务清单 | `ADR-RF-001`、`R02 §3`、`R00 §3.1` |
| 交易形态 / 适用法规 / 解除语义 | `R01 §1 §3`、`ADR-RF-002` |
| 中台共享边界与三处切断点 | `R03`、`ADR-RF-003` |
| 双主体身份与个人信息隔离 | `ADR-RF-004`、`R08` |
| 事件载荷禁个人信息 | `R09 §5`、`ADR-RF-007` |
| MQ 削峰与密文信封 | `ADR-RF-014`、`R09 §5.5`、`R02 §3.2 §7.2 §9 §12` |
| 分布式锁（防重复进件 / 回调幂等） | `ADR-RF-015`、`R09 §6`、`R00 §3.5 §7`、本文件 §2 锁纪律条目 |
| 回调幂等（唯一索引为权威） | `ADR-RF-013`、`R06 §6`（迁移 V19） |
| 端口 / 号段 / 前缀 | `R00 §6 §7`、本文件 §6 |
| 状态机引擎（分层口径 / 迁移范围 / 版本选型） | `R04 §5.3`、共享 `statemachine` 模块、本文件 B-14 |
| 参数默认值（可配置覆盖） | `R01 §7` |

---

## 11. 开工检查单（每轮动手前逐条确认）

- [ ] 已读 `PROGRESS.md`，确认本轮范围与上一轮收口状态
- [ ] 涉及 `[待定源 C-xx]` 的，核对 `R01 §6` 当前裁决状态：✅/🟦 按裁决值实现（可配置覆盖）；🌐 外部事实项只做接口与留痕骨架
- [ ] 涉及命名/号段/端口/服务名的，已查 `R00` 并在 `PROGRESS.md §2` 登记
- [ ] 涉及共享禁区文件的，已获明确授权
- [ ] 涉及外部方的，已确认 `R07 §7` 调研清单是否回填；未回填一律走人工路径 + 防腐层
- [ ] 涉及个人信息 / 事件 / 权限的，已过 `R08 §5` 与 `R09 §5` 白名单检查
- [ ] 涉及缓存 / Redis 的，已过 `ADR-RF-012` §2 用途白名单检查（B-13：只缓存只读配置与限流态，不缓存余额 / 流水 / PII / 征信与审查结果）
- [ ] 涉及状态机的，已确认状态图仍以领域为唯一事实源（`*.Status.graph()`）、未接线边已显式登记、`domain` 未引入 Spring 依赖
- [ ] 涉及新服务的，已确认端口位、网关路由、精确装配清单与冒烟断言归属
- [ ] 收口时跑 `python scripts/check_docs.py`，并在 `PROGRESS.md` 记录结果
- [ ] 涉及 MQ 的：已确认载荷走**密文信封**（非明文）、同一 handler 两个入口（不写两份业务逻辑）、幂等键已登记、开启后跑过**真实 broker 活体联调**（`ADR-RF-014`）
- [ ] 涉及 `.sh` / `.py` 脚本的（含新增、改写、批量替换）：已确认落盘为 LF（`git ls-files --eol` 中该文件为 `i/lf w/lf`），见 §4.1

---

## 12. 自检

本手册覆盖：事实源优先级、分层与精确装配、口径红线、环境事实（12 进程）、验收判据、
号段与共享资源、禁区、命名、反模式 21 条、口径索引、开工检查单，
以及 §4.1 的 Windows 行尾纪律（仓库 blob 一律 LF，脚本只在 WSL 侧执行）。
与 `README.md`（是什么）、`PROGRESS.md`（做到哪一步）、`docs/R00`（命名定源）无内容重复，仅互相引用。
全文中文。
