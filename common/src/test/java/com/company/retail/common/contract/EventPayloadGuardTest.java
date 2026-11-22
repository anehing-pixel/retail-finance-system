package com.company.retail.common.contract;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件 payload 白名单守卫单测（INV-4）与事件常量表完整性（R09 §5.1）。
 */
class EventPayloadGuardTest {

    @Test
    void rejectsPiiKeys() {
        // 禁止键清单（R09 §5.3）逐一命中即拒绝
        for (String key : java.util.List.of("name", "fullName", "kana", "address", "birthDate",
                "phone", "email", "employer", "annualIncome", "myNumber", "customerName")) {
            String payload = """
                    {"%s":"山田太郎","loanId":"1","traceId":"t"}""".formatted(key);
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> EventPayloadGuard.validate("LOAN_SETTLED", payload),
                    "禁止键未被拒绝：" + key);
            assertTrue(ex.getMessage().contains("白名单外"));
        }
    }

    @Test
    void rejectsUnknownKeysByDefaultDeny() {
        String payload = "{\"someNovelKey\":1,\"loanId\":\"1\",\"traceId\":\"t\"}";
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate("LOAN_SETTLED", payload));
    }

    @Test
    void acceptsWhitelistedPayloadWithBizKeyAndTraceId() {
        String payload = """
                {"loanId":"42","status":"SETTLED","amount":120000,"currency":"JPY","traceId":"t-1"}""";
        assertDoesNotThrow(() -> EventPayloadGuard.validate("LOAN_SETTLED", payload));
    }

    @Test
    void rejectsMissingBizKeyOrTraceId() {
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate("LOAN_SETTLED", "{\"status\":\"SETTLED\",\"traceId\":\"t\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate("LOAN_SETTLED", "{\"loanId\":\"42\"}"));
    }

    @Test
    void rejectsUnregisteredEventType() {
        // 未登记事件类型必须在发布端被挡住（bizKeyOf default 分支）
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate("NOT_A_REAL_EVENT", "{\"traceId\":\"t\"}"));
    }

    @Test
    void everyEventConstantHasBizKeyMapping() {
        // R00 §3.6 全部 17 个事件常量都必须能解析出聚合键——防「加了常量忘了映射」
        for (var field : OutboxPublisher.class.getFields()) {
            if (field.getType() == String.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                String eventType = assertDoesNotThrow(() -> (String) field.get(null));
                String bizKey = assertDoesNotThrow(() -> OutboxPublisher.bizKeyOf(eventType),
                        "事件缺聚合键映射：" + eventType);
                assertTrue(EventPayloadGuard.ALLOWED_KEYS.contains(bizKey),
                        "聚合键不在 payload 白名单内：" + bizKey);
            }
        }
        // 事件数量与 R09 §5.1 对齐（17 个：CONSENT_GRANTED 与 CONSENT_WITHDRAWN 算两个）
        long constants = java.util.Arrays.stream(OutboxPublisher.class.getFields())
                .filter(f -> f.getType() == String.class)
                .count();
        assertEquals(17, constants, "事件常量数应与 R09 §5.1 一致");
    }

    @Test
    void bizKeyMappingMatchesContract() {
        // R09 §5.1 aggregate_key 抽查：键名与白名单内登记一致
        Map<String, String> expected = java.util.Map.ofEntries(
                Map.entry("APPLICATION_SUBMITTED", "applicationId"),
                Map.entry("CONSENT_WITHDRAWN", "consentId"),
                Map.entry("LOAN_SETTLED", "loanId"),
                Map.entry("TITLE_RELEASE_REGISTERED", "releaseOrderId"),
                Map.entry("REPAYMENT_RECEIVED", "paymentRef"),
                Map.entry("CREDIT_BUREAU_REPORT_SENT", "reportId"));
        expected.forEach((event, key) -> assertEquals(key, OutboxPublisher.bizKeyOf(event)));
        // getFields() 只返回常量字段（bizKeyOf 是方法），数量应为 17
        assertEquals(17, OutboxPublisher.class.getFields().length);
    }

    @Test
    void whitelistHasNoPiiKeyShapes() {
        // 白名单本身不得含任何疑似 PII 的键（防御白名单自我污染）
        var suspicious = EventPayloadGuard.ALLOWED_KEYS.stream()
                .map(String::toLowerCase)
                .filter(k -> k.contains("name") || k.contains("address") || k.contains("phone")
                        || k.contains("birth") || k.contains("income") || k.contains("email")
                        || k.contains("account") || k.contains("vinowner"))
                .collect(Collectors.toList());
        assertTrue(suspicious.isEmpty(), "白名单含疑似 PII 键：" + suspicious);
    }
}
