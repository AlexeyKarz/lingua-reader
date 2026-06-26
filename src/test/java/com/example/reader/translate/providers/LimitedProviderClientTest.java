package com.example.reader.translate.providers;

import com.example.reader.translate.properties.TranslateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static void requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static class CountingProvider implements ProviderClient {
        int literalCalls;

        @Override
        public String literal(String text, String source, String target) {
            literalCalls++;
            return "real:" + text;
        }

        @Override
        public String explain(String text, String source, String target, String bookContext) {
            return "real explanation:" + text;
        }
    }
}
