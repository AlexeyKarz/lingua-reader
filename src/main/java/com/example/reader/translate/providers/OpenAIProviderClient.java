package com.example.reader.translate.providers;

import com.example.reader.translate.properties.OpenAITranslateProperties;
import com.example.reader.translate.properties.PromptProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAIProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIProviderClient.class);

    private final WebClient webClient;
    private final MeterRegistry registry;
    private final String model;
    private final String literaryModel;
    private final int timeoutSeconds;
    private final int maxCompletionTokens;
    private final int literaryMaxCompletionTokens;
    private final String literalSystem;
    private final String literarySystem;
    private final String explainSystem;
    private final String providerName = "openai";

    public OpenAIProviderClient(
            OpenAITranslateProperties props,
            PromptProperties prompts,
            WebClient.Builder builder,
            MeterRegistry registry
    ) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("translate.openai.enabled=true but no API key configured");
        }
        this.timeoutSeconds = props.getTimeoutSeconds();
        this.model = props.getModel();
        this.literaryModel = props.getLiteraryModel() == null || props.getLiteraryModel().isBlank()
                ? props.getModel()
                : props.getLiteraryModel();
        this.maxCompletionTokens = props.getMaxCompletionTokens();
        this.literaryMaxCompletionTokens = props.getLiteraryMaxCompletionTokens();
        this.webClient = builder
                .baseUrl("https://api.openai.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .build();
        this.registry = registry;
        log.info("OpenAI provider enabled with model {} and maxCompletionTokens {}", this.model, this.maxCompletionTokens);
        this.literalSystem = prompts.getLiteralSystem();
        this.literarySystem = prompts.getLiterarySystem();
        this.explainSystem = prompts.getExplainSystem();
    }

    @Override
    public String literal(String text, String source, String target) {
        log.info("Calling OpenAI literal translation {} -> {} ({} chars)", source, target, text.length());
        String userPrompt = "Source language: " + source +
                "\nTarget language: " + target +
                "\nText:\n" + text;

        return ProviderMetrics.record(
                registry,
                providerName,
                model,
                "literal",
                source,
                target,
                () -> callChat(model, literalSystem, userPrompt, maxCompletionTokens, 0.1)
        );
    }

    @Override
    public String literary(String text, String source, String target, String bookContext) {
        return literary(text, source, target, bookContext, null);
    }

    @Override
    public String literary(String text, String source, String target, String bookContext, String modelOverride) {
        String effectiveModel = effectiveLiteraryModel(modelOverride);
        log.info("Calling OpenAI literary translation {} -> {} ({} chars, ctx {} chars, model {})",
                source, target, text.length(), bookContext == null ? 0 : bookContext.length(), effectiveModel);
        String userPrompt = "Source language: " + source +
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
                () -> callResponses(effectiveModel, literarySystem, userPrompt, literaryMaxCompletionTokens, 0.3)
        );
    }

    @Override
    public String explain(String text, String source, String target, String bookContext) {
        log.info("Calling OpenAI explain {} -> {} ({} chars, ctx {} chars)",
                source, target, text.length(), bookContext == null ? 0 : bookContext.length());
        String userPrompt = "Source language: " + source +
                "\nTarget language (for explanation): " + target +
                "\nBook context: " + bookContext +
                "\nSentence:\n" + text;

        return ProviderMetrics.record(
                registry,
                providerName,
                model,
                "explain",
                source,
                target,
                () -> callChat(model, explainSystem, userPrompt, maxCompletionTokens, 0.1)
        );
    }

    private String callChat(String model, String systemPrompt, String userPrompt, int maxTokens, double temperature) {
        // Minimal Chat Completions-style call
        long started = System.currentTimeMillis();
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", temperature,
                "max_tokens", maxTokens
        );

        try {
            OpenAIChatResponse response = this.webClient
                    .post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(OpenAIChatResponse.class)
                    .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                    .block();

            long durationMs = System.currentTimeMillis() - started;
            if (response == null || response.choices == null || response.choices.isEmpty()) {
                throw new IllegalStateException("Empty OpenAI response");
            }
            String content = response.choices.getFirst().message.content;
            log.info("OpenAI call succeeded in {} ms ({} chars)", durationMs, content == null ? 0 : content.length());
            return content;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - started;
            log.error("OpenAI call failed after {} ms: {}", durationMs, e.getMessage());
            throw e;
        }
    }

    private String callResponses(String model, String systemPrompt, String userPrompt, int maxTokens, double temperature) {
        long started = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("instructions", systemPrompt);
        body.put("input", userPrompt);
        body.put("temperature", temperature);
        body.put("max_output_tokens", maxTokens);
        body.put("store", false);

        try {
            OpenAIResponsesResponse response = this.webClient
                    .post()
                    .uri("/v1/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(OpenAIResponsesResponse.class)
                    .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                    .block();

            String content = extractResponsesText(response);
            long durationMs = System.currentTimeMillis() - started;
            log.info("OpenAI Responses call succeeded in {} ms ({} chars)",
                    durationMs, content == null ? 0 : content.length());
            return content;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - started;
            if (e instanceof WebClientResponseException responseException) {
                String detail = responseException.getResponseBodyAsString().trim();
                if (detail.length() > 1_000) detail = detail.substring(0, 1_000);
                log.error("OpenAI Responses call failed after {} ms: {} {}", durationMs, e.getMessage(), detail);
                throw new IllegalStateException(
                        detail.isBlank() ? e.getMessage() : "OpenAI rejected the request: " + detail,
                        e
                );
            }
            log.error("OpenAI Responses call failed after {} ms: {}", durationMs, e.getMessage());
            throw e;
        }
    }

    private String effectiveLiteraryModel(String modelOverride) {
        return modelOverride == null || modelOverride.isBlank() ? literaryModel : modelOverride.trim();
    }

    private String extractResponsesText(OpenAIResponsesResponse response) {
        if (response == null) {
            throw new IllegalStateException("Empty OpenAI Responses response");
        }
        if (response.error != null) {
            throw new IllegalStateException("OpenAI Responses error: " + response.error.message);
        }
        if (response.output_text != null && !response.output_text.isBlank()) {
            return response.output_text;
        }
        if (response.output == null || response.output.isEmpty()) {
            throw new IllegalStateException("OpenAI Responses response did not include output text");
        }

        List<String> parts = new ArrayList<>();
        for (OpenAIResponsesOutput output : response.output) {
            if (output.content == null) continue;
            for (OpenAIResponsesContent content : output.content) {
                if ("output_text".equals(content.type) && content.text != null && !content.text.isBlank()) {
                    parts.add(content.text);
                }
            }
        }
        String text = String.join("", parts).trim();
        if (text.isBlank()) {
            throw new IllegalStateException("OpenAI Responses response did not include output text");
        }
        return text;
    }

    // DTOs to parse the response (minimal)
    public static class OpenAIChatResponse {
        public List<Choice> choices;
    }

    public static class Choice {
        public Message message;
    }

    public static class Message {
        public String role;
        public String content;
    }

    public static class OpenAIResponsesResponse {
        public String output_text;
        public List<OpenAIResponsesOutput> output;
        public OpenAIResponsesError error;
    }

    public static class OpenAIResponsesOutput {
        public String type;
        public List<OpenAIResponsesContent> content;
    }

    public static class OpenAIResponsesContent {
        public String type;
        public String text;
    }

    public static class OpenAIResponsesError {
        public String message;
    }
}
