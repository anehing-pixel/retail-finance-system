---
schema: 1
id: ADR-RF-010
title: "经销商主数据共享、角色隔离"
type: adr
status: accepted
date: "2026-11-22"
updated: "2026-11-22"
owner: 架构侧
deciders: ["架构侧"]
lang: zh-CN
---

# ADR-RF-010：经销商主数据共享、角色隔离

## 文档控制

| 项 | 值 |
|---|---|
| 文档编号 | `ADR-RF-010` |
| 状态 | accepted |
| 决策日期 | 2026-11-22 |
| 最近更新 | 2026-11-22 |
| 决策人 | 架构侧 |
| 关系 | — |

## 状态

**Accepted**（2026-11-22） · 影响：`R02 §5.3`、`R03 §7`、`R07 §3`、`R09 §7`

## 背景

- 同一批法人（销售店 / 4S 店）在两条业务线里扮演**完全不同**的角色：
  floorplan 侧是**债务人**（循环授信、在库垫款、让渡担保），RF 侧是**加盟店 / 代付先 / 申请代办**。
- 这是两项目**唯一值得共享的业务主数据**，也是最容易出错的地方。
- 本项目已按微服务架构拆为多个服务（`ADR-RF-001`），其中 `application-service`（申请代办）、
  `contract-service`（合同）、`title-release-service`（解除文件送付）都需要读主数据。
  若各服务各自直连 floorplan 库或各自同步一份，会出现多个不一致副本，且把 C 端进程接进 B 端数据库，
  违反 `ADR-RF-009`（独立实例、每类数据一个权威写者）。
- floorplan 的 `dealers` / `credit_lines` 以 `dealer_id` 归户，且 `DealerApplicationService.java:30-31`
  明写主体是法人经销商；`R03 §4` 切断点 ② 已裁定 C 端不得复用这套法人归户语义。

## 结论

1. **权威写者唯一**：销售店主数据的权威方是 **floorplan**。RF 侧**只读、不反写**。
2. **单一投影入口**：由中台提供 `DealerMasterClient`（Feign 契约，返回 `ApiResponse<DealerMasterDto>`）
   或只读视图；**只有 `application-service` 做一次同步落 `core.dealer_partners`**（带 `sync_status`、
   `dealer_master_ref`、名称/住址快照）。
3. **其他服务不各自同步**：`contract-service` / `title-release-service` 等通过内部
   `DealerPartnerQueryClient`（`GET /api/dealer-partners/{id}`，返回 `ApiResponse<DealerPartnerDto>`）
   读取投影；跨服务走 Feign + `X-Request-Id`，**不跨库直写、不各自建副本**。
4. **共享 / 隔离对照**（`R03 §7`）：

| 共享 | 隔离 |
|---|---|
| 法人识别（统一代码 / 门店代码）、名称、住址、联系方式、营业状态 | floorplan 的 `credit_lines` / `advances` / 担保登记（RF 不读不写） |
| — | RF 的 `dealer_agreements` / 代付付款账户（`account_ref` 引用码，**不传明文账号**）/ 申请代办 scope |

5. **同步失败降级**：`sync_status=UNSYNCED` → 允许人工录入 + 标记，**不阻塞申请**；
   恢复后由对账工单核对，不自动覆盖人工录入。
6. **禁止**：把 floorplan 的 `dealer_id` 当作 C 端自然人归户键；把融资额度当作加盟店信用；
   在 `pii.*` 或 `core.applications` 里建 `dealer_id` 归户列。
7. 加盟店合同、个人信息委托/共同使用的责任分界由 `[待定源 C-12]` / C-06 定源，
   落在 `core.dealer_agreements` 与 `R08 §6`。

## 后果

**变容易**：主数据只有一个投影点，跨服务一致；C 端进程不接触 B 端库；角色边界清晰可审计；
同步失败不阻塞业务；形态反转（`ADR-RF-002`）不受影响。

**变难**：多一次跨服务 Feign 调用（需 JWT 透传与 fail-closed fallback）；
中台契约变更需双方评审；`UNSYNCED` 数据需要人工对账流程。

**明确不做**：不共享数据库实例、不让多个服务各自同步主数据、不把 floorplan 的额度/担保语义带进 C 端。

## 被拒选项与理由

| 选项 | 理由 |
|---|---|
| 各服务各自直连 floorplan 库读 `dealers` | 把 C 端进程接进 B 端库，违反独立实例与权威写者原则；且产生多份不一致副本 |
| 把 floorplan 的 `Dealer` 实体直接当 C 端加盟店用 | 法人额度/担保语义渗入自然人业务（切断点 ②） |
| 两项目共用一张 `dealers` 表 | 角色、账户、额度无法隔离；一次字段变更影响两条业务线 |
| 同步失败就阻塞申请 | 主数据是参考信息，不应成为可用性单点；人工路径是一等公民 |

## 权衡

放弃「每个服务本地有一份主数据最快」，换取**单一权威源与跨服务一致**。
微服务架构下，共享主数据的正确方式是**一个投影 + 契约查询**，不是每个服务各建副本。
