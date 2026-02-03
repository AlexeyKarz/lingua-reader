package com.example.reader.translate.providers;

import com.example.reader.translate.properties.GoogleTranslateProperties;
import com.example.reader.translate.properties.PromptProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class GoogleProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleProviderClient.class);

    private final WebClient webClient;
    private final MeterRegistry registry;
    private final String model;
    private final int timeoutSeconds;
    private final String literalSystem;
    private final String explainSystem;
    private final String apiKey;
    private final String providerName = "google";

    public GoogleProviderClient(
            GoogleTranslateProperties props,
            PromptProperties prompts,
            WebClient.Builder builder,
            MeterRegistry registry
    ) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("translate.google.apiKey must be set");
        }

        this.model = props.getModel();
        this.timeoutSeconds = props.getTimeoutSeconds();
        this.apiKey = props.getApiKey();

        this.webClient = builder
                .baseUrl(props.getBaseUrl())
                .build();
        this.registry = registry;

        this.literalSystem = prompts.getLiteralSystem();
        this.explainSystem = prompts.getExplainSystem();

        log.info("Google provider enabled with model {}", this.model);
    }

    @Override
    public String literal(String text, String source, String target) {
        String prompt = "Source language: " + source +
                "\nTarget language: " + target +
                "\nText:\n" + text;
        return ProviderMetrics.record(
                registry,
                providerName,
                model,
                "literal",
                source,
                target,
                () -> callGenerateContent(literalSystem, prompt)
        );
    }

    @Override
    public String explain(String text, String source, String target, String bookContext) {
        String prompt = "Source language: " + source +
                "\nTarget language (for explanation): " + target +
                "\nContext: " + bookContext +
                "\nText:\n" + text;
        return ProviderMetrics.record(
                registry,
                providerName,
                model,
                "explain",
                source,
                target,
                () -> callGenerateContent(explainSystem, prompt)
        );
    }

    private String callGenerateContent(String system, String user) {
        long started = System.currentTimeMillis();

        // Preferred shape: use systemInstruction + user contents.
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of(
                        "parts", List.of(Map.of("text", system))
                ),
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(Map.of("text", user))
                        )
                ),
                "generationConfig", Map.of(
                        "temperature", 0.1
                )
        );

        try {
            GoogleResponse response = this.webClient
                    .post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1beta/models/{model}:generateContent")
                            .queryParam("key", apiKey)
                            .build(model)
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(GoogleResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .retryWhen(Retry.max(1))
                    .block();

            String content = extractText(response);

            long durationMs = System.currentTimeMillis() - started;
            log.info("Google call succeeded in {} ms ({} chars)",
                    durationMs, content.length());

            return content;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - started;
            log.error("Google call failed after {} ms: {}", durationMs, e.toString());
            throw e;
        }
    }

    private static String extractText(GoogleResponse response) {
        if (response == null || response.candidates == null || response.candidates.isEmpty()) {
            throw new IllegalStateException("Empty Google response");
        }
        var c = response.candidates.getFirst();
        if (c.content == null || c.content.parts == null || c.content.parts.isEmpty()) {
            throw new IllegalStateException("Empty Google candidate content");
        }
        var sb = new StringBuilder();
        for (var p : c.content.parts) {
            if (p != null && p.text != null) sb.append(p.text);
        }
        return sb.toString().trim();
    }

    // DTOs
    public static class GoogleResponse {
        public List<Candidate> candidates;
    }

    public static class Candidate {
        public Content content;
        public String finishReason;
    }

    public static class Content {
        public String role;
        public List<Part> parts;
    }

    public static class Part {
        public String text;
    }
}
