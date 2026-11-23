# PROGRESS.md · 唯一事实源

> **规则**：本文件是「做到哪一步」的唯一权威源。`README.md` 的模块/断言数、`docs/` 的目标态描述都可能滞后，
> 一律以本文件为准。每轮收口必须更新本文件，再提交。
> **当前状态**：计划内 S0–S5 全部完成（W9 放行，tag `w9-accepted`）· 16 模块代码 + Flyway V1–V14（60 表）· 构建 353 单测 0 失败（2026-11-23 W-SIM2 校正：此前记录 706/748 系统计口径虚高，权威口径=类级/模块级 surefire 汇总）· 走网关冒烟 26/26 · E2E 98/98 · 外部提议协议 v1 已定稿 · W-SIM2 外部模拟器六向对接全通 · 文档自检 PASS。详见 §0。

---

## 0. 状态横幅（每轮收口更新）

| 项 | 值 |
|---|---|
| 轮次 | **S2 · 全六包完成（2026-11-23 凌晨）**：W3/W4/W5 并行窗口 + W6/W7 窗口（账务闭环解除 / 贷后对拍合规）全部合入 master，全量 538 测试 0 失败 → **进行中：W8 收口（全链路 E2E 走网关）** |
| 架构口径 | **Spring Cloud 微服务，直接对齐 floorplan**（用户 2026-11-22 指示，见 `ADR-RF-001`）；12 个部署单元 = 10 业务服务 + 网关 + DMZ |
| 文档语言 | **全文中文**（用户 2026-11-22 指示）：法律/机构名用中文译名，拉丁缩写与代码标识符保留 |
| 交付 | 根 4 文件；`docs/00` + `R00–R11` 共 12 份；`docs/adr/ADR-RF-001~011` 共 11 份；`scripts/`（check_docs / normalize_terms / gen_w2_services / smoke_w2）；`run-microservices.sh`；`.github/workflows/docs-check.yml`；**`frontend/`（W-FE）**；**`external-simulator/`（W-SIM：外部机构模拟器 simulator.js(18100, 零依赖 Node) + 模拟对接 E2E demo_e2e.py）** |
| 代码 | **W1**：`../company-platform-contracts` 19 主类 + 34 单测全绿；**W2（本仓）**：Maven 16 模块——父 pom + 四层共享（domain/application/common/infrastructure）+ 10 业务服务 + 网关 + DMZ；单测 16（JWT 双主体 8 + 事件白名单 8）+ DMZ 验签单测 4；Flyway V1–V9 已实测执行（49 张表）；`mvn -B clean package` 16 模块 BUILD SUCCESS |
| 验证 | `mvn -B clean package` → **BUILD SUCCESS（单测 0 失败；总数以 surefire 汇总为准，见顶部校正注）**；`run-microservices.sh smoke` → **26/26**；`scripts/e2e_w8.py` 全链路 → **98/98 EXIT 0**（可重复）；`check_docs.py` **PASS 13/13**；floorplan 基线未触碰 |
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
| 9 | 一致性与术语自检 | 13 项机器校验落地并实测 **PASS / FAIL 0 / EXIT 0**，替代本轮无代码可编译的「冒烟全绿」 | `scripts/check_docs.py` |
| 10 | 全中文 + 微服务口径回灌 | 全仓术语替换（假名 + 日文汉字） + 3 个 ADR 重命名 + 引用同步 + 10 份 ADR 节标题中文化；重写 `R00 §2 §3.1 §3.2` 与 `R09` 全文、修正 4 处端口段 | `scripts/normalize_terms.py` |
| 11 | C-01 定源 + DMS 对接定源 | C-01 双形态并行（`ADR-RF-002` Accepted）；DMS 初期走数据集成商、四类业务接口与安全规范回填 `R07`（`ADR-RF-011` Accepted）；`R06` 增假名姓名字段 | `R01 §6`、`R07 §3 §4 §7`、`ADR-RF-002`、`ADR-RF-011` |

---

## 2. 共享资源登记（跨项目，改动前必须登记）

