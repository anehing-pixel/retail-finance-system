# retail-core API 文档

**服务**：`retail-core`（核心账务）
**端口**：`8090`；本地网关 `18081`
**规范**：[openapi.yaml](openapi.yaml) / [openapi.json](openapi.json)
**Swagger UI**：[swagger-ui.html](swagger-ui.html)

> 静态 OpenAPI 3.0.3，由 `gen_openapi.py` 从当前代码生成；不引入 springdoc 运行时依赖。

---

## 1. 认证与通用约定

| 项 | 约定 |
|---|---|
| 认证 | `/api/health` 免鉴权；其余 `/api/**` 需要员工 JWT：`Authorization: Bearer <token>` |
| 主体 | 员工侧 `EMPLOYEE`；客户 token 不能通过员工侧验签 |
| 权限点 | 写接口的 `@RequirePermission` 见各 operation 的 `x-permission` 与下表 |
| 统一响应 | `ApiResponse<T>`：`code / message / data / traceId / details?` |
| 成功 | `code = "OK"`，`message = "OK"` |
| 失败 | `code = RF_<域>_<3位序号>`，HTTP 状态按错误码映射（400/401/403/409/422/500/502/503） |
| 金额 | 一律整数日元（`int64`），禁止浮点 |
| 日期 | `yyyy-MM-dd`；时间 ISO-8601 `date-time`，业务日 JST |
| traceId | 请求头 `X-Request-Id` 可选；响应头回显，响应体 `traceId` 同值 |
| 幂等 | `POST /api/disbursements`、`POST /api/payments`、`POST /api/loans/{id}/settle` 必须带 `Idempotency-Key` |

---

## 2. 接口清单（19 个 operation）

| # | 方法 | 路径 | 权限点 | 幂等 | 入参 | 出参 |
|---:|---|---|---|---|---|---|
| 1 | GET | `/api/health` | 免鉴权 | — | 无 | `HealthView` |
| 2 | GET | `/api/whoami` | 员工 JWT | — | 无 | `WhoamiView` |
| 3 | POST | `/api/loans` | `DISBURSEMENT_EXEC` | `uk_loan_contract` | `CreateLoanRequest` | `LoanView` |
| 4 | GET | `/api/loans/{id}` | 员工 JWT | — | path `id` | `LoanView` |
| 5 | GET | `/api/loans/{id}/balance` | 员工 JWT | — | path `id` | `BalanceView` |
| 6 | POST | `/api/repayment-plans` | `CONTRACT_ISSUE` | `uk_plan_loan_version` | `GeneratePlanRequest` | `PlanView` |
| 7 | GET | `/api/repayment-plans?loanId=` | 员工 JWT | — | query `loanId` | `PlanItemsView` |
| 8 | POST | `/api/disbursements` | `DISBURSEMENT_EXEC` | `Idempotency-Key` + 2 唯一索引 | `DisbursementRequest` | `DisbursementView` |
| 9 | POST | `/api/payments` | `PAYMENT_ALLOCATE` | `Idempotency-Key` + 2 唯一索引 | `PaymentRequest` | `PaymentView` |
| 10 | POST | `/api/payments/{id}/manual-match` | `PAYMENT_MANUAL_MATCH` | `uk_alloc_payment` | `ManualMatchRequest` | `PaymentView` |
| 11 | GET | `/api/payments/{id}` | 员工 JWT | — | path `id` | `PaymentView` |
| 12 | GET | `/api/loans/{id}/settlement-quote` | 员工 JWT | — | path `id` + query | `SettlementQuoteView` |
| 13 | POST | `/api/loans/{id}/settle` | `SETTLEMENT_CONFIRM` | `Idempotency-Key` + `uk_settlement_loan` | `SettlementRequest` | `SettlementView` |
| 14 | GET | `/api/loans/{id}/reconciliation` | 员工 JWT | — | path `id` | `Reconciliation` |
| 15 | POST | `/api/financial-products` | `CONFIG_MAINTAIN` | `uk_fin_product_ver` | `ProductCreateRequest` | `ProductProfile` |
| 16 | PUT | `/api/financial-products/{code}` | `CONFIG_MAINTAIN` | 乐观锁 `version` | `ProductReviseRequest` | `ProductProfile` |
| 17 | GET | `/api/financial-products/{code}` | 员工 JWT | — | path `code` | `ProductProfile` |
| 18 | GET | `/api/financial-products/{code}/versions` | 员工 JWT | — | path `code` | `ProductProfile[]` |
| 19 | POST | `/api/jobs/{jobName}` | `JOB_TRIGGER` | `uk_batch_job_date` | `JobRequest` | `JobResult` |

