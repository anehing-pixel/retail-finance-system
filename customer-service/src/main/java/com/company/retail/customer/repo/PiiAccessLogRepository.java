package com.company.retail.customer.repo;

import com.company.retail.customer.mapper.PiiAccessLogMapper;
import org.springframework.stereotype.Repository;

/**
 * audit.pii_access_logs 追加写入（R06 §4.8：不记明文值，只记谁/何时/哪个客户/哪个字段/用途）。
 */
@Repository
public class PiiAccessLogRepository {

    private final PiiAccessLogMapper mapper;

    public PiiAccessLogRepository(PiiAccessLogMapper mapper) {
        this.mapper = mapper;
    }

    public void record(String actor, String actorSubject, long customerId,
                       String fields, String purpose, String traceId) {
        mapper.record(actor, actorSubject, customerId, fields, purpose, traceId);
    }
}
