#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 retail-core 的 OpenAPI 3.0.3 规范与离线 Swagger UI。

用法（仓库根目录）：
    python api-docs/retail-core/gen_openapi.py

输出：
    api-docs/retail-core/openapi.yaml
    api-docs/retail-core/openapi.json
    api-docs/retail-core/swagger-ui.html   （内嵌 spec，file:// 直接打开可渲染）

说明：
    - 静态文档，不引入 springdoc / swagger 运行时依赖；
    - 规范与示例以当前代码为准，接口入参/出参见各 operation 的 examples；
    - Swagger UI 资源已本地化到 api-docs/assets/swagger-ui/（5.17.14，Apache-2.0）。
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = Path(__file__).resolve().parent
ASSETS_REL = "../assets/swagger-ui"

YAML_OUT = OUT_DIR / "openapi.yaml"
JSON_OUT = OUT_DIR / "openapi.json"
HTML_OUT = OUT_DIR / "swagger-ui.html"

TRACE = "6f1c2a9e-3b7d-4c1a-9e2f-8a4b5c6d7e8f"


# ---------------------------------------------------------------------------
# 通用构造器
# ---------------------------------------------------------------------------
def ref(name: str) -> dict:
    return {"$ref": f"#/components/schemas/{name}"}


def ok(data):
    return {"code": "OK", "message": "OK", "data": data, "traceId": TRACE}


def fail(code: str, message: str, details=None):
    body = {"code": code, "message": message, "traceId": TRACE}
    if details is not None:
        body["details"] = details
    return body


def ex(summary: str, value):
    return {"summary": summary, "value": value}


def ok_resp(data_schema: dict, example_value, description: str = "成功",
             extra: dict | None = None) -> dict:
    wrapper = {
        "allOf": [
            ref("ApiResponseBase"),
            {"type": "object", "properties": {"data": data_schema}},
        ]
    }
    examples = {"success": ex(description, ok(example_value))}
    if extra:
        for name, (summary, value) in extra.items():
            examples[name] = ex(summary, ok(value))
    return {
        "200": {
            "description": description,
            "content": {
                "application/json": {
                    "schema": wrapper,
                    "examples": examples,
                }
            },
        }
    }


def error_resp(status: int, description: str, examples: dict) -> dict:
    return {
        str(status): {
            "description": description,
            "content": {
                "application/json": {
                    "schema": ref("ApiResponseError"),
                    "examples": examples,
                }
            },
        }
    }


def auth_resp(permission: str | None = None) -> dict:
    forbidden_summary = f"缺少权限点 {permission}" if permission else "权限不足"
    return {
        **error_resp(401, "未认证 / token 无效", {
            "unauthorized": ex("缺少或无效的员工 JWT", fail(
                "RF_AUTH_001", "未认证 / token 无效", {"reason": "BAD_TOKEN"})),
        }),
        **error_resp(403, "权限不足", {
            "forbidden": ex(forbidden_summary, fail(
                "RF_AUTH_002", "权限不足", {"required": permission} if permission else None)),
        }),
    }


def sys_resp() -> dict:
    return error_resp(500, "系统异常", {
        "system": ex("未预期异常", fail("RF_SYS_001", "未预期异常：系统内部错误")),
    })


def merge(*dicts: dict) -> dict:
    out: dict = {}
    for d in dicts:
        out.update(d)
    return out


def body(schema_name: str, examples: dict, description: str | None = None) -> dict:
    b = {
        "required": True,
        "content": {
            "application/json": {
                "schema": ref(schema_name),
                "examples": examples,
            }
        },
    }
    if description:
        b["description"] = description
    return b


def path_param(name: str, description: str, schema: dict | None = None) -> dict:
    return {
        "name": name,
        "in": "path",
        "required": True,
        "description": description,
        "schema": schema or {"type": "integer", "format": "int64"},
    }


def query_param(name: str, description: str, schema: dict, required: bool = False) -> dict:
    return {
        "name": name,
        "in": "query",
        "required": required,
        "description": description,
        "schema": schema,
    }


def op(tag: str, summary: str, description: str, operation_id: str,
       responses: dict, parameters: list | None = None, request_body: dict | None = None,
       permission: str | None = None, idempotent: bool = False) -> dict:
    o = {
        "tags": [tag],
        "summary": summary,
        "description": description,
        "operationId": operation_id,
        "parameters": parameters or [],
        "responses": responses,
    }
    if request_body is not None:
        o["requestBody"] = request_body
    if permission:
        o["x-permission"] = permission
    if idempotent:
        o["x-idempotency-required"] = True
    return o


TRACE_PARAM = {"$ref": "#/components/parameters/TraceIdHeader"}
IDEM_PARAM = {"$ref": "#/components/parameters/IdempotencyKey"}


# ---------------------------------------------------------------------------
# 组件：schema
# ---------------------------------------------------------------------------
LOAN_STATUS = [
    "PENDING_DISBURSEMENT", "DISBURSING", "ACTIVE", "DISBURSE_FAILED",
    "DELINQUENT", "SETTLED", "EARLY_SETTLED", "WRITTEN_OFF", "CANCELLED",
]
ITEM_STATUS = ["OPEN", "DUE", "PARTIAL", "PAID", "OVERDUE", "WAIVED"]
PAYMENT_CHANNEL = ["DIRECT_DEBIT", "PAYMENT_SLIP", "CONVENIENCE_STORE", "BANK_TRANSFER"]
REPAYMENT_METHOD = ["EQUAL_PAYMENT", "EQUAL_PRINCIPAL", "BONUS_PAYMENT", "LUMP_SUM"]
PRODUCT_SCHEME = ["INSTALLMENT_CREDIT", "MONEY_LENDING"]

