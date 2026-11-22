package com.company.retail.titlerelease.application;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.titlerelease.persistence.JdbcTitleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;

/**
 * LOAN_SETTLED 消费方（BC-8，R04 §6）：解除八步的第 1–2 步——
 * 债权终态（事件即证据链①②③来源）→ 生成 {@code ReleaseOrder.CREATED}。
 *
 * <p>消费三铁律（R09 §5.4）：幂等（uk_release_loan + 已存在即返回）／不吞异常
 * （payload 缺业务主键直接抛，交给调度器计数与 DEAD）／不跨服务调用
 * （只读本地表、只写本地状态；证据锚点 loan_settled_event_id 落库）。</p>
 */
@Component
public class LoanSettledConsumer implements EventConsumer {

    static final String CONSUMER_NAME = "title-release-service";

    private static final Logger log = LoggerFactory.getLogger(LoanSettledConsumer.class);

    private final JdbcTitleRepository titleRepository;
    private final TitleReleaseApplicationService applicationService;

    public LoanSettledConsumer(JdbcTitleRepository titleRepository,
                               TitleReleaseApplicationService applicationService) {
        this.titleRepository = titleRepository;
        this.applicationService = applicationService;
    }

    @Override
    public String name() {
        return CONSUMER_NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(OutboxPublisher.LOAN_SETTLED);
    }

    @Override
    @Transactional
    public void consume(DomainEvent event) {
        Long loanId = event.longField("loanId");
        if (loanId == null || loanId <= 0) {
            // 不吞异常：缺业务主键按坏事件处理（调度器重试 3 次后 DEAD，R09 §5.4 铁律 2）
            throw new IllegalStateException("LOAN_SETTLED payload 缺 loanId（R09 §5.1 聚合键）");
        }

        // 挡板兜底：交车报告（S9/G9）登记缺失时补一条 REGISTERED 保留记录（见最终报告 W6 挡板清单）
        if (titleRepository.findByLoanId(loanId).isEmpty()) {
            titleRepository.insert(loanId, "UNKNOWN-" + loanId, "REGISTERED", "REGISTERED",
                    Timestamp.from(Instant.now()), null);
            log.warn("LOAN_SETTLED 消费：债权 {} 缺保留登记记录，已按挡板补 REGISTERED（W6 兜底）", loanId);
        }

        // 幂等：已存在订单（重复投递 / 重放）直接返回
        var existing = applicationService.createReleaseOrderFromEvent(loanId, event.eventId());
        log.info("LOAN_SETTLED 消费完成 loanId={} releaseOrderId={} status={}",
                loanId, existing.releaseOrderId(), existing.status());
    }
}
