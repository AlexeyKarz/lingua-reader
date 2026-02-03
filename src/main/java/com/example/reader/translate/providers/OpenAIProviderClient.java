package com.example.reader.translate.providers;

import com.example.reader.translate.properties.OpenAITranslateProperties;
import com.example.reader.translate.properties.PromptProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

public class OpenAIProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIProviderClient.class);

    private final WebClient webClient;
    private final MeterRegistry registry;
    private final String model;
    private final int timeoutSeconds;
    private final String literalSystem;
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
        this.webClient = builder
                .baseUrl("https://api.openai.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .build();
        this.registry = registry;
        log.info("OpenAI provider enabled with model {}", this.model);
        this.literalSystem = prompts.getLiteralSystem();
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
                () -> callChat(literalSystem, userPrompt)
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
                () -> callChat(explainSystem, userPrompt)
        );
    }

    private String callChat(String systemPrompt, String userPrompt) {
        // Minimal Chat Completions-style call
        long started = System.currentTimeMillis();
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0.1
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
}