SCHEMAS: dict = {
    "ApiResponseBase": {
        "type": "object",
        "required": ["code", "message", "traceId"],
        "properties": {
            "code": {"type": "string", "description": "业务码；成功为 OK", "example": "OK"},
            "message": {"type": "string", "description": "业务消息", "example": "OK"},
            "traceId": {"type": "string", "description": "请求追踪 ID；与响应头 X-Request-Id 同值",
                        "example": TRACE},
            "details": {"type": "object", "additionalProperties": True, "nullable": True,
                        "description": "错误明细；成功时通常省略"},
        },
    },
    "ApiResponseError": {
        "type": "object",
        "required": ["code", "message", "traceId"],
        "properties": {
            "code": {"type": "string", "description": "错误码，格式 RF_<域>_<3位序号>",
                     "example": "RF_VALID_001"},
            "message": {"type": "string", "example": "参数错误"},
            "traceId": {"type": "string", "example": TRACE},
            "details": {"type": "object", "additionalProperties": True, "nullable": True,
                        "description": "错误明细（字段名、业务主键、原因码等）"},
        },
    },
    "CreateLoanRequest": {
        "type": "object",
        "required": ["contractId", "principal"],
        "properties": {
            "contractId": {"type": "integer", "format": "int64", "minimum": 1,
                           "example": 10001},
            "principal": {"type": "integer", "format": "int64", "minimum": 1,
                          "example": 1230000, "description": "本金（整数日元）"},
            "feeAmount": {"type": "integer", "format": "int64", "minimum": 0,
                          "default": 0, "example": 0, "description": "债权费用（整数日元）"},
        },
    },
    "LoanView": {
        "type": "object",
        "required": ["loanId", "contractId", "loanNo", "status", "principal",
                     "feeAmount", "balance", "version"],
        "properties": {
            "loanId": {"type": "integer", "format": "int64", "example": 5001},
            "contractId": {"type": "integer", "format": "int64", "example": 10001},
            "loanNo": {"type": "string", "example": "LN-10001",
                       "description": "债权编号；由合同 ID 派生 LN-<contractId>"},
            "status": {"type": "string", "enum": LOAN_STATUS, "example": "PENDING_DISBURSEMENT"},
            "principal": {"type": "integer", "format": "int64", "description": "本金（整数日元）",
                          "example": 1230000},
            "feeAmount": {"type": "integer", "format": "int64", "description": "债权费用（整数日元）",
                          "example": 0},
            "balance": {"type": "integer", "format": "int64", "description": "余额（整数日元）",
                        "example": 1230000},
            "disbursedAt": {"type": "string", "format": "date-time", "nullable": True,
                            "example": None},
            "version": {"type": "integer", "format": "int32", "example": 0},
        },
    },
    "BalanceView": {
        "type": "object",
        "required": ["loanId", "balance", "status"],
        "properties": {
            "loanId": {"type": "integer", "format": "int64", "example": 5001},
            "balance": {"type": "integer", "format": "int64", "example": 820000},
            "status": {"type": "string", "enum": LOAN_STATUS, "example": "ACTIVE"},
        },
    },
    "GeneratePlanRequest": {
        "type": "object",
        "required": ["loanId", "method", "firstDueDate"],
        "properties": {
            "loanId": {"type": "integer", "format": "int64", "example": 5001},
            "method": {"type": "string", "enum": REPAYMENT_METHOD, "example": "EQUAL_PAYMENT",
                       "description": "还款方式：等额本息 / 等额本金 / 奖金还款 / 一次性"},
            "installmentCount": {"type": "integer", "format": "int32", "minimum": 1,
                                 "nullable": True, "example": 3,
                                 "description": "期数；指定 productCode 时可省略，取档位默认"},
            "firstDueDate": {"type": "string", "format": "date", "example": "2026-12-10",
                             "description": "首期到期日（yyyy-MM-dd）"},
            "monthlyRateBp": {"type": "integer", "format": "int32", "minimum": 0,
                              "nullable": True, "example": 0,
                              "description": "月利率基点；200 = 2.0%/月；省略时取档位默认"},
            "bonusMonth": {"type": "integer", "format": "int32", "minimum": 1, "maximum": 12,
                           "nullable": True, "example": None,
                           "description": "奖金月（1-12）；仅 BONUS_PAYMENT 使用"},
            "productCode": {"type": "string", "maxLength": 32, "nullable": True,
                            "example": "AUTO_LOAN_36_0",
                            "description": "产品档位码；省略 = 纯入参模式"},
        },
    },
    "PlanView": {
        "type": "object",
        "required": ["planId", "loanId", "method", "installmentCount", "firstDueDate",
                     "monthlyRateBp", "totalAmountDue", "totalPrincipalDue", "totalInterestDue"],
        "properties": {
            "planId": {"type": "integer", "format": "int64", "example": 8001},
            "loanId": {"type": "integer", "format": "int64", "example": 5001},
            "method": {"type": "string", "enum": REPAYMENT_METHOD, "example": "EQUAL_PAYMENT"},
            "installmentCount": {"type": "integer", "format": "int32", "example": 3},
            "firstDueDate": {"type": "string", "format": "date", "example": "2026-12-10"},
            "bonusMonth": {"type": "integer", "format": "int32", "nullable": True, "example": None},
            "monthlyRateBp": {"type": "integer", "format": "int32", "example": 0,
                              "description": "实际采用的月利率基点（快照）"},
            "productCode": {"type": "string", "nullable": True, "example": "AUTO_LOAN_36_0"},
            "productVersion": {"type": "integer", "format": "int32", "nullable": True,
                               "example": 1},
            "totalAmountDue": {"type": "integer", "format": "int64", "example": 1230000,
                               "description": "Σ amountDue（本金 + 利息）"},
            "totalPrincipalDue": {"type": "integer", "format": "int64", "example": 1230000},
            "totalInterestDue": {"type": "integer", "format": "int64", "example": 0,
                                 "description": "Σ interestDue；计划生成时一次性计入应计利息"},
        },
    },
    "ScheduleItem": {
        "type": "object",
        "required": ["periodNo", "dueDate", "amountDue", "principalDue",
                     "interestDue", "status", "paidAmount"],
        "properties": {
            "periodNo": {"type": "integer", "format": "int32", "example": 1},
            "dueDate": {"type": "string", "format": "date", "example": "2026-12-10"},
            "amountDue": {"type": "integer", "format": "int64", "example": 410000},
            "principalDue": {"type": "integer", "format": "int64", "example": 410000},
            "interestDue": {"type": "integer", "format": "int64", "example": 0},
            "status": {"type": "string", "enum": ITEM_STATUS, "example": "DUE"},
            "paidAmount": {"type": "integer", "format": "int64", "example": 0},
        },
    },
    "PlanItemsView": {
        "type": "object",
        "required": ["loanId", "items"],
        "properties": {
            "loanId": {"type": "integer", "format": "int64", "example": 5001},
            "items": {"type": "array", "items": ref("ScheduleItem")},
        },
    },
    "DisbursementRequest": {
        "type": "object",
        "required": ["loanId", "contractId", "underwritingCaseId"],
        "properties": {
            "loanId": {"type": "integer", "format": "int64", "example": 5001},
            "contractId": {"type": "integer", "format": "int64", "example": 10001},
            "underwritingCaseId": {"type": "integer", "format": "int64", "example": 7001},
            "payeeAccountRef": {"type": "string", "nullable": True,
                                "example": "DEALER-ACCT-001",
                                "description": "收款方引用码；非明文银行账号"},
        },
    },
    "DisbursementView": {
        "type": "object",
        "required": ["disbursementId", "loanId", "contractId", "status", "amount",
                     "currency", "instructionRef", "paidAt", "loanStatus"],
        "properties": {
            "disbursementId": {"type": "integer", "format": "int64", "example": 9001},
            "loanId": {"type": "integer", "format": "int64", "example": 5001},
            "contractId": {"type": "integer", "format": "int64", "example": 10001},
            "status": {"type": "string", "example": "PAID"},
            "amount": {"type": "integer", "format": "int64", "example": 1230000},
            "currency": {"type": "string", "example": "JPY"},
            "instructionRef": {"type": "string", "example": "INS-5001-DEALER-ACCT-001"},
            "paidAt": {"type": "string", "format": "date-time",
                       "example": "2026-11-28T03:00:00Z"},
            "loanStatus": {"type": "string", "enum": LOAN_STATUS, "example": "ACTIVE"},
        },
    },
    "PaymentRequest": {
        "type": "object",
        "required": ["accountRef", "transactionId", "amount", "channel"],
        "properties": {
            "loanId": {"type": "integer", "format": "int64", "example": 5001,
                       "description": "债权 ID；<= 0 或缺省时登记为 UNMATCHED，需人工匹配"},
            "accountRef": {"type": "string", "maxLength": 64, "example": "ACCT-001",
                           "description": "账户引用码（非明文账号）"},
            "transactionId": {"type": "string", "maxLength": 64,
                              "example": "TX-20261128-001",
                              "description": "交易流水号；与 accountRef 组成去重键"},
            "amount": {"type": "integer", "format": "int64", "minimum": 1,
                       "example": 410000, "description": "入金金额（整数日元）"},
            "channel": {"type": "string", "enum": PAYMENT_CHANNEL,
                        "example": "BANK_TRANSFER"},
            "paymentRef": {"type": "string", "maxLength": 64, "nullable": True,
                           "example": "PM-20261128-001",
                           "description": "入金引用；省略时派生 PM-<accountRef>-<transactionId>"},
        },
    },
    "ManualMatchRequest": {
        "type": "object",
        "required": ["loanId"],
        "properties": {
            "loanId": {"type": "integer", "format": "int64", "minimum": 1, "example": 5001},
        },
    },
    "PaymentView": {
        "type": "object",
        "required": ["paymentId", "paymentRef", "loanId", "status", "amount",
                     "allocatedFee", "allocatedInterest", "allocatedPrincipal",
                     "loanBalanceAfter", "loanStatusAfter"],
        "properties": {
            "paymentId": {"type": "integer", "format": "int64", "example": 10001},
            "paymentRef": {"type": "string", "example": "PM-20261128-001"},
            "loanId": {"type": "integer", "format": "int64", "example": 5001},
            "status": {"type": "string", "enum": ["RECEIVED", "MATCHED", "UNMATCHED", "ALLOCATED"],
                       "example": "ALLOCATED"},
            "amount": {"type": "integer", "format": "int64", "example": 410000},
            "allocatedFee": {"type": "integer", "format": "int64", "example": 0},
            "allocatedInterest": {"type": "integer", "format": "int64", "example": 0},
            "allocatedPrincipal": {"type": "integer", "format": "int64", "example": 410000},
            "loanBalanceAfter": {"type": "integer", "format": "int64", "example": 820000},
            "loanStatusAfter": {"type": "string", "example": "ACTIVE"},
        },
    },
    "SettlementRequest": {
        "type": "object",
        "required": ["type"],
        "properties": {
            "type": {"type": "string", "enum": ["NORMAL", "EARLY"], "example": "EARLY",
                     "description": "NORMAL = 全部结清；EARLY = 提前结清（C-10 公式）"},
            "waiverAmount": {"type": "integer", "format": "int64", "minimum": 0,
                             "default": 0, "example": 20000,
                             "description": "提前结清减免额（整数日元）；NORMAL 时忽略"},
            "settlementFee": {"type": "integer", "format": "int64", "minimum": 0,
                              "default": 0, "example": 10000,
                              "description": "事务手续费（整数日元）；NORMAL 时忽略"},
        },
    },
    "SettlementView": {
        "type": "object",
        "required": ["loanId", "status", "settlementType", "quote", "waiverAmount",
                     "settlementFee", "balanceAfter", "settledAt"],
        "properties": {
            "loanId": {"type": "integer", "format": "int64", "example": 5001},
            "status": {"type": "string", "enum": LOAN_STATUS, "example": "EARLY_SETTLED"},
            "settlementType": {"type": "string", "enum": ["NORMAL", "EARLY"],
                               "example": "EARLY"},
            "quote": {"type": "integer", "format": "int64", "example": 810000,
                      "description": "结清报价；NORMAL 为 0"},
            "waiverAmount": {"type": "integer", "format": "int64", "example": 20000},
            "settlementFee": {"type": "integer", "format": "int64", "example": 10000},
            "balanceAfter": {"type": "integer", "format": "int64", "example": 0},
            "settledAt": {"type": "string", "format": "date-time",
                          "example": "2026-11-28T04:00:00Z"},
        },
    },
    "SettlementQuoteView": {
        "type": "object",
        "required": ["loanId", "loanStatus", "remainingPrincipal", "waiverAmount",
                     "settlementFee", "feeOutstanding", "quote"],
        "properties": {
            "loanId": {"type": "integer", "format": "int64", "example": 5001},
            "loanStatus": {"type": "string", "enum": LOAN_STATUS, "example": "ACTIVE"},
            "remainingPrincipal": {"type": "integer", "format": "int64", "example": 820000},
            "waiverAmount": {"type": "integer", "format": "int64", "example": 20000},
            "settlementFee": {"type": "integer", "format": "int64", "example": 10000},
            "feeOutstanding": {"type": "integer", "format": "int64", "example": 0},
            "quote": {"type": "integer", "format": "int64", "example": 810000,
                      "description": "剩余本金 - 减免额 + 手续费"},
        },
    },
    "Reconciliation": {
        "type": "object",
        "required": ["loanBalance", "ledgerBalance", "outstandingBalance",
                     "scheduleOutstanding", "feeOutstanding", "consistent"],
        "properties": {
            "loanBalance": {"type": "integer", "format": "int64", "example": 0},
            "ledgerBalance": {"type": "integer", "format": "int64", "example": 0},
            "outstandingBalance": {"type": "integer", "format": "int64", "example": 0},
            "scheduleOutstanding": {"type": "integer", "format": "int64", "example": 0},
            "feeOutstanding": {"type": "integer", "format": "int64", "example": 0},
            "consistent": {"type": "boolean", "example": True},
        },
    },
    "ProductCreateRequest": {
        "type": "object",
        "required": ["productCode", "productName", "scheme", "termCount", "rateBp"],
        "properties": {
            "productCode": {"type": "string", "maxLength": 32, "example": "INSTALMENT_36_200"},
            "productName": {"type": "string", "maxLength": 64, "example": "个品分期 36 期 月息 2.0%"},
            "scheme": {"type": "string", "enum": PRODUCT_SCHEME, "example": "INSTALLMENT_CREDIT"},
            "termCount": {"type": "integer", "format": "int32", "minimum": 1, "example": 36},
            "rateBp": {"type": "integer", "format": "int32", "minimum": 0, "example": 200,
                       "description": "月利率基点；200 = 2.0%/月；0 = 零息"},
            "bonusMonth": {"type": "integer", "format": "int32", "minimum": 1, "maximum": 12,
                           "nullable": True, "example": None},
            "waiverCapBp": {"type": "integer", "format": "int32", "minimum": 0, "maximum": 1000,
                            "nullable": True, "example": None,
                            "description": "提前结清减免上限（千分比）；当前结清未强制校验"},
            "effectiveFrom": {"type": "string", "format": "date", "nullable": True,
                              "example": "2026-01-01",
                              "description": "生效日；省略 = 当日 JST"},
        },
    },
    "ProductReviseRequest": {
        "type": "object",
        "required": ["productName", "scheme", "termCount", "rateBp"],
        "properties": {
            "productName": {"type": "string", "maxLength": 64, "example": "个品分期 36 期 月息 1.0%"},
            "scheme": {"type": "string", "enum": PRODUCT_SCHEME, "example": "INSTALLMENT_CREDIT"},
            "termCount": {"type": "integer", "format": "int32", "minimum": 1, "example": 36},
            "rateBp": {"type": "integer", "format": "int32", "minimum": 0, "example": 100},
            "bonusMonth": {"type": "integer", "format": "int32", "minimum": 1, "maximum": 12,
                           "nullable": True, "example": None},
            "waiverCapBp": {"type": "integer", "format": "int32", "minimum": 0, "maximum": 1000,
                            "nullable": True, "example": None},
            "effectiveFrom": {"type": "string", "format": "date", "nullable": True,
                              "example": "2026-11-28",
                              "description": "当前实现未使用：revise 的新版本生效日固定为当日 JST"},
        },
    },
    "ProductProfile": {
        "type": "object",
        "required": ["productCode", "productVersion", "productName", "scheme", "termCount",
                     "rateBp", "effectiveFrom", "enabled", "lockVersion"],
        "properties": {
            "productCode": {"type": "string", "example": "INSTALMENT_36_200"},
            "productVersion": {"type": "integer", "format": "int32", "example": 1},
            "productName": {"type": "string", "example": "个品分期 36 期 月息 2.0%"},
            "scheme": {"type": "string", "enum": PRODUCT_SCHEME, "example": "INSTALLMENT_CREDIT"},
            "termCount": {"type": "integer", "format": "int32", "example": 36},
            "rateBp": {"type": "integer", "format": "int32", "example": 200},
            "bonusMonth": {"type": "integer", "format": "int32", "nullable": True, "example": None},
            "waiverCapBp": {"type": "integer", "format": "int32", "nullable": True, "example": None},
            "effectiveFrom": {"type": "string", "format": "date", "example": "2026-01-01"},
            "effectiveTo": {"type": "string", "format": "date", "nullable": True, "example": None},
            "enabled": {"type": "boolean", "example": True},
            "lockVersion": {"type": "integer", "format": "int32", "example": 0,
                            "description": "乐观锁版本"},
        },
    },
    "JobRequest": {
        "type": "object",
        "properties": {
            "bizDate": {"type": "string", "format": "date", "nullable": True,
                        "example": "2026-11-28",
                        "description": "业务日期；省略 = 当天"},
        },
    },
    "JobResult": {
        "type": "object",
        "required": ["jobName", "bizDate", "dueCount", "overdueCount"],
        "properties": {
            "jobName": {"type": "string", "example": "schedule-due-rollup"},
            "bizDate": {"type": "string", "format": "date", "example": "2026-11-28"},
            "dueCount": {"type": "integer", "format": "int32", "example": 3},
            "overdueCount": {"type": "integer", "format": "int32", "example": 0},
        },
    },
    "HealthView": {
        "type": "object",
        "required": ["service", "status"],
        "properties": {
            "service": {"type": "string", "example": "retail-core"},
            "status": {"type": "string", "example": "UP"},
        },
    },
    "WhoamiView": {
        "type": "object",
        "required": ["service", "subject", "subjectType", "roles", "traceId"],
        "properties": {
            "service": {"type": "string", "example": "retail-core"},
            "subject": {"type": "string", "example": "staff-1001"},
            "subjectType": {"type": "string", "example": "EMPLOYEE"},
            "roles": {"type": "array", "items": {"type": "string"},
                      "example": ["OPERATIONS"]},
            "traceId": {"type": "string", "example": TRACE},
        },
    },
}

