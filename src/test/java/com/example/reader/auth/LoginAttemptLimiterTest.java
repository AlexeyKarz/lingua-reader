package com.example.reader.auth;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptLimiterTest {

    @Test
    void blocksAfterTenFailuresAndResetsAfterSuccess() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(
                Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC)
        );

        for (int attempt = 0; attempt < 10; attempt += 1) {
            limiter.checkAllowed("ADMIN@example.test", "127.0.0.1");
            limiter.recordFailure("admin@example.test", "127.0.0.1");
        }

        assertThatThrownBy(() -> limiter.checkAllowed("admin@example.test", "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class);

        limiter.recordSuccess("admin@example.test", "127.0.0.1");
        limiter.checkAllowed("admin@example.test", "127.0.0.1");
    }
}
