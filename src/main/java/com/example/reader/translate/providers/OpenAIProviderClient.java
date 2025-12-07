package com.example.reader.translate.providers;

import com.example.reader.translate.OpenAITranslateProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

public class OpenAIProviderClient implements ProviderClient {

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
    }

    @Override
    public String literal(String text, String source, String target) {
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
        String systemPrompt = """
                You are a language tutor that helps with understanding texts (books, short stories, novellas).
                Explain provided text in detail: vocabulary, grammar, nuances and how it would be naturally understood.
                Output in the user's target language, but show important original words as needed.
                """;

        String userPrompt = "Source language: " + source +
                "\nTarget language (for explanation): " + target +
                "\nBook context: " + bookContext +
                "\nSentence:\n" + text;

        return callChat(systemPrompt, userPrompt);
    }

    private String callChat(String systemPrompt, String userPrompt) {
        // Minimal Chat Completions-style call
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0.1
        );

        OpenAIChatResponse response = this.webClient
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OpenAIChatResponse.class)
                .block();

        if (response == null || response.choices == null || response.choices.isEmpty()) {
            throw new IllegalStateException("Empty OpenAI response");
        }
        return response.choices.getFirst().message.content;
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