# 成功响应 wrapper
for name in ("LoanView", "BalanceView", "PlanView", "PlanItemsView", "DisbursementView",
             "PaymentView", "SettlementView", "SettlementQuoteView", "Reconciliation",
             "ProductProfile", "JobResult", "HealthView", "WhoamiView"):
    SCHEMAS[f"ApiResponse{name}"] = {
        "allOf": [
            ref("ApiResponseBase"),
            {"type": "object", "properties": {"data": ref(name)}},
        ]
    }

SCHEMAS["ApiResponseProductProfileList"] = {
    "allOf": [
        ref("ApiResponseBase"),
        {"type": "object", "properties": {
            "data": {"type": "array", "items": ref("ProductProfile")}
        }},
    ]
}


# ---------------------------------------------------------------------------
# 示例数据
# ---------------------------------------------------------------------------
LOAN_PENDING = {
    "loanId": 5001, "contractId": 10001, "loanNo": "LN-10001",
    "status": "PENDING_DISBURSEMENT", "principal": 1230000, "feeAmount": 0,
    "balance": 1230000, "disbursedAt": None, "version": 0,
}
LOAN_ACTIVE = {
    **LOAN_PENDING, "status": "ACTIVE", "balance": 1230000,
    "disbursedAt": "2026-11-28T03:00:00Z", "version": 2,
}
PLAN_ZERO = {
    "planId": 8001, "loanId": 5001, "method": "EQUAL_PAYMENT",
    "installmentCount": 3, "firstDueDate": "2026-12-10", "bonusMonth": None,
    "monthlyRateBp": 0, "productCode": "AUTO_LOAN_36_0", "productVersion": 1,
    "totalAmountDue": 1230000, "totalPrincipalDue": 1230000, "totalInterestDue": 0,
}
PLAN_INTEREST = {
    "planId": 8002, "loanId": 5002, "method": "EQUAL_PAYMENT",
    "installmentCount": 12, "firstDueDate": "2026-12-10", "bonusMonth": None,
    "monthlyRateBp": 200, "productCode": "INSTALMENT_36_200", "productVersion": 1,
    "totalAmountDue": 1395693, "totalPrincipalDue": 1230000, "totalInterestDue": 165693,
}
ITEMS_DUE = {
    "loanId": 5001,
    "items": [
        {"periodNo": 1, "dueDate": "2026-12-10", "amountDue": 410000,
         "principalDue": 410000, "interestDue": 0, "status": "DUE", "paidAmount": 0},
        {"periodNo": 2, "dueDate": "2027-01-10", "amountDue": 410000,
         "principalDue": 410000, "interestDue": 0, "status": "DUE", "paidAmount": 0},
        {"periodNo": 3, "dueDate": "2027-02-10", "amountDue": 410000,
         "principalDue": 410000, "interestDue": 0, "status": "DUE", "paidAmount": 0},
    ],
}
ITEMS_AFTER_FIRST = {
    "loanId": 5001,
    "items": [
        {"periodNo": 1, "dueDate": "2026-12-10", "amountDue": 410000,
         "principalDue": 410000, "interestDue": 0, "status": "PAID", "paidAmount": 410000},
        {"periodNo": 2, "dueDate": "2027-01-10", "amountDue": 410000,
         "principalDue": 410000, "interestDue": 0, "status": "DUE", "paidAmount": 0},
        {"periodNo": 3, "dueDate": "2027-02-10", "amountDue": 410000,
         "principalDue": 410000, "interestDue": 0, "status": "DUE", "paidAmount": 0},
    ],
}
DISBURSEMENT = {
    "disbursementId": 9001, "loanId": 5001, "contractId": 10001, "status": "PAID",
    "amount": 1230000, "currency": "JPY",
    "instructionRef": "INS-5001-DEALER-ACCT-001",
    "paidAt": "2026-11-28T03:00:00Z", "loanStatus": "ACTIVE",
}
PAYMENT_ALLOCATED = {
    "paymentId": 10001, "paymentRef": "PM-20261128-001", "loanId": 5001,
    "status": "ALLOCATED", "amount": 410000, "allocatedFee": 0,
    "allocatedInterest": 0, "allocatedPrincipal": 410000,
    "loanBalanceAfter": 820000, "loanStatusAfter": "ACTIVE",
}
PAYMENT_UNMATCHED = {
    "paymentId": 10002, "paymentRef": "PM-UNMATCHED-001", "loanId": 0,
    "status": "UNMATCHED", "amount": 50000, "allocatedFee": 0,
    "allocatedInterest": 0, "allocatedPrincipal": 0,
    "loanBalanceAfter": 0, "loanStatusAfter": "UNMATCHED",
}
PAYMENT_GET = {
    "paymentId": 10001, "paymentRef": "PM-20261128-001", "loanId": 0,
    "status": "ALLOCATED", "amount": 410000, "allocatedFee": 0,
    "allocatedInterest": 0, "allocatedPrincipal": 0,
    "loanBalanceAfter": 0, "loanStatusAfter": "ALLOCATED",
}
QUOTE_EARLY = {
    "loanId": 5001, "loanStatus": "ACTIVE", "remainingPrincipal": 820000,
    "waiverAmount": 20000, "settlementFee": 10000, "feeOutstanding": 0, "quote": 810000,
}
SETTLE_EARLY = {
    "loanId": 5001, "status": "EARLY_SETTLED", "settlementType": "EARLY",
    "quote": 810000, "waiverAmount": 20000, "settlementFee": 10000,
    "balanceAfter": 0, "settledAt": "2026-11-28T04:00:00Z",
}
SETTLE_NORMAL = {
    "loanId": 5001, "status": "SETTLED", "settlementType": "NORMAL",
    "quote": 0, "waiverAmount": 0, "settlementFee": 0,
    "balanceAfter": 0, "settledAt": "2026-11-28T04:00:00Z",
}
RECON = {
    "loanBalance": 0, "ledgerBalance": 0, "outstandingBalance": 0,
    "scheduleOutstanding": 0, "feeOutstanding": 0, "consistent": True,
}
PRODUCT_V1 = {
    "productCode": "INSTALMENT_36_200", "productVersion": 1,
    "productName": "个品分期 36 期 月息 2.0%", "scheme": "INSTALLMENT_CREDIT",
    "termCount": 36, "rateBp": 200, "bonusMonth": None, "waiverCapBp": None,
    "effectiveFrom": "2026-01-01", "effectiveTo": None, "enabled": True, "lockVersion": 0,
}
PRODUCT_V1_RETIRED = {
    **PRODUCT_V1, "effectiveTo": "2026-11-28", "enabled": False, "lockVersion": 1,
}
PRODUCT_V2 = {
    **PRODUCT_V1, "productVersion": 2, "productName": "个品分期 36 期 月息 1.0%",
    "rateBp": 100, "effectiveFrom": "2026-11-28", "lockVersion": 0,
}
PRODUCT_CREATED = {
    **PRODUCT_V1, "productCode": "DEMO_36_200",
    "productName": "演示档位 36 期 月息 2.0%",
}
JOB_RESULT = {
    "jobName": "schedule-due-rollup", "bizDate": "2026-11-28",
    "dueCount": 3, "overdueCount": 0,
}
HEALTH = {"service": "retail-core", "status": "UP"}
WHOAMI = {
    "service": "retail-core", "subject": "staff-1001", "subjectType": "EMPLOYEE",
    "roles": ["OPERATIONS"], "traceId": TRACE,
}


