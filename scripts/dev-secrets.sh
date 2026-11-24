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
# 运行期连库角色（R11 P0-1 / G-DB-2）：本地用 V1 建的 *_dev 口令；生产必须由密钥托管注入
export RETAIL_DB_APP_PASSWORD="${RETAIL_DB_APP_PASSWORD:-retail_app_rw_dev}"
export RETAIL_DB_PII_PASSWORD="${RETAIL_DB_PII_PASSWORD:-retail_pii_rw_dev}"
export RETAIL_DB_DMZ_PASSWORD="${RETAIL_DB_DMZ_PASSWORD:-retail_dmz_rw_dev}"
# 迁移账号（G-DB-3）：本地沿用 postgres；生产必须单独注入 owner/迁移账号口令
export RETAIL_DB_MIGRATION_USER="${RETAIL_DB_MIGRATION_USER:-postgres}"
export RETAIL_DB_MIGRATION_PASSWORD="${RETAIL_DB_MIGRATION_PASSWORD:-postgres}"
