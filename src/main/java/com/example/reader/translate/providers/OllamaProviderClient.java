// src/main/java/com/example/reader/translate/providers/OllamaProviderClient.java
package com.example.reader.translate.providers;

import com.example.reader.translate.properties.OllamaTranslateProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

public class OllamaProviderClient implements ProviderClient {

    private final WebClient client;
    private final MeterRegistry registry;
    private final String model;
    private final String explainModel;
    private final String providerName = "ollama";

    public OllamaProviderClient(OllamaTranslateProperties props, WebClient.Builder builder, MeterRegistry registry) {
        this.client = builder.baseUrl(props.getBaseUrl()).build();
        this.model = props.getModel();
        this.explainModel = (props.getExplainModel() == null || props.getExplainModel().isBlank())
                ? props.getModel()
                : props.getExplainModel();
        this.registry = registry;
    }

    @Override
    public String literal(String text, String source, String target) {
        String prompt = """
                Translate strictly from %s to %s.
                Output ONLY the translation text.
                Keep it as literal as possible while grammatical.

                Text:
                %s
                """.formatted(source, target, text);

        return ProviderMetrics.record(
                registry,
                providerName,
                model,
                "literal",
                source,
                target,
                () -> generate(model, prompt)
        );
    }

    @Override
    public String explain(String text, String source, String target, String bookContext) {
        String prompt = """
                You are a language tutor.
                Explain vocabulary, grammar, and nuance for this sentence.
                The explanation should be as concise and clear as possible.
                Do not use markdown symbols.
                Provide the explanation in %s.
                Use book context if helpful.

                Book context: %s
                Source language: %s
                Sentence:
                %s
                """.formatted(target, bookContext, source, text);

        return ProviderMetrics.record(
                registry,
                providerName,
                explainModel,
                "explain",
                source,
                target,
                () -> generate(explainModel, prompt)
        );
    }

    private String generate(String model, String prompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false
        );

        OllamaResp resp = client.post()
                .uri("/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OllamaResp.class)
                .block();

        if (resp == null || resp.response == null) {
            throw new IllegalStateException("Empty Ollama response");
        }
        return resp.response.trim();
    }

    public static class OllamaResp {
        public String response;
    }
}
