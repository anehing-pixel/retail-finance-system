package com.company.retail.externalapi.security;

import java.time.Instant;

/**
 * nonce 防重放存储（{@code integration.nonces}，唯一键 (nonce, partner_id)，R06 §integration）。
 */
public interface NonceStore {

    /**
     * 首次登记返回 true；同 (nonce, partner) 已存在（重放）返回 false。
     * 原子写入，依赖唯一键 + ON CONFLICT DO NOTHING。
     */
    boolean tryRecord(String partnerCode, String nonce);

    /** 惰性清理：删除留存期之前的过期 nonce，返回删除行数。 */
    int purgeBefore(Instant cutoff);
}
