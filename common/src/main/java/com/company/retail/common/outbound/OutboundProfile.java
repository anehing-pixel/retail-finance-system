package com.company.retail.common.outbound;

/**
 * 出站调用的策略档（P0-7；R02 §7.4）。
 *
 * <p>档位按「失败的业务后果」划分，而不是按外部方名字划分：门禁宁可误拒、
 * 资金动作绝不重试、咨询性副本不阻断主流程。</p>
 */
public enum OutboundProfile {

    /** Feign 同步门禁（合同 / 同意 / 审查）：只读 GET，允许 1 次传输层重试。 */
    GATE,

    /** 同意门禁的原始 HTTP 兜底：Feign 已失败一次，兜底不再重试。 */
    GATE_RAW,

    /** 查询型只读通道（征信查询）：跨事务重试由用例层控制。 */
    QUERY,

    /** 写入型通道（征信报送 / 异议）：业务幂等键 + 人工重送，不自动重试。 */
    WRITE,

    /** 咨询性副本（eKYC / DMS 结果通知 / 送达）：失败不阻断主流程。 */
    ADVISORY,

    /** 资金动作（银行代付）：绝不自动重试，fail-closed + 对账兜底。 */
    FINANCIAL,

    /** DMZ 入站回调转发（external-api → title-release）：锁内不重试，上游会重推。 */
    FORWARD
}
