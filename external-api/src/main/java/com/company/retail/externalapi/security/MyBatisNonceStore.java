package com.company.retail.externalapi.security;

import com.company.retail.externalapi.mapper.NonceMapper;
import com.company.retail.externalapi.mapper.PartnerMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * nonce 存储的 MyBatis 实现（{@code integration.nonces}）。
 *
 * <p>partner_id 按 partner_code 回查 {@code integration.partners}（无行则 0，
 * CONFIG 模式 dev partner 亦能落库防重放——nonce 唯一键本就按 partner 隔离）。</p>
 *
 * <p><b>防重放红线</b>：登记走 {@code INSERT ... ON CONFLICT DO NOTHING}，
 * 原子性由唯一键 (nonce, partner_id) + 该子句保证，不改成「先查后插」。</p>
 */
@Component
public class MyBatisNonceStore implements NonceStore {

    private final NonceMapper mapper;
    private final PartnerMapper partners;

    public MyBatisNonceStore(NonceMapper mapper, PartnerMapper partners) {
        this.mapper = mapper;
        this.partners = partners;
    }

    @Override
    public boolean tryRecord(String partnerCode, String nonce) {
        return mapper.insertNonce(nonce, resolvePartnerId(partnerCode)) == 1;
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        return mapper.deleteNoncesBefore(cutoff);
    }

    private long resolvePartnerId(String partnerCode) {
        List<Long> ids = partners.findPartnerIdsByCode(partnerCode);
        return ids.isEmpty() ? 0L : ids.get(0);
    }
}