# ---------------------------------------------------------------------------
# paths
# ---------------------------------------------------------------------------
PATHS: dict = {}

PATHS["/api/health"] = {
    "get": op(
        "健康检查", "健康检查",
        "免鉴权端点；返回服务名与 UP 状态。用于进程存活与走网关冒烟。",
        "health",
        merge(
            ok_resp(ref("HealthView"), HEALTH),
            sys_resp(),
        ),
        parameters=[TRACE_PARAM],
    )
}
PATHS["/api/health"]["get"]["security"] = []

PATHS["/api/whoami"] = {
    "get": op(
        "健康检查", "当前员工主体",
        "验证员工 JWT 三道关并回显验签结果（subject / subjectType / roles / traceId）。",
        "whoami",
        merge(
            ok_resp(ref("WhoamiView"), WHOAMI),
            auth_resp(),
            sys_resp(),
        ),
        parameters=[TRACE_PARAM],
    )
}

PATHS["/api/loans"] = {
    "post": op(
        "债权", "建立债权",
        "合同生效后建立债权。初始状态 `PENDING_DISBURSEMENT`，初始余额 = principal + feeAmount；"
        "`uk_loan_contract` 保证一份合同只建一笔债权。\n\n"
        "权限点：`DISBURSEMENT_EXEC`。",
        "createLoan",
        merge(
            ok_resp(ref("LoanView"), LOAN_PENDING),
            error_resp(400, "参数错误", {
                "invalid": ex("contractId / principal 非法", fail(
                    "RF_VALID_001", "contractId 必须为正整数业务主键",
                    {"contractId": "0"})),
            }),
            error_resp(409, "重复请求", {
                "duplicate": ex("同一合同已存在债权", fail(
                    "RF_CONFLICT_001", "该合同已存在债权记录（uk_loan_contract）")),
            }),
            auth_resp("DISBURSEMENT_EXEC"),
            sys_resp(),
        ),
        parameters=[TRACE_PARAM],
        request_body=body("CreateLoanRequest", {
            "success": ex("建债权（本金 1,230,000 円）", {
                "contractId": 10001, "principal": 1230000, "feeAmount": 0}),
        }),
        permission="DISBURSEMENT_EXEC",
    )
}

