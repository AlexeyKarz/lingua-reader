package com.example.reader.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptLimiter {
    private static final int MAX_FAILURES = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final Clock clock;
    private final ConcurrentHashMap<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public void checkAllowed(String email, String remoteAddress) {
        String key = key(email, remoteAddress);
        long now = clock.millis();
        AttemptState state = attempts.get(key);
        if (state == null || state.resetAtMillis() <= now) {
            attempts.remove(key);
            return;
        }
        if (state.failures() >= MAX_FAILURES) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts. Try again later.");
        }
    }

    public void recordFailure(String email, String remoteAddress) {
        String key = key(email, remoteAddress);
        long now = clock.millis();
        attempts.compute(key, (ignored, state) -> {
            if (state == null || state.resetAtMillis() <= now) {
                return new AttemptState(1, now + WINDOW.toMillis());
            }
            return new AttemptState(state.failures() + 1, state.resetAtMillis());
        });
    }

    public void recordSuccess(String email, String remoteAddress) {
        attempts.remove(key(email, remoteAddress));
    }

    private String key(String email, String remoteAddress) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        String normalizedRemote = remoteAddress == null ? "" : remoteAddress.trim();
        return normalizedEmail + "|" + normalizedRemote;
    }

    private record AttemptState(int failures, long resetAtMillis) {}
}
