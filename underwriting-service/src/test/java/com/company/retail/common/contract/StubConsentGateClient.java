package com.company.retail.common.contract;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 同意门禁挡板（测试夹具；与冻结契约同包才能实现 package-private 接口）。
 *
 * <p>行为模式：</p>
 * <ul>
 *   <li>{@link Mode#ALLOW}：2xx + code=OK + allowed=true；</li>
 *   <li>{@link Mode#DENY}：2xx + code=OK + allowed=false（已撤回）；</li>
 *   <li>{@link Mode#NOT_OK}：HTTP 可达但 code 语义失败（模拟 4xx/5xx 应答体）；</li>
 *   <li>{@link Mode#UNAVAILABLE}：抛 RuntimeException（模拟超时/连接失败）。</li>
 * </ul>
 *
 * <p>实现注记：冻结契约的 {@code ApiResponseShape} 是<b>非静态内部类</b>（W2 缺陷），
 * 无法直接 new——这里反射用 null 外围实例构造（该类不引用外围成员，null 安全），
 * 并已登记「需要对共享区改动的事项」：应改为 {@code static class}。</p>
 */
public class StubConsentGateClient implements FeignContracts.ConsentGateClient {

    public enum Mode { ALLOW, DENY, NOT_OK, UNAVAILABLE }

    private final Map<Long, Mode> modes = new HashMap<>();
    private Mode defaultMode = Mode.ALLOW;
    public int callCount = 0;

    public StubConsentGateClient(Mode defaultMode) {
        this.defaultMode = defaultMode;
    }

    public void setMode(long consentId, Mode mode) {
        modes.put(consentId, mode);
    }

    @Override
    public FeignContracts.ApiResponseShape<FeignContracts.GateResult> check(long consentId) {
        callCount++;
        Mode mode = modes.getOrDefault(consentId, defaultMode);
        return switch (mode) {
            case ALLOW -> shape("OK", new FeignContracts.GateResult(true, "CONSENT_VALID"));
            case DENY -> shape("OK", new FeignContracts.GateResult(false, "CONSENT_WITHDRAWN"));
            case NOT_OK -> shape("RF_PII_001", null);
            case UNAVAILABLE -> throw new RuntimeException("simulated timeout / connection refused");
        };
    }

    @SuppressWarnings("unchecked")
    private static FeignContracts.ApiResponseShape<FeignContracts.GateResult> shape(String code,
                                                                                    FeignContracts.GateResult data) {
        try {
            // ApiResponseShape 是非静态内部类且外围类构造器私有（W2 缺陷）：
            // Unsafe.allocateInstance 绕过构造器（该类无状态依赖外围实例，安全）
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            var f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            Class<?> cls = Class.forName("com.company.retail.common.contract.FeignContracts$ApiResponseShape");
            Object shape = unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, cls);
            set(shape, "code", code);
            set(shape, "message", code);
            set(shape, "data", data);
            set(shape, "traceId", "test-trace");
            return (FeignContracts.ApiResponseShape<FeignContracts.GateResult>) shape;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ApiResponseShape 反射构造失败（W2 契约缺陷）", e);
        }
    }

    private static void set(Object target, String field, Object value) throws ReflectiveOperationException {
        Field f = target.getClass().getField(field);
        f.set(target, value);
    }
}