PATHS["/api/loans/{id}"] = {
    "get": op(
        "债权", "查询债权",
        "返回债权主信息（状态、本金、费用、余额、代付时点、乐观锁版本）。"
        "不返回应计利息明细与结清流水。",
        "getLoan",
        merge(
            ok_resp(ref("LoanView"), LOAN_ACTIVE),
            error_resp(400, "参数错误", {
                "notFound": ex("债权不存在", fail(
                    "RF_VALID_001", "债权不存在", {"loanId": "5001"})),
            }),
            auth_resp(),
            sys_resp(),
        ),
        parameters=[path_param("id", "债权 ID"), TRACE_PARAM],
    )
}

PATHS["/api/loans/{id}/balance"] = {
    "get": op(
        "债权", "查询债权余额",
        "返回 `core.loans.balance` 与债权状态；余额口径见 INV-2（本金 + 费用 + 应计利息 − 核销 − 豁免）。",
        "getLoanBalance",
        merge(
            ok_resp(ref("BalanceView"), {"loanId": 5001, "balance": 820000, "status": "ACTIVE"}),
            error_resp(400, "参数错误", {
                "notFound": ex("债权不存在", fail(
                    "RF_VALID_001", "债权不存在", {"loanId": "5001"})),
            }),
            auth_resp(),
            sys_resp(),
        ),
        parameters=[path_param("id", "债权 ID"), TRACE_PARAM],
    )
}

PATHS["/api/repayment-plans"] = {
    "post": op(
        "还款计划", "生成还款计划（计息）",
        "按还款方式生成期次；指定 `productCode` 时按「授信覆盖 > 产品档位」合成生效参数，"
        "未指定时走纯入参模式。\n\n"
        "同一本地事务内：锁债权 → 写计划/期次 → Σ interestDue 计入 `interest_accrued` 与 `balance` → "
        "乐观锁更新债权。一份债权只允许一个计划版本（`uk_plan_loan_version`）。\n\n"
        "计息公式：`期次利息 = floor(期初剩余本金 × rate_bp ÷ 10000)`；"
        "计划生成后把全期利息一次性计入应计利息，不做逐日计提。\n\n"
        "权限点：`CONTRACT_ISSUE`。",
        "generateRepaymentPlan",
        merge(
            ok_resp(ref("PlanView"), PLAN_ZERO, "生成成功（零息 3 期）", {
                "interestBearing": ("生成成功（2.0% 12 期）", PLAN_INTEREST),
            }),
            error_resp(400, "参数错误", {
                "missingDueDate": ex("firstDueDate 缺失", fail(
                    "RF_VALID_001", "firstDueDate 必填（yyyy-MM-dd）")),
                "invalidMethod": ex("method 非法", fail(
                    "RF_VALID_001",
                    "method 必须是 EQUAL_PAYMENT / EQUAL_PRINCIPAL / BONUS_PAYMENT / LUMP_SUM")),
                "invalidTerms": ex("期数/利率/奖金月非法", fail(
                    "RF_VALID_001", "期次必须为正整数")),
            }),
            error_resp(409, "重复计划", {
                "duplicate": ex("该债权已有计划", fail(
                    "RF_CONFLICT_001", "该债权已存在还款计划（uk_plan_loan_version）")),
            }),
            auth_resp("CONTRACT_ISSUE"),
            sys_resp(),
        ),
        parameters=[TRACE_PARAM],
        request_body=body("GeneratePlanRequest", {
            "zeroRate": ex("零息 3 期", {
                "loanId": 5001, "method": "EQUAL_PAYMENT", "installmentCount": 3,
                "firstDueDate": "2026-12-10", "monthlyRateBp": 0,
                "bonusMonth": None, "productCode": "AUTO_LOAN_36_0"}),
            "interestBearing": ex("2.0% 12 期", {
                "loanId": 5002, "method": "EQUAL_PAYMENT", "installmentCount": 12,
                "firstDueDate": "2026-12-10", "monthlyRateBp": 200,
                "bonusMonth": None, "productCode": "INSTALMENT_36_200"}),
        }),
        permission="CONTRACT_ISSUE",
    ),
    "get": op(
        "还款计划", "查询还款计划期次",
        "先按 `loanId` 解析 `planId`，再按 `planId` 查期次（`plan_id` 与 `loan_id` 是两个独立序列）。"
        "返回期次的本金/利息拆分、状态与已核销金额。",
        "getRepaymentPlanItems",
        merge(
            ok_resp(ref("PlanItemsView"), ITEMS_DUE, "期次列表（首期未核销）", {
                "afterFirstPayment": ("首期核销后", ITEMS_AFTER_FIRST),
            }),
            error_resp(400, "参数错误", {
                "noPlan": ex("该债权尚无还款计划", fail(
                    "RF_VALID_001", "该债权尚无还款计划", {"loanId": "5001"})),
            }),
            auth_resp(),
            sys_resp(),
        ),
        parameters=[
            query_param("loanId", "债权 ID", {"type": "integer", "format": "int64"}, True),
            TRACE_PARAM,
        ],
    ),
}

PATHS["/api/disbursements"] = {
    "post": op(
        "代付", "执行代付",
        "代付编排：`Idempotency-Key` → 合同/审查前置校验（fail-closed）→ 本地事务。\n\n"
        "本地事务内：锁债权 → `PENDING_DISBURSEMENT / DISBURSE_FAILED → DISBURSING` → "
        "调银行适配器 → 插 `core.disbursements` → `DISBURSING → ACTIVE` → 更新债权 → "
        "写 `DISBURSEMENT_PAID` outbox。\n\n"
        "前置校验任一「查不到 / 超时 / 非 2xx / code != OK / allowed != true」一律拒绝代付，"
        "UNKNOWN 不视为通过。\n\n"
        "权限点：`DISBURSEMENT_EXEC`。\n\n"
        "**幂等**：`Idempotency-Key` + `uk_disb_idempotency` + `uk_disb_loan` 三层。",
        "executeDisbursement",
        merge(
            ok_resp(ref("DisbursementView"), DISBURSEMENT),
            error_resp(400, "参数错误", {
                "missingKey": ex("缺少 Idempotency-Key", fail(
                    "RF_VALID_001", "Idempotency-Key 必填（R09 §2）")),
                "contractMismatch": ex("loanId 与 contractId 不匹配", fail(
                    "RF_VALID_001", "loanId 与 contractId 不匹配",
                    {"loanContractId": "10001", "requestContractId": "10002"})),
            }),
            error_resp(409, "重复代付 / 并发冲突", {
                "duplicate": ex("重复代付", fail(
                    "RF_LOAN_002", "重复代付（INV-1：一份合同只允许一次成功代付）",
                    {"loanStatus": "ACTIVE"})),
                "concurrent": ex("债权乐观锁冲突", fail(
                    "RF_CONFLICT_001", "债权并发更新冲突（乐观锁 version）")),
            }),
            error_resp(502, "代付前置校验失败", {
                "gateDenied": ex("合同/审查校验未通过", fail(
                    "RF_LOAN_001", "代付门禁未全过，拒绝代付（G8 fail-closed）",
                    {"reasonCode": "CONTRACT_GATE_DENIED"})),
            }),
            error_resp(500, "银行未受理", {
                "bankRejected": ex("银行代付未受理", fail(
                    "RF_SYS_001", "银行代付未受理",
                    {"instructionRef": "INS-5001-DEALER-ACCT-001",
                     "reason": "BANK_UNREACHABLE:TIMEOUT"})),
            }),
            auth_resp("DISBURSEMENT_EXEC"),
        ),
        parameters=[IDEM_PARAM, TRACE_PARAM],
        request_body=body("DisbursementRequest", {
            "success": ex("代付给销售店", {
                "loanId": 5001, "contractId": 10001, "underwritingCaseId": 7001,
                "payeeAccountRef": "DEALER-ACCT-001"}),
        }),
        permission="DISBURSEMENT_EXEC",
        idempotent=True,
    )
}

