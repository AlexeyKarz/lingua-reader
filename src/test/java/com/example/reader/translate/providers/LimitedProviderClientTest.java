package com.example.reader.translate.providers;

import com.example.reader.translate.properties.TranslateLimitProperties;
import com.example.reader.translate.AdminTranslationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitedProviderClientTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void fallsBackWhenPerIpLimitIsReached() {
        TranslateLimitProperties limits = new TranslateLimitProperties();
        limits.setPerIpDaily(1);
        limits.setGlobalDaily(10);

        CountingProvider realProvider = new CountingProvider();
        LimitedProviderClient limited = new LimitedProviderClient(realProvider, limits);
        requestFrom("203.0.113.10");

        assertThat(limited.literal("Hallo", "de", "en")).isEqualTo("real:Hallo");

        String second = limited.literal("Welt", "de", "en");

        assertThat(realProvider.literalCalls).isEqualTo(1);
        assertThat(second).contains("Daily AI limit reached for this visitor");
        assertThat(second).contains("Showing demo stub output instead");
        assertThat(second).contains("[de→en] Welt");
    }

    @Test
    void fallsBackWhenGlobalLimitIsReached() {
        TranslateLimitProperties limits = new TranslateLimitProperties();
        limits.setPerIpDaily(10);
        limits.setGlobalDaily(1);

        CountingProvider realProvider = new CountingProvider();
        LimitedProviderClient limited = new LimitedProviderClient(realProvider, limits);

        requestFrom("203.0.113.10");
        assertThat(limited.literal("Hallo", "de", "en")).isEqualTo("real:Hallo");

        requestFrom("198.51.100.25");
        String second = limited.literal("Welt", "de", "en");

        assertThat(realProvider.literalCalls).isEqualTo(1);
        assertThat(second).contains("public demo reached its daily AI limit");
        assertThat(second).contains("[de→en] Welt");
    }

    @Test
    void fallsBackWhenTextIsTooLong() {
        TranslateLimitProperties limits = new TranslateLimitProperties();
        limits.setMaxTextChars(5);

        CountingProvider realProvider = new CountingProvider();
        LimitedProviderClient limited = new LimitedProviderClient(realProvider, limits);
        requestFrom("203.0.113.10");

        String result = limited.literal("too long", "en", "de");

        assertThat(realProvider.literalCalls).isZero();
        assertThat(result).contains("too long for AI mode");
        assertThat(result).contains("[en→de] too long");
    }

    @Test
    void appliesSeparateLiteraryTextLimit() {
        TranslateLimitProperties limits = new TranslateLimitProperties();
        limits.setMaxTextChars(100);
        limits.setLiteraryMaxTextChars(5);

        CountingProvider realProvider = new CountingProvider();
        LimitedProviderClient limited = new LimitedProviderClient(realProvider, limits);
        requestFrom("203.0.113.10");

        String result = limited.literary("too long", "en", "de", "context");

        assertThat(realProvider.literaryCalls).isZero();
        assertThat(result).contains("too long for AI mode");
        assertThat(result).contains("Literary translation stub [en->de]: too long");
    }

    @Test
    void adminRequestsBypassDailyQuotaButKeepTextLimit() {
        TranslateLimitProperties limits = new TranslateLimitProperties();
        limits.setLiteraryPerIpDaily(0);
        limits.setLiteraryGlobalDaily(0);
        limits.setLiteraryMaxTextChars(100);

        CountingProvider realProvider = new CountingProvider();
        LimitedProviderClient limited = new LimitedProviderClient(realProvider, limits);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/translate/literary");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(limited.literary("short", "en", "de", "context")).isEqualTo("real literary:short");
        assertThat(realProvider.literaryCalls).isEqualTo(1);
    }

    @Test
    void fallsBackWhenProviderFails() {
        TranslateLimitProperties limits = new TranslateLimitProperties();
        FailingProvider realProvider = new FailingProvider();
        LimitedProviderClient limited = new LimitedProviderClient(realProvider, limits);
        requestFrom("203.0.113.10");

        String result = limited.explain("Hallo Welt", "de", "en", "context");

        assertThat(result).contains("AI provider is temporarily unavailable or too slow");
        assertThat(result).contains("Explanation stub: Hallo Welt");
    }

    @Test
    void administrativeGenerationFailsInsteadOfPersistingStubOutput() {
        TranslateLimitProperties limits = new TranslateLimitProperties();
        LimitedProviderClient limited = new LimitedProviderClient(new FailingProvider(), limits);

        assertThatThrownBy(() -> AdminTranslationContext.call(
                () -> limited.literary("passage", "el", "en", "context")
        )).isInstanceOf(RuntimeException.class).hasMessageContaining("provider failed");
    }

    private static void requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static class CountingProvider implements ProviderClient {
        int literalCalls;
        int literaryCalls;

        @Override
        public String literal(String text, String source, String target) {
            literalCalls++;
            return "real:" + text;
        }

        @Override
        public String literary(String text, String source, String target, String bookContext) {
            literaryCalls++;
            return "real literary:" + text;
        }

        @Override
        public String explain(String text, String source, String target, String bookContext) {
            return "real explanation:" + text;
        }
    }

    private static class FailingProvider implements ProviderClient {
        @Override
        public String literal(String text, String source, String target) {
            throw new RuntimeException("provider failed");
        }

        @Override
        public String literary(String text, String source, String target, String bookContext) {
            throw new RuntimeException("provider failed");
        }

        @Override
        public String explain(String text, String source, String target, String bookContext) {
            throw new RuntimeException("provider failed");
        }
    }
}
