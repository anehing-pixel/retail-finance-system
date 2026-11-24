#!/usr/bin/env bash
# ============================================================================
# 本地开发密钥（仅限本地）——R11 P0-8
#
# 生产环境绝不 source 本文件：各服务 application.yml 不提供任何密钥默认值，
# 未注入即启动失败（SecretPolicy 同时拒绝过短与生产环境 dev 弱值）。
# 本地由 run-microservices.sh / scripts/dev-stack.sh 显式 source，并激活 dev
# profile 允许这些 dev-only 值通过强度校验。
#
# PII data-key / hmac-key 必须跨重启稳定：已落库的密文依赖同一把 data-key，
# 随机生成会导致历史数据无法解密。JWT / 附件签名密钥也保持固定，便于复现。
# ============================================================================
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
export RETAIL_JWT_SECRET="${RETAIL_JWT_SECRET:-dev-only-retail-staff-secret-change-me-in-prod-0123456789}"
export RETAIL_CUSTOMER_JWT_SECRET="${RETAIL_CUSTOMER_JWT_SECRET:-dev-only-retail-customer-secret-change-me-in-prod-01234}"
export RETAIL_PII_DATA_KEY="${RETAIL_PII_DATA_KEY:-dev-only-retail-pii-data-key-change-me-in-prod-012345}"
export RETAIL_PII_HMAC_KEY="${RETAIL_PII_HMAC_KEY:-dev-only-retail-pii-hmac-key-change-me-in-prod-01234}"
export RETAIL_ATTACHMENTS_TOKEN_SECRET="${RETAIL_ATTACHMENTS_TOKEN_SECRET:-dev-only-local-attachment-token-0123456789}"