PATHS["/api/payments"] = {
    "post": op(
        "收款核销", "入金录入与核销",
        "入金入账 + 核销。`loanId <= 0` 或缺省 → `UNMATCHED`，走人工匹配，不自动分摊。\n\n"
        "核销顺序：债权级费用 → 期次升序 → 期内先利息后本金；`OPEN` 未到期不可核销；"
        "拆完仍有剩余 → 超额入金 `RF_PAY_002`，不自动分摊。\n\n"
        "核销、余额更新、期次状态、`REPAYMENT_RECEIVED` outbox 在同一本地事务；"
        "事务尾做 INV-2 三口径对拍。\n\n"
        "权限点：`PAYMENT_ALLOCATE`。\n\n"
        "**幂等**：`Idempotency-Key` + `uk_payment_account_txn` + `uk_alloc_payment`。",
        "allocatePayment",
        merge(
            ok_resp(ref("PaymentView"), PAYMENT_ALLOCATED, "核销成功（ALLOCATED）", {
                "unmatched": ("转人工匹配（UNMATCHED）", PAYMENT_UNMATCHED),
            }),
            error_resp(400, "参数错误 / 状态不允许", {
                "invalid": ex("必填字段或金额/渠道非法", fail(
                    "RF_VALID_001",
                    "channel 必须是 DIRECT_DEBIT / PAYMENT_SLIP / CONVENIENCE_STORE / BANK_TRANSFER")),
                "loanState": ex("债权状态不允许核销", fail(
                    "RF_VALID_002", "债权状态不允许入金核销", {"loanStatus": "SETTLED"})),
            }),
            error_resp(409, "并发冲突", {
                "idempotency": ex("同一幂等键不同请求体", fail(
                    "RF_CONFLICT_001", "幂等键冲突 / 重复请求")),
                "concurrent": ex("期次/债权乐观锁冲突", fail(
                    "RF_CONFLICT_001", "期次并发更新冲突（乐观锁 version）",
                    {"itemId": "11001"})),
            }),
            error_resp(422, "入金无法核销 / 超额", {
                "duplicatePayment": ex("重复入金明细", fail(
                    "RF_PAY_001", "入金明细重复（uk_payment_account_txn，X-6 去重）")),
                "noPlan": ex("债权尚无还款计划", fail(
                    "RF_PAY_001", "债权尚无还款计划，入金无法核销", {"loanId": "5001"})),
                "overpay": ex("超额入金", fail(
                    "RF_PAY_002", "超额入金（X-8：转工单/退款，不自动分摊）",
                    {"remainder": "10000"})),
            }),
            auth_resp("PAYMENT_ALLOCATE"),
            sys_resp(),
        ),
        parameters=[IDEM_PARAM, TRACE_PARAM],
        request_body=body("PaymentRequest", {
            "allocated": ex("按期核销", {
                "loanId": 5001, "accountRef": "ACCT-001",
                "transactionId": "TX-20261128-001", "amount": 410000,
                "channel": "BANK_TRANSFER", "paymentRef": "PM-20261128-001"}),
            "unmatched": ex("无法匹配合同 → UNMATCHED", {
                "loanId": 0, "accountRef": "ACCT-001",
                "transactionId": "TX-20261128-999", "amount": 50000,
                "channel": "BANK_TRANSFER"}),
        }),
        permission="PAYMENT_ALLOCATE",
        idempotent=True,
    )
}

PATHS["/api/payments/{id}/manual-match"] = {
    "post": op(
        "收款核销", "人工匹配 UNMATCHED 入金",
        "仅 `UNMATCHED` 状态可人工匹配；匹配后在本地事务内执行核销。"
        "一债权一笔入金只核销一次（`uk_alloc_payment`）。\n\n"
        "权限点：`PAYMENT_MANUAL_MATCH`。",
        "manualMatchPayment",
        merge(
            ok_resp(ref("PaymentView"), PAYMENT_ALLOCATED),
            error_resp(400, "参数错误 / 状态不允许", {
                "notFound": ex("入金不存在", fail(
                    "RF_VALID_001", "入金记录不存在", {"paymentId": "10002"})),
                "invalidLoan": ex("loanId 非法", fail(
                    "RF_VALID_001", "loanId 必须为正整数业务主键")),
                "notUnmatched": ex("入金状态不是 UNMATCHED", fail(
                    "RF_VALID_002", "入金状态不允许人工匹配（仅 UNMATCHED）",
                    {"status": "ALLOCATED"})),
            }),
            error_resp(409, "并发冲突", {
                "duplicate": ex("重复核销", fail(
                    "RF_CONFLICT_001", "重复核销（uk_alloc_payment：一笔入金只核销一次）")),
            }),
            error_resp(422, "超额入金", {
                "overpay": ex("超额入金", fail(
                    "RF_PAY_002", "超额入金（X-8：转工单/退款，不自动分摊）",
                    {"remainder": "10000"})),
            }),
            auth_resp("PAYMENT_MANUAL_MATCH"),
            sys_resp(),
        ),
        parameters=[path_param("id", "入金 ID"), TRACE_PARAM],
        request_body=body("ManualMatchRequest", {
            "success": ex("匹配到债权 5001", {"loanId": 5001}),
        }),
        permission="PAYMENT_MANUAL_MATCH",
    )
}

PATHS["/api/payments/{id}"] = {
    "get": op(
        "收款核销", "查询入金",
        "返回入金主信息。**当前实现**：`loanId`、`allocatedFee/Interest/Principal`、"
        "`loanBalanceAfter` 以 0 占位，`loanStatusAfter` 回填入金状态；"
        "核销拆分请查还款计划或对拍端点。",
        "getPayment",
        merge(
            ok_resp(ref("PaymentView"), PAYMENT_GET),
            error_resp(400, "参数错误", {
                "notFound": ex("入金不存在", fail(
                    "RF_VALID_001", "入金记录不存在", {"paymentId": "10001"})),
            }),
            auth_resp(),
            sys_resp(),
        ),
        parameters=[path_param("id", "入金 ID"), TRACE_PARAM],
    )
}

PATHS["/api/loans/{id}/settlement-quote"] = {
    "get": op(
        "结清与对拍", "提前结清试算",
        "C-10 公式：`提前结清金额 = 剩余本金 − 减免额 + 事务手续费`。\n\n"
        "返回剩余本金、减免额、手续费、未核销费用与报价。"
        "减免额不得超过剩余本金；`waiverCapBp` 当前只存在产品档位，结清时未强制校验。",
        "getSettlementQuote",
        merge(
            ok_resp(ref("SettlementQuoteView"), QUOTE_EARLY),
            error_resp(400, "参数错误", {
                "notFound": ex("债权不存在", fail(
                    "RF_VALID_001", "债权不存在", {"loanId": "5001"})),
                "noPlan": ex("债权尚无还款计划", fail(
                    "RF_VALID_002", "债权尚无还款计划，无法结清", {"loanId": "5001"})),
                "waiverTooLarge": ex("减免额超过剩余本金", fail(
                    "RF_VALID_001", "减免额不得超过剩余本金（C-10 守卫；上限待 L-12 核实）")),
            }),
            auth_resp(),
            sys_resp(),
        ),
        parameters=[
            path_param("id", "债权 ID"),
            query_param("waiverAmount", "减免额（整数日元）",
                        {"type": "integer", "format": "int64", "minimum": 0, "default": 0}),
            query_param("settlementFee", "事务手续费（整数日元）",
                        {"type": "integer", "format": "int64", "minimum": 0, "default": 0}),
            TRACE_PARAM,
        ],
    )
}

