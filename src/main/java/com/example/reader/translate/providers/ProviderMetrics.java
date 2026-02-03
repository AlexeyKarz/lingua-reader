package com.example.reader.translate.providers;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;

final class ProviderMetrics {

    @FunctionalInterface
    interface ProviderCall<T> {
        T call() throws Exception;
    }

    private ProviderMetrics() {}

    static <T> T record(
            MeterRegistry registry,
            String provider,
            String model,
            String operation,
            String source,
            String target,
            ProviderCall<T> call
    ) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        String error = "none";

        try {
            return call.call();
        } catch (Exception e) {
            outcome = "fail";
            error = classifyError(e);
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        } finally {
            Timer timer = Timer.builder("translate.provider.latency")
                    .tags(
                            "provider", safeTag(provider),
                            "operation", safeTag(operation),
                            "model", safeTag(model),
                            "src", safeTag(source),
                            "tgt", safeTag(target),
                            "outcome", outcome
                    )
                    .publishPercentileHistogram()
                    .register(registry);
            sample.stop(timer);

            registry.counter(
                    "translate.provider.calls",
                    "provider", safeTag(provider),
                    "operation", safeTag(operation),
                    "model", safeTag(model),
                    "src", safeTag(source),
                    "tgt", safeTag(target),
                    "outcome", outcome,
                    "error", error
            ).increment();
        }
    }

    private static String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value;
    }

    private static String classifyError(Throwable error) {
        if (error == null) {
            return "none";
        }
        WebClientResponseException wcre = findCause(error, WebClientResponseException.class);
        if (wcre != null) {
            if (wcre.getStatusCode().is4xxClientError()) return "4xx";
            if (wcre.getStatusCode().is5xxServerError()) return "5xx";
            return "http";
        }
        if (findCause(error, TimeoutException.class) != null) {
            return "timeout";
        }
        return "other";
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
