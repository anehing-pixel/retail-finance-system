package com.company.retail.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MQ 密文信封密钥启动门禁（R11 P0-8）：开启 MQ 时密钥缺失 / 过短 / 生产环境弱默认值一律拒绝启动。
 */
class MqEnvelopeConfigSecretTest {

    private static final String DEV_KEY = "dev-only-retail-pii-data-key-change-me-in-prod-012345";
    private static final String STRONG_KEY = "mq-envelope-data-key-0123456789abcdef0123";

    private static StandardEnvironment env(String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    private static MqProperties props(String dataKey) {
        MqProperties props = new MqProperties();
        props.setEnabled(true);
        props.setDataKey(dataKey);
        return props;
    }

    @Test
    void blankKeyFailsStartup() {
        assertThrows(IllegalStateException.class,
                () -> new MqEnvelopeConfig().envelopeCipher(props(""), env()));
    }

    @Test
    void shortKeyFailsStartup() {
        assertThrows(IllegalStateException.class,
                () -> new MqEnvelopeConfig().envelopeCipher(props("short-key"), env()));
    }

    @Test
    void devKeyIsRejectedWithoutDevProfile() {
        assertThrows(IllegalStateException.class,
                () -> new MqEnvelopeConfig().envelopeCipher(props(DEV_KEY), env()));
    }

    @Test
    void devKeyIsAcceptedWithDevProfile() {
        assertDoesNotThrow(() ->
                new MqEnvelopeConfig().envelopeCipher(props(DEV_KEY), env("dev")));
    }

    @Test
    void strongKeyPasses() {
        assertDoesNotThrow(() ->
                new MqEnvelopeConfig().envelopeCipher(props(STRONG_KEY), env()));
    }
}