PATHS["/api/loans/{id}/settle"] = {
    "post": op(
        "结清与对拍", "正常结清 / 提前结清",
        "`NORMAL`：末回核销完成、无未清偿期次、余额精确为 0 → `SETTLED`，只做状态迁移。\n\n"
        "`EARLY`：`quote = 剩余本金 − 减免额 + 手续费`；同一本地事务内手续费计提 → "
        "结清入金核销 → 豁免未到期利息/折让本金/未核销费用 → 期次关闭 → 余额归零 → `EARLY_SETTLED`。\n\n"
        "`SETTLED` 与 `EARLY_SETTLED` 是两个不同终态（B-7）。\n\n"
        "权限点：`SETTLEMENT_CONFIRM`。\n\n"
        "**幂等**：`Idempotency-Key` + `uk_settlement_loan` + 终态。",
        "settleLoan",
        merge(
            ok_resp(ref("SettlementView"), SETTLE_EARLY, "提前结清成功", {
                "normal": ("全部结清成功（余额已为 0）", SETTLE_NORMAL),
            }),
            error_resp(400, "参数错误 / 状态不允许", {
                "invalidType": ex("type 非法", fail(
                    "RF_VALID_001", "type 必须是 NORMAL / EARLY")),
                "g10Blocked": ex("G10 未满足：存在未核销期次", fail(
                    "RF_VALID_002", "存在未核销期次，不得进入全部结清（G10：末回核销前置）",
                    {"unpaidTotal": "410000"})),
                "noUnpaid": ex("无未清偿期次，应走 NORMAL", fail(
                    "RF_VALID_002", "无未清偿期次，应走全部结清（NORMAL）而非提前结清")),
                "balanceNotZero": ex("余额未精确为 0", fail(
                    "RF_VALID_002", "余额未精确为 0，不得进入结清终态（G10）",
                    {"balance": "10000"})),
            }),
            error_resp(409, "重复结清 / 并发冲突", {
                "duplicate": ex("重复结清", fail(
                    "RF_CONFLICT_001", "重复结清（uk_settlement_loan：一债权一次结清调整）")),
                "concurrent": ex("债权乐观锁冲突", fail(
                    "RF_CONFLICT_001", "债权并发更新冲突（乐观锁 version）")),
            }),
            auth_resp("SETTLEMENT_CONFIRM"),
            sys_resp(),
        ),
        parameters=[path_param("id", "债权 ID"), IDEM_PARAM, TRACE_PARAM],
        request_body=body("SettlementRequest", {
            "early": ex("提前结清（减免 20,000 / 手续费 10,000）", {
                "type": "EARLY", "waiverAmount": 20000, "settlementFee": 10000}),
            "normal": ex("全部结清", {
                "type": "NORMAL", "waiverAmount": 0, "settlementFee": 0}),
        }),
        permission="SETTLEMENT_CONFIRM",
        idempotent=True,
    )
}

PATHS["/api/loans/{id}/reconciliation"] = {
    "get": op(
        "结清与对拍", "余额三口径对拍",
        "INV-2 精确相等：\n"
        "- `loanBalance`：`core.loans.balance` 存量；\n"
        "- `ledgerBalance`：本金 + 费用 + 应计利息 − Σ核销 − Σ豁免 + Σ结清手续费；\n"
        "- `outstandingBalance`：Σ期次未清偿 + 费用未清偿。\n\n"
        "`consistent=false` 表示账实不符，核销/结清事务内会直接 `RF_SYS_001` 回滚。",
        "getReconciliation",
        merge(
            ok_resp(ref("Reconciliation"), RECON),
            error_resp(400, "参数错误", {
                "notFound": ex("债权不存在", fail(
                    "RF_VALID_001", "债权不存在", {"loanId": "5001"})),
            }),
            auth_resp(),
            sys_resp(),
        ),
        parameters=[path_param("id", "债权 ID"), TRACE_PARAM],
    )
}

PATHS["/api/financial-products"] = {
    "post": op(
        "产品档位", "新建产品档位",
        "新建档位（首个版本）。`uk_fin_product_ver` 保证同码同版本唯一；"
        "只定义还款口径（期数 / 月利率 / 奖金月 / 结清减免上限），费用与费率属合同条款。\n\n"
        "读侧走 Caffeine L1 + Redis L2；改配置不回改历史计划。\n\n"
        "权限点：`CONFIG_MAINTAIN`。",
        "createFinancialProduct",
        merge(
            ok_resp(ref("ProductProfile"), PRODUCT_CREATED),
            error_resp(400, "参数错误", {
                "invalid": ex("档位参数非法", fail(
                    "RF_VALID_001", "期次必须为正整数")),
            }),
            error_resp(409, "档位已存在", {
                "duplicate": ex("同码档位已存在", fail(
                    "RF_CONFLICT_001", "该产品档位已存在，请走改版接口",
                    {"productCode": "DEMO_36_200"})),
            }),
            auth_resp("CONFIG_MAINTAIN"),
            sys_resp(),
        ),
        parameters=[TRACE_PARAM],
        request_body=body("ProductCreateRequest", {
            "success": ex("新建 36 期 月息 2.0% 档位", {
                "productCode": "DEMO_36_200", "productName": "演示档位 36 期 月息 2.0%",
                "scheme": "INSTALLMENT_CREDIT", "termCount": 36, "rateBp": 200,
                "bonusMonth": None, "waiverCapBp": None, "effectiveFrom": "2026-01-01"}),
        }),
        permission="CONFIG_MAINTAIN",
    )
}

PATHS["/api/financial-products/{code}"] = {
    "put": op(
        "产品档位", "产品档位改版",
        "旧版本封口停用 + 新版本插入（同一事务），提交后失效两级缓存。"
        "不做原地覆盖，旧版本行保留供变更审计。\n\n"
        "路径 `code` 是权威档位码；请求体不需要再带 `productCode`。"
        "并发改配置由旧版本行 `version` 乐观锁兜底。\n\n"
        "**当前实现**：新版本 `effectiveFrom` 固定为当日 JST；请求体里的 `effectiveFrom` 不参与 revise。\n\n"
        "权限点：`CONFIG_MAINTAIN`。",
        "reviseFinancialProduct",
        merge(
            ok_resp(ref("ProductProfile"), PRODUCT_V2),
            error_resp(400, "参数错误", {
                "notFound": ex("档位不存在或未生效", fail(
                    "RF_VALID_001", "产品档位不存在或未生效",
                    {"productCode": "INSTALMENT_36_200"})),
                "invalid": ex("档位参数非法", fail(
                    "RF_VALID_001", "减免上限（千分比）必须在 0-1000")),
            }),
            error_resp(409, "并发修改冲突", {
                "concurrent": ex("乐观锁冲突", fail(
                    "RF_CONFLICT_001", "产品档位并发修改冲突（乐观锁 version）",
                    {"productCode": "INSTALMENT_36_200"})),
            }),
            auth_resp("CONFIG_MAINTAIN"),
            sys_resp(),
        ),
        parameters=[path_param("code", "产品档位码", {"type": "string"}), TRACE_PARAM],
        request_body=body("ProductReviseRequest", {
            "success": ex("改版为月息 1.0%，新版本 2", {
                "productName": "个品分期 36 期 月息 1.0%", "scheme": "INSTALLMENT_CREDIT",
                "termCount": 36, "rateBp": 100, "bonusMonth": None,
                "waiverCapBp": None}),
        }),
        permission="CONFIG_MAINTAIN",
    ),
    "get": op(
        "产品档位", "当前生效档位",
        "按业务日（JST）取当前生效档位；读路径：Caffeine L1 → Redis L2 → DB，"
        "Redis 不可用时降级直读 DB。",
        "getFinancialProduct",
        merge(
            ok_resp(ref("ProductProfile"), PRODUCT_V1),
            error_resp(400, "参数错误", {
                "notFound": ex("档位不存在或未生效", fail(
                    "RF_VALID_001", "产品档位不存在或未生效",
                    {"productCode": "INSTALMENT_36_200"})),
            }),
            auth_resp(),
            sys_resp(),
        ),
        parameters=[path_param("code", "产品档位码", {"type": "string"}), TRACE_PARAM],
    ),
}

PATHS["/api/financial-products/{code}/versions"] = {
    "get": op(
        "产品档位", "同码全部版本",
        "变更审计视图；不走缓存。返回同码所有版本（含已封口停用的旧版本）。",
        "listFinancialProductVersions",
        merge(
            ok_resp({"type": "array", "items": ref("ProductProfile")},
                    [PRODUCT_V1_RETIRED, PRODUCT_V2]),
            auth_resp(),
            sys_resp(),
        ),
        parameters=[path_param("code", "产品档位码", {"type": "string"}), TRACE_PARAM],
    )
}

PATHS["/api/jobs/{jobName}"] = {
    "post": op(
        "批处理", "触发到期滚转作业",
        "本服务只实现 `schedule-due-rollup`：\n"
        "- `OPEN` 且 `due_date <= bizDate` → `DUE`；\n"
        "- `DUE` 且 `due_date < bizDate` → `OVERDUE`。\n\n"
        "`core.batch_runs` 以 `(job_name, biz_date)` 唯一留痕，同日重跑幂等；"
        "`PARTIAL` 不自动转 `OVERDUE`。\n\n"
        "权限点：`JOB_TRIGGER`。",
        "triggerScheduleDueRollup",
        merge(
            ok_resp(ref("JobResult"), JOB_RESULT),
            error_resp(400, "参数错误", {
                "unknownJob": ex("未知作业名", fail(
                    "RF_VALID_001", "未知作业名（本服务实现：schedule-due-rollup）",
                    {"jobName": "unknown"})),
                "badDate": ex("bizDate 格式非法", fail(
                    "RF_VALID_001", "bizDate 必须是 yyyy-MM-dd")),
            }),
            auth_resp("JOB_TRIGGER"),
            sys_resp(),
        ),
        parameters=[
            path_param("jobName", "作业名；本服务仅支持 schedule-due-rollup",
                       {"type": "string", "enum": ["schedule-due-rollup"]}),
            TRACE_PARAM,
        ],
        request_body=body("JobRequest", {
            "today": ex("今日到期滚转", {"bizDate": "2026-11-28"}),
            "backfill": ex("补跑指定业务日", {"bizDate": "2026-11-30"}),
        }),
        permission="JOB_TRIGGER",
    )
}


