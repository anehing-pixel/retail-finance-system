package com.company.retail.externalapi.security;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.externalapi.config.DmzProperties;
import com.company.retail.externalapi.security.PartnerDirectory.PartnerProfile;
import com.company.retail.common.config.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DMZ 三关守门人单测（R07 §5.2 顺序语义，纯内存桩，无 DB）。
 * 覆盖：重放拒绝、过期 nonce 清理语义、scope 拒绝、IP 白名单开/关两态、
 * call_logs 留痕、验签失败优先于 scope/IP 三关。
 */
class DmzGatekeeperTest {

    private static final String PARTNER = "DMS-DEV-01";
    private static final String SECRET = "dev-dms-partner-secret-0123456789";
    private static final String BODY = "{\"hello\":\"dmz\"}";

    /** 可拨动的测试时钟。 */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advanceSeconds(long s) { now = now.plusSeconds(s); }
    }

    /** 内存 nonce 桩：登记记时间戳，清理按 cutoff 删旧。 */
    static class FakeNonceStore implements NonceStore {
        final MutableClock clock;
        final Map<String, Instant> used = new HashMap<>();
        final List<Instant> purgeCutoffs = new ArrayList<>();
        FakeNonceStore(MutableClock clock) { this.clock = clock; }
        @Override public boolean tryRecord(String partnerCode, String nonce) {
            String key = partnerCode + "|" + nonce;
            if (used.containsKey(key)) return false;
            used.put(key, clock.instant());
            return true;
        }
        @Override public int purgeBefore(Instant cutoff) {
            purgeCutoffs.add(cutoff);
            int before = used.size();
            used.values().removeIf(t -> t.isBefore(cutoff));
            return before - used.size();
        }
    }

    /** 内存留痕桩：[partnerCode, endpoint, status, result, traceId]。 */
    static class FakeCallLogStore implements CallLogStore {
        final List<String[]> rows = new ArrayList<>();
        @Override public void record(String partnerCode, String endpoint, int httpStatus,
                                     long durationMs, String result, String traceId) {
            rows.add(new String[]{partnerCode, endpoint, String.valueOf(httpStatus), result, traceId});
        }
    }

    static class StubDirectory implements PartnerDirectory {
        final Map<String, PartnerProfile> profiles = new HashMap<>();
        @Override public PartnerProfile profileOf(String partnerCode) { return profiles.get(partnerCode); }
    }

    final MutableClock clock = new MutableClock();
    final FakeNonceStore nonceStore = new FakeNonceStore(clock);
    final FakeCallLogStore callLogs = new FakeCallLogStore();
    final StubDirectory directory = new StubDirectory();
    final PartnerRegistry registry = new PartnerRegistry(SECRET, SECRET);

    DmzGatekeeper newKeeper(boolean ipAllowlistEnabled) {
        DmzProperties props = new DmzProperties();
        props.setIpAllowlistEnabled(ipAllowlistEnabled);
        return new DmzGatekeeper(registry, directory, nonceStore, callLogs, props, clock);
    }

    StubDirectory registerEchoPartner() {
        directory.profiles.put(PARTNER,
                new PartnerProfile(PARTNER, true, Set.of("echo"), List.of()));
        return directory;
    }

    /** 构造一个带合法签名的 echo 请求。 */
    MockHttpServletRequest validRequest(String nonce, String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/external/echo");
        String ts = String.valueOf(clock.instant().getEpochSecond());
        req.addHeader("X-Partner-Code", PARTNER);
        req.addHeader("X-Nonce", nonce);
        req.addHeader("X-Timestamp", ts);
        req.addHeader("X-Key-Version", "v1");
        req.addHeader("X-Signature", PartnerSignatureVerifier.expectedSignature(SECRET,
                PartnerSignatureVerifier.canonical("POST", "/external/echo", ts, nonce, "v1", BODY)));
        req.setRemoteAddr(ip);
        req.setAttribute(TraceIdFilter.ATTR, "trace-" + nonce);
        return req;
    }

    private static final Supplier<Object> OK = () -> "ok-data";

    String codeOf(Runnable r) {
        try {
            r.run();
            return "NO_THROW";
        } catch (BusinessException e) {
            return e.getErrorCode().getCode();
        }
    }

    @Test
    void validRequestPassesAllGatesAndLogsSuccess() {
        registerEchoPartner();
        Object out = newKeeper(false).guard(validRequest("n-ok", "127.0.0.1"), BODY, OK);
        assertEquals("ok-data", out);
        assertEquals(1, callLogs.rows.size());
        assertEquals("SUCCESS", callLogs.rows.get(0)[3]);
        assertEquals("200", callLogs.rows.get(0)[2]);
        assertEquals("trace-n-ok", callLogs.rows.get(0)[4]);
    }

    @Test
    void replayedNonceRejectedWithRfPtnr002AndLogged() {
        registerEchoPartner();
        DmzGatekeeper keeper = newKeeper(false);
        keeper.guard(validRequest("n-replay", "127.0.0.1"), BODY, OK);
        String code = codeOf(() -> keeper.guard(validRequest("n-replay", "127.0.0.1"), BODY, OK));
        assertEquals(RFErrorCode.RF_PTNR_002.getCode(), code, "同 nonce 重放必须拒绝");
        assertEquals(2, callLogs.rows.size());
        assertEquals("SUCCESS", callLogs.rows.get(0)[3]);
        assertEquals(RFErrorCode.RF_PTNR_002.getCode(), callLogs.rows.get(1)[3], "失败留痕记语义码");
    }

    @Test
    void expiredNoncePurgedThenReusableButTimestampStillGuards() {
        registerEchoPartner();
        DmzGatekeeper keeper = newKeeper(false);
        MockHttpServletRequest first = validRequest("n-exp", "127.0.0.1");
        keeper.guard(first, BODY, OK);

        clock.advanceSeconds(700);   // 超过留存期 600s
        MockHttpServletRequest replay = validRequest("n-exp", "127.0.0.1");  // 重签新时间戳
        keeper.guard(replay, BODY, OK);

        // 惰性清理的 cutoff = now - retention
        Instant cutoff = clock.instant().minusSeconds(600);
        assertEquals(cutoff, nonceStore.purgeCutoffs.get(nonceStore.purgeCutoffs.size() - 1));
        // 清理后同 nonce 不误拒（无重放风险：旧时间戳早已过期，见下一条单测）
        assertTrue(callLogs.rows.stream().allMatch(r -> "SUCCESS".equals(r[3])),
                "留存期外的同 nonce 允许再登记");
    }

    @Test
    void oldTimestampWithReusedNonceStillRejectedByTimestampGate() {
        registerEchoPartner();
        DmzGatekeeper keeper = newKeeper(false);
        String oldTs = String.valueOf(clock.instant().getEpochSecond());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/external/echo");
        req.addHeader("X-Partner-Code", PARTNER);
        req.addHeader("X-Nonce", "n-old");
        req.addHeader("X-Timestamp", oldTs);
        req.addHeader("X-Key-Version", "v1");
        req.addHeader("X-Signature", PartnerSignatureVerifier.expectedSignature(SECRET,
                PartnerSignatureVerifier.canonical("POST", "/external/echo", oldTs, "n-old", "v1", BODY)));
        req.setRemoteAddr("127.0.0.1");

        clock.advanceSeconds(700);   // 签名仍合法，但时间戳超出 ±300s
        assertEquals(RFErrorCode.RF_PTNR_001.getCode(),
                codeOf(() -> keeper.verify(req, PARTNER, oldTs, "n-old", "v1", BODY,
                        req.getHeader("X-Signature"))), "过期时间戳先行拒绝");
    }

    @Test
    void outOfScopeEndpointRejectedWith403() {
        directory.profiles.put(PARTNER,
                new PartnerProfile(PARTNER, true, Set.of("submit_application"), List.of()));
        DmzGatekeeper keeper = newKeeper(false);
        String code = codeOf(() -> keeper.guard(validRequest("n-scope", "127.0.0.1"), BODY, OK));
        assertEquals(RFErrorCode.RF_PTNR_003.getCode(), code, "scope 外接口拒绝");
        assertEquals(RFErrorCode.RF_PTNR_003.getCode(), callLogs.rows.get(0)[3]);
    }

    @Test
    void ipAllowlistOffStateIgnoresPartnerList() {
        directory.profiles.put(PARTNER,
                new PartnerProfile(PARTNER, true, Set.of("echo"), List.of("127.0.0.1")));
        Object out = newKeeper(false).guard(validRequest("n-ipoff", "9.9.9.9"), BODY, OK);
        assertEquals("ok-data", out, "全局开关关：名单外 IP 也放行");
    }

    @Test
    void ipAllowlistOnStateRejectsForeignIpAllowsListed() {
        directory.profiles.put(PARTNER,
                new PartnerProfile(PARTNER, true, Set.of("echo"), List.of("127.0.0.1")));
        DmzGatekeeper keeper = newKeeper(true);
        assertEquals(RFErrorCode.RF_PTNR_003.getCode(),
                codeOf(() -> keeper.guard(validRequest("n-ipbad", "9.9.9.9"), BODY, OK)),
                "白名单外 IP 拒绝");
        assertEquals("ok-data", keeper.guard(validRequest("n-ipgood", "127.0.0.1"), BODY, OK),
                "名单内 IP 放行");
    }

    @Test
    void badSignatureBeatsScopeAndIpGates() {
        directory.profiles.put(PARTNER,
                new PartnerProfile(PARTNER, true, Set.of("submit_application"), List.of("127.0.0.1")));
        DmzGatekeeper keeper = newKeeper(true);
        MockHttpServletRequest req = validRequest("n-badsig", "9.9.9.9");
        req.removeHeader("X-Signature");
        req.addHeader("X-Signature", "TAMPERED-SIGNATURE");
        String code = codeOf(() -> keeper.guard(req, BODY, OK));
        assertEquals(RFErrorCode.RF_PTNR_001.getCode(), code,
                "验签失败优先于 scope/IP（R07 §5.2 第 3 关先于第 4 关）");
    }

    @Test
    void disabledPartnerRejectedAfterValidSignature() {
        directory.profiles.put(PARTNER,
                new PartnerProfile(PARTNER, false, Set.of("echo"), List.of()));
        String code = codeOf(() -> newKeeper(false).guard(validRequest("n-disabled", "127.0.0.1"), BODY, OK));
        assertEquals(RFErrorCode.RF_PTNR_003.getCode(), code, "DB 模式 DISABLED 拒绝");
    }

    @Test
    void unknownPartnerRejectedAndLoggedWithoutPartner() {
        String code = codeOf(() -> newKeeper(false).guard(validRequest("n-unknown", "127.0.0.1"), BODY, OK));
        assertEquals(RFErrorCode.RF_PTNR_003.getCode(), code);
        assertEquals(1, callLogs.rows.size(), "未登记 partner 也留痕（partner_id 为空由存储层解析）");
    }

    @Test
    void routeKeyMapsExternalPath() {
        assertEquals("echo", DmzGatekeeper.routeKeyOf("/external/echo"));
        assertEquals("dms_applications", DmzGatekeeper.routeKeyOf("/external/dms/applications"));
        assertEquals("", DmzGatekeeper.routeKeyOf(null));
    }

    @Test
    void nonceDisabledSwitchSkipsReplayGate() {
        registerEchoPartner();
        DmzProperties props = new DmzProperties();
        props.setNonceEnabled(false);
        DmzGatekeeper keeper = new DmzGatekeeper(registry, directory, nonceStore, callLogs, props, clock);
        keeper.guard(validRequest("n-sw", "127.0.0.1"), BODY, OK);
        keeper.guard(validRequest("n-sw", "127.0.0.1"), BODY, OK);
        assertTrue(callLogs.rows.stream().allMatch(r -> "SUCCESS".equals(r[3])),
                "开关关：同 nonce 两次都放行");
        assertTrue(nonceStore.used.isEmpty(), "开关关：不写 nonce 库");
    }
}
