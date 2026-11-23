package com.company.retail.externalapi.security;

import com.company.retail.externalapi.mapper.CallLogMapper;
import com.company.retail.externalapi.mapper.PartnerMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 调用留痕 MyBatis 实现（{@code integration.call_logs}，append-only）。
 * partner_id 按 partner_code 回查，未登记 partner 记 NULL。
 */
@Component
public class MyBatisCallLogStore implements CallLogStore {

    private final CallLogMapper mapper;
    private final PartnerMapper partners;

    public MyBatisCallLogStore(CallLogMapper mapper, PartnerMapper partners) {
        this.mapper = mapper;
        this.partners = partners;
    }

    @Override
    public void record(String partnerCode, String endpoint, int httpStatus, long durationMs,
                       String result, String traceId) {
        mapper.insertCallLog(resolvePartnerIdOrNull(partnerCode), truncate(endpoint), httpStatus,
                (int) Math.min(durationMs, Integer.MAX_VALUE), truncate(result), traceId);
    }

    private Long resolvePartnerIdOrNull(String partnerCode) {
        if (partnerCode == null) {
            return null;
        }
        List<Long> ids = partners.findPartnerIdsByCode(partnerCode);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String truncate(String v) {
        if (v == null) {
            return null;
        }
        return v.length() <= 128 ? v : v.substring(0, 128);
    }
}