# 给 path/query 参数补上入参示例（GET 端点没有 requestBody，示例落在 parameters 上）
for _path, _methods in PATHS.items():
    for _method, _operation in _methods.items():
        for _p in _operation.get("parameters", []):
            if "$ref" in _p:
                continue
            _name = _p.get("name")
            _schema = _p.setdefault("schema", {})
            if _name == "id":
                _schema.setdefault("example",
                                   10002 if "manual-match" in _path
                                   else (10001 if "/payments/" in _path else 5001))
            elif _name == "code":
                _schema.setdefault("example", "INSTALMENT_36_200")
            elif _name == "jobName":
                _schema.setdefault("example", "schedule-due-rollup")
            elif _name == "loanId":
                _schema.setdefault("example", 5001)
            elif _name == "waiverAmount":
                _schema.setdefault("example", 20000)
            elif _name == "settlementFee":
                _schema.setdefault("example", 10000)


# ---------------------------------------------------------------------------
# 组装 spec
# ---------------------------------------------------------------------------
SPEC = {
    "openapi": "3.0.3",
    "info": {
        "title": "新车零售金融核心系统 · retail-core API",
        "version": "1.0.0",
        "description": (
            "核心账务服务（端口 8090）的 HTTP 接口文档：债权、还款计划、代付、入金核销、"
            "结清、余额对拍、产品档位与到期滚转批处理。\n\n"
            "**统一响应**：`ApiResponse<T>`，成功 `code=OK`，失败为 `RF_*` 错误码；"
            "`traceId` 与响应头 `X-Request-Id` 同值。\n\n"
            "**金额**：一律整数日元（`int64`），禁止浮点。**日期**：`yyyy-MM-dd`；"
            "**时间**：ISO-8601 `date-time`（全系统 JST 业务日）。\n\n"
            "**认证**：`/api/health` 免鉴权；其余 `/api/**` 需要员工 JWT"
            "（`Authorization: Bearer <token>`）。写接口的权限点见各 operation 的 `x-permission`。\n\n"
            "**幂等**：`POST /api/disbursements`、`POST /api/payments`、"
            "`POST /api/loans/{id}/settle` 必须带 `Idempotency-Key`；"
            "同键同请求体返回首次结果，同键不同请求体返回 `RF_CONFLICT_001`。\n\n"
            "**说明**：本规范是静态 OpenAPI 3.0.3，由 `gen_openapi.py` 从当前代码生成；"
            "不引入 springdoc 运行时依赖。"
        ),
    },
    "servers": [
        {"url": "http://localhost:18081", "description": "本地网关（推荐；统一鉴权/路由/限流）"},
        {"url": "http://localhost:8090", "description": "retail-core 直连（仅本地调试）"},
    ],
    "tags": [
        {"name": "健康检查", "description": "health / whoami"},
        {"name": "债权", "description": "债权建立与查询"},
        {"name": "还款计划", "description": "计息与期次查询"},
        {"name": "代付", "description": "G8 前置校验、银行适配与状态迁移"},
        {"name": "收款核销", "description": "入金录入、核销、人工匹配"},
        {"name": "结清与对拍", "description": "正常/提前结清、结算试算、INV-2 对拍"},
        {"name": "产品档位", "description": "产品档位目录、版本化与缓存"},
        {"name": "批处理", "description": "到期滚转作业"},
    ],
    "security": [{"bearerAuth": []}],
    "paths": PATHS,
    "components": {
        "securitySchemes": {
            "bearerAuth": {
                "type": "http",
                "scheme": "bearer",
                "bearerFormat": "JWT",
                "description": "员工 JWT（HS256）；`Authorization: Bearer <token>`。"
                               "客户 token 不能通过员工侧验签。",
            }
        },
        "parameters": {
            "IdempotencyKey": {
                "name": "Idempotency-Key",
                "in": "header",
                "required": True,
                "description": "幂等键（全局唯一作用域）。同键同请求体 → 重放首次结果；"
                               "同键不同请求体 → RF_CONFLICT_001。",
                "schema": {"type": "string", "maxLength": 64, "example": "idem-20261128-001"},
            },
            "TraceIdHeader": {
                "name": "X-Request-Id",
                "in": "header",
                "required": False,
                "description": "可选请求追踪 ID；省略时服务自动生成，并在响应头回显。",
                "schema": {"type": "string", "example": TRACE},
            },
        },
        "schemas": SCHEMAS,
    },
}


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------
class NoAliasDumper(yaml.SafeDumper):
    """重复对象不输出 &id/*id，保证 YAML 可读性（示例/参数复用是刻意的）。"""

    def ignore_aliases(self, data):
        return True


def dump() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    yaml_text = yaml.dump(
        SPEC, Dumper=NoAliasDumper, allow_unicode=True, sort_keys=False,
        width=120, default_flow_style=False,
    )
    YAML_OUT.write_text(yaml_text, encoding="utf-8", newline="\n")
    JSON_OUT.write_text(
        json.dumps(SPEC, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8", newline="\n",
    )

    spec_json = json.dumps(SPEC, ensure_ascii=False).replace("</", "<\\/")
    html = f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>retail-core API · Swagger UI</title>
<link rel="stylesheet" href="{ASSETS_REL}/swagger-ui.css">
<style>
  body {{ margin: 0; background: #f7f9fc; }}
  .rf-banner {{
    padding: 14px 22px; background: #0f172a; color: #e2e8f0;
    font-family: "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif;
    display: flex; flex-wrap: wrap; gap: 14px; align-items: baseline;
  }}
  .rf-banner b {{ font-size: 16px; }}
  .rf-banner span {{ color: #94a3b8; font-size: 13px; }}
  .rf-banner a {{ color: #7dd3fc; text-decoration: none; font-size: 13px; }}
  .rf-banner a:hover {{ text-decoration: underline; }}
  #rf-error {{
    display: none; margin: 18px 22px; padding: 12px 16px;
    border: 1px solid #fdba74; background: #fff7ed; color: #9a3412;
    border-radius: 10px; font-family: sans-serif;
  }}
  body.rf-failed #rf-error {{ display: block; }}
  .swagger-ui .topbar {{ display: none; }}
</style>
</head>
<body>
<div class="rf-banner">
  <b>retail-core API</b>
  <span>核心账务 · 端口 8090 · 网关 18081</span>
  <span>金额=整数日元 · 认证=员工 JWT · 写接口=Idempotency-Key</span>
  <a href="openapi.yaml">openapi.yaml</a>
  <a href="openapi.json">openapi.json</a>
  <a href="README.md">接口说明</a>
</div>
<div id="rf-error">Swagger UI 资源未加载。请确认 <code>{ASSETS_REL}/swagger-ui.css</code>、
<code>swagger-ui-bundle.js</code> 存在；本页内嵌了 OpenAPI spec，不需要额外 fetch。</div>
<div id="swagger-ui"></div>
<script src="{ASSETS_REL}/swagger-ui-bundle.js"></script>
<script src="{ASSETS_REL}/swagger-ui-standalone-preset.js"></script>
<script>
const spec = {spec_json};
window.onload = function () {{
  if (!window.SwaggerUIBundle) {{
    document.body.classList.add('rf-failed');
    return;
  }}
  window.ui = SwaggerUIBundle({{
    spec: spec,
    dom_id: '#swagger-ui',
    deepLinking: true,
    presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
    plugins: [SwaggerUIBundle.plugins.DownloadUrl],
    layout: 'StandaloneLayout',
    docExpansion: 'list',
    defaultModelsExpandDepth: 1,
    defaultModelExpandDepth: 1,
    displayRequestDuration: true,
    persistAuthorization: true,
    tryItOutEnabled: true,
    filter: true,
    validatorUrl: null,
    withCredentials: false
  }});
}};
</script>
</body>
</html>
"""
    HTML_OUT.write_text(html, encoding="utf-8", newline="\n")

    print(f"已生成 {YAML_OUT}")
    print(f"已生成 {JSON_OUT}")
    print(f"已生成 {HTML_OUT}")


if __name__ == "__main__":
    dump()
