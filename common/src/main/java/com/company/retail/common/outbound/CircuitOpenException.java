package com.company.retail.common.outbound;

/**
 * 熔断打开时抛出的明确失败（P0-7）。
 *
 * <p>调用方现有 {@code catch (Exception)} 会把它转成既有 fail-closed 语义
 * （门禁拒付 / 同意无效 / 咨询性副本失败），不新增错误码。</p>
 */
public class CircuitOpenException extends RuntimeException {

    public CircuitOpenException(String channel) {
        super("出站熔断打开: " + channel);
    }
}