| 资源 | 本项目占用 | floorplan 占用 | 状态 |
|---|---|---|---|
| 业务服务端口 | **8090–8099**（10 个） | 8080–8088 | 不冲突 ✅ |
| 前端控制台端口 | **18082**（frontend/serve.py，本地验证用，非部署单元） | — | 不冲突 ✅（用户 2026-11-23 指示「页面也要测试通」） |
| 外部机构模拟器端口 | **18100**（external-simulator/simulator.js，W-SIM；有 node 时编排自动拉起并将 CIC 出站切至模拟器） | — | 不冲突 ✅（用户 2026-11-23 指示「模拟一个外部服务回应你」） |
| 内部网关 | 18081 | 18080 | 不冲突 ✅ |
| 外部 DMZ | 18091 | 18090 | 不冲突 ✅ |
| 数据库宿主端口 | 5435 | 5434 | 不冲突 ✅ |
| 数据库实例 | `retail_finance`（独立） | `floorplan`（独立） | 物理隔离 ✅ |
| Flyway | V1 起（独立库） | V1–V26 已执行、V27–V31 预分配、V40–V79 已占用 | 不撞号 ✅ |
| 配置前缀 | `retail.*` | `floorplan.*` | 不冲突 ✅ |
| 错误码前缀 | `RF_*` | `ADVANCE_*` / `CREDIT_*` 等 | 不冲突 ✅ |
| JWT 环境变量 | `RETAIL_JWT_SECRET` / `RETAIL_CUSTOMER_JWT_SECRET` | `FLOORPLAN_JWT_SECRET` | 三把密钥互不相同 ✅ |
| 中台 jar | `com.company:company-platform-contracts:1.0.0-SNAPSHOT`（**✅ 已抽取**，独立仓 `../company-platform-contracts`，`mvn install` 到本地仓；34 单测全绿；RF 依赖 ≥ 此版本） | floorplan 不强制迁移（继续用 `com.company.floorplan.*` 现网类，基线全绿未动） | ✅ W1 完成 |
| Feign 契约 | `com.company.retail.common.contract`（**✅ W2 已冻结**：`FeignContracts` 代付前 3 门禁契约（contract/consent/underwriting，fail-closed）+ `OutboxPublisher` 17 事件常量 + `EventPayloadGuard` 白名单；`w2-frozen` 后并行包只读） | `com.company.floorplan.common.contract` | 各自常量表 ✅ |

> 变更记录：
> - 2026-11-23：业务端口段由 8090–8098 扩为 **8090–8099**（新增 `recon-service :8099`），
>   同步 `R00 §6`、`README §2`、`R02 §3`、`AGENTS §4`。
> - 2026-11-23：**用户授权**清理收口残留的陈旧表述（§0「下一轮」、§3 旧编号阶段行、§7 假完成警示），
>   按 W9 终态现实回填；事实（验证记录）未改，只清文字残留。
> - 2026-11-23：**用户授权**外部协议先行定源：`R07 §4.1–§4.16` 新增「我方提议协议 v1」字段级规格
>   （通用报文规范 + P-01~P-14，覆盖全部未回填通道）；未新增文档文件与错误码家族，
>   `check_docs.py` 文件计数与 C-xx 登记不受影响。
> - 2026-11-23：**用户授权**全仓文档与 W9 终态对齐：清除 README / `00` / R00 / R02 / R10 / R11 /
>   AGENTS（§4 `run-microservices.sh` 标注）中「零代码/零迁移/零进程、jar 未抽取、服务未联调」等
>   文档初始化时代的陈旧表述，改按实测终态回填；实现事实（`PROGRESS §6` 验证记录）未改。

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
| **后续** | 外部对接与合规验收：DMS 直连演进、征信/银行/身份核验/登记代办/通知通道、个人信息保护控制、访问审计、职责分离、全量回归 | `R07 §7` 调研清单回填 + 外部签约 | ⬜ 未启动（非阻塞，回填前走人工路径） |

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

> 本轮唯一可跑的验收是 `check_docs.py`。在 S1/S2 之前，任何「构建全绿」「12 个服务已联通」的表述都是假的。

---

## 7. 已知「假未完成 / 假完成」警示

1. `R02` 的服务边界定义**已由 W2 落地并经网关冒烟验证**；但「路由可达」≠「业务能力完整」，
   能力完备性以 `scripts/smoke_w2.py`（26 断言）与 `scripts/e2e_w8.py`（98 断言）为准，新增能力必须补断言。
2. `R06` 的表清单是**字段级规格**；实际 DDL 以 `infrastructure/` 下已执行的 Flyway 迁移为准
   （V1–V14，共 60 张表，实测 success）。改表一律走新迁移，不许改已执行迁移的字节。
3. `R10` 的断言清单是**验收设计**；实际通过情况以构建测试数（当前 353，见顶部校正注）+ 冒烟 + E2E 实测记录（§6）为准。
4. `README §4` 的文档编号齐全 ≠ 内容已实现；各文档「交付性质」见各自 §0 文档控制。
5. 与 floorplan 的对应关系是设计对齐（同构微服务），代码层面通过中台 jar 复用
   （`../company-platform-contracts`，W1 已抽取并 `mvn install`，34 单测全绿），其余不直接复用。
6. **12 个服务都起了但彼此零调用 ≠ 微服务已落地**：必须用 Feign 门禁 + outbox + 走网关冒烟证明链路（A-16）——
   此判据已由 W2–W9 的冒烟与 E2E 满足，后续新增服务/链路仍按同判据验收。
