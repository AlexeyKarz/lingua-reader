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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GoogleProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleProviderClient.class);

    private final WebClient webClient;
    private final MeterRegistry registry;
    private final String model;
    private final String literaryModel;
    private final int timeoutSeconds;
    private final int maxOutputTokens;
    private final int literaryMaxOutputTokens;
    private final String literalSystem;
    private final String literarySystem;
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
        this.literaryModel = props.getLiteraryModel() == null || props.getLiteraryModel().isBlank()
                ? props.getModel()
                : props.getLiteraryModel();
        this.timeoutSeconds = props.getTimeoutSeconds();
        this.maxOutputTokens = props.getMaxOutputTokens();
        this.literaryMaxOutputTokens = props.getLiteraryMaxOutputTokens();
        this.apiKey = props.getApiKey();

        this.webClient = builder
                .baseUrl(props.getBaseUrl())
                .build();
        this.registry = registry;

        this.literalSystem = prompts.getLiteralSystem();
        this.literarySystem = prompts.getLiterarySystem();
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
                () -> callGenerateContent(model, literalSystem, prompt, 0.1, maxOutputTokens)
        );
    }

    @Override
    public String literary(String text, String source, String target, String bookContext) {
        return literary(text, source, target, bookContext, null);
    }

    @Override
    public String literary(String text, String source, String target, String bookContext, String modelOverride) {
        String effectiveModel = effectiveLiteraryModel(modelOverride);
        String prompt = "Source language: " + source +
                "\nTarget language: " + target +
                "\nBook context: " + (bookContext == null ? "" : bookContext) +
                "\nText:\n" + text;
        return ProviderMetrics.record(
                registry,
                providerName,
                effectiveModel,
                "literary",
                source,
                target,
                () -> callGenerateContent(effectiveModel, literarySystem, prompt, 0.3, literaryMaxOutputTokens)
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
                () -> callGenerateContent(model, explainSystem, prompt, 0.1, maxOutputTokens)
        );
    }

    @Override
    public String cleanOcr(String text, String language, String modelOverride) {
        String effectiveModel = effectiveLiteraryModel(modelOverride);
        String system = """
                Correct only clear OCR recognition errors. Preserve language, historical spelling,
                dialect, names, meaning, punctuation, paragraph boundaries, and style. Never
                translate, modernize, summarize, explain, or invent. Output only corrected text.
                """;
        return ProviderMetrics.record(
                registry, providerName, effectiveModel, "ocr_cleanup", language, language,
                () -> callGenerateContent(effectiveModel, system,
                        "Language: " + language + "\nOCR text:\n" + text,
                        0.1, Math.max(literaryMaxOutputTokens, 2500))
        );
    }

    private String callGenerateContent(String model, String system, String user, double temperature, int maxOutputTokens) {
        long started = System.currentTimeMillis();

        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("temperature", temperature);
        if (maxOutputTokens > 0) {
            generationConfig.put("maxOutputTokens", maxOutputTokens);
        }

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
                "generationConfig", generationConfig
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

    private String effectiveLiteraryModel(String modelOverride) {
        return modelOverride == null || modelOverride.isBlank() ? literaryModel : modelOverride.trim();
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
