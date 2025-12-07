package com.example.reader.translate.providers;

import com.example.reader.translate.properties.OpenAITranslateProperties;
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
    private final String model;

    public OpenAIProviderClient(OpenAITranslateProperties props, WebClient.Builder builder) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("translate.openai.enabled=true but no API key configured");
        }
        this.model = props.getModel();
        this.webClient = builder
                .baseUrl("https://api.openai.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .build();
        log.info("OpenAI provider enabled with model {}", this.model);
    }

    @Override
    public String literal(String text, String source, String target) {
        log.info("Calling OpenAI literal translation {} -> {} ({} chars)", source, target, text.length());
        String systemPrompt = """
                You are a translation engine.
                Translate strictly from the source language to the target language.
                Output ONLY the translation text, no explanations, no quotes, no markup.
                Keep the translation as literal as possible while remaining grammatical.
                """;

        String userPrompt = "Source language: " + source +
                "\nTarget language: " + target +
                "\nText:\n" + text;

        return callChat(systemPrompt, userPrompt);
    }

    @Override
    public String explain(String text, String source, String target, String bookContext) {
        log.info("Calling OpenAI explain {} -> {} ({} chars, ctx {} chars)",
                source, target, text.length(), bookContext == null ? 0 : bookContext.length());
        String systemPrompt = """
                You are a language tutor that helps with understanding texts (books, short stories, novellas).
                Explain provided text in detail: vocabulary, grammar, nuances and how it would be naturally understood.
                Output in the user's target language, but show important original words as needed.
                Try to make the answer as concise and clear as possible. Do now use markdown symbols (like bold * or header # symbols).
                """;

        String userPrompt = "Source language: " + source +
                "\nTarget language (for explanation): " + target +
                "\nBook context: " + bookContext +
                "\nSentence:\n" + text;

        return callChat(systemPrompt, userPrompt);
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