---

## 3. 示例场景

Swagger UI 中每个 operation 都带示例；以下为贯穿示例：

| 场景 | 值 |
|---|---|
| 合同 | `contractId = 10001` |
| 债权 | `loanId = 5001`，本金 `1,230,000`，费用 `0` |
| 还款计划 | 3 期、0 息、每期 `410,000` |
| 代付 | `disbursementId = 9001`，状态 `PAID`，债权 `ACTIVE` |
| 首期核销 | 入金 `410,000`，核销本金 `410,000`，余额 `820,000` |
| 提前结清 | 剩余本金 `820,000`、减免 `20,000`、手续费 `10,000` → 报价 `810,000` |
| 对拍 | 结清后三口径全 `0`，`consistent = true` |

包含入参/出参示例的典型 operation：

- `POST /api/repayment-plans`：零息 3 期 + 2.0% 12 期两个请求示例
- `POST /api/payments`：`ALLOCATED` + `UNMATCHED` 两个成功示例
- `POST /api/loans/{id}/settle`：`EARLY` + `NORMAL` 两个请求/成功示例
- 写接口均带至少一个 4xx 错误示例（`RF_VALID_001` / `RF_CONFLICT_001` / `RF_PAY_002` 等）

---

## 4. 当前实现注意（避免误读）

1. **`GET /api/payments/{id}` 是简化视图**：`loanId`、`allocatedFee/Interest/Principal`、
   `loanBalanceAfter` 以 0 占位，`loanStatusAfter` 回填入金状态；核销拆分请查还款计划或对拍端点。
2. **`POST /api/payments` 的 `loanId <= 0` 或缺省** → `UNMATCHED`，不自动分摊；
   需要 `POST /api/payments/{id}/manual-match` 人工匹配。
3. **提前结清减免上限未强制**：`waiverCapBp` 存在产品档位，但结清只校验
   “减免非负 + 减免 ≤ 剩余本金”。
4. **计划只支持单版本**：`uk_plan_loan_version`，本波次 `plan_version = 1`。
5. **利率快照**：`product_code / product_version / rate_bp` 落在 `core.repayment_plans`；
   改档位不回改历史计划。
6. **批处理路径参数**：`/api/jobs/{jobName}` 当前只接受 `schedule-due-rollup`。
7. **代付前置校验是 fail-closed**：合同/审查任一“查不到 / 超时 / 非 2xx / allowed != true”
   一律 `RF_LOAN_001`，UNKNOWN 不视为通过。
8. **产品档位改版**：新版本 `effectiveFrom` 固定为当日 JST；请求体里的 `effectiveFrom`
   当前不参与 revise（create 仍使用该字段）。

---

## 5. 重新生成与验证

```bash
python api-docs/retail-core/gen_openapi.py
```

验证：

- OpenAPI 3.0.3 schema 校验（官方 OAS 3.0 schema，`jsonschema` Draft4Validator）
- `$ref` 完整性：所有引用命中 `components`
- 示例覆盖：19/19 operation 有 200 示例；POST/PUT 有请求示例
- Swagger UI：Edge headless 加载，19 个 `.opblock`，0 error widget

> 本目录是静态文档，不改变服务运行期行为；Java 构建/单测口径仍以 `PROGRESS.md` 为准。
