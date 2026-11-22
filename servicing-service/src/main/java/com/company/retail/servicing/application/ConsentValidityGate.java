package com.company.retail.servicing.application;

/**
 * 同意有效性门禁端口（INV-5 / RB-1：报送触发前必须存在未撤销同意，fail-closed）。
 *
 * <p>provider 是 customer-service（frozen 契约 {@code GET /api/consents/{id}/disbursement-gate}）；
 * 实现放 servicing 模块的 {@code com.company.retail.common.contract} 包内（与 W4 同构）——
 * 契约接口是 package-private，同包才能命名。任何异常 / 超时 / UNKNOWN 一律 false。</p>
 */
public interface ConsentValidityGate {

    /** 同意存在且未撤销才返回 true；查不到 / 超时 / 异常一律 false（fail-closed，禁缓存）。 */
    boolean isValid(long consentId);
}
