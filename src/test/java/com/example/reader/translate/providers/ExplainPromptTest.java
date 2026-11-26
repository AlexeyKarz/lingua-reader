package com.example.reader.translate.providers;

import com.example.reader.translate.properties.GoogleTranslateProperties;
import com.example.reader.translate.properties.OllamaTranslateProperties;
import com.example.reader.translate.properties.OpenAITranslateProperties;
import com.example.reader.translate.properties.PromptProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainPromptTest {
    @Test
    void applicationConfigDoesNotOverrideTheConciseDefault() throws Exception {
        PromptProperties effective = new PromptProperties();
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        Binder.get(environment).bind("translate.prompts", Bindable.ofInstance(effective));

        assertThat(effective.getExplainSystem()).isEqualTo(new PromptProperties().getExplainSystem())
                .contains("40-80 words", "dictionary base form", "case/preposition", "at most one short example",
                        "original writing system", "note uncertainty briefly", "No introduction")
                .doesNotContain("in detail");
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "google", "ollama"})
    void explainSendsSharedInstructionsAndKeepsSelectionAndContext(String provider) throws Exception {
        PromptProperties prompts = new PromptProperties();
        JsonNode body = explainRequest(provider, prompts);
        String payload = body.toString();
        assertThat(payload).contains("40-80 words", "original writing system", "dictionary base form",
                "case/preposition", "No introduction", "acurrucado", "es", "ru", "Selected sentence:");
        if (provider.equals("openai")) {
            assertThat(body.at("/messages/0/content").asText()).isEqualTo(prompts.getExplainSystem());
            assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(300);
        } else if (provider.equals("google")) {
            assertThat(body.at("/systemInstruction/parts/0/text").asText()).isEqualTo(prompts.getExplainSystem());
        } else {
            assertThat(body.get("prompt").asText()).startsWith(prompts.getExplainSystem());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "google", "ollama"})
    void explainStillHonorsAnExplicitPromptOverride(String provider) throws Exception {
        PromptProperties prompts = new PromptProperties();
        prompts.setExplainSystem("Custom concise teaching style.");
        assertThat(explainRequest(provider, prompts).toString()).contains("Custom concise teaching style.");
    }

    private JsonNode explainRequest(String provider, PromptProperties prompts) throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        String response = switch (provider) {
            case "openai" -> """
                    {"choices":[{"finish_reason":"stop","message":{"content":"Short language note."}}]}
                    """;
            case "google" -> """
                    {"candidates":[{"content":{"parts":[{"text":"Short language note."}]}}]}
                    """;
            default -> """
                    {"response":"Short language note."}
                    """;
        };
        // Capture serialized HTTP bodies in memory: no provider calls, API keys or database needed.
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            MockClientHttpRequest capture = new MockClientHttpRequest(request.method(), request.url());
            BodyInserter.Context context = new BodyInserter.Context() {
                public List<HttpMessageWriter<?>> messageWriters() { return ExchangeStrategies.withDefaults().messageWriters(); }
                public Optional<ServerHttpRequest> serverRequest() { return Optional.empty(); }
                public Map<String, Object> hints() { return Map.of(); }
            };
            return request.body().insert(capture, context).then(Mono.defer(capture::getBodyAsString)).map(body -> {
                requestBody.set(body);
                return ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(response).build();
            });
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProviderClient client;
        if (provider.equals("openai")) {
            OpenAITranslateProperties properties = new OpenAITranslateProperties();
            properties.setApiKey("test-key");
            properties.setModel("gpt-6-luna");
            client = new OpenAIProviderClient(properties, prompts, builder, registry);
        } else if (provider.equals("google")) {
            GoogleTranslateProperties properties = new GoogleTranslateProperties();
            properties.setApiKey("test-key");
            client = new GoogleProviderClient(properties, prompts, builder, registry);
        } else {
            OllamaTranslateProperties properties = new OllamaTranslateProperties();
            properties.setModel("test-model");
            client = new OllamaProviderClient(properties, prompts, builder, registry);
        }
        assertThat(client.explain("acurrucado", "es", "ru", "Selected sentence: El gato estaba acurrucado."))
                .isEqualTo("Short language note.");
        registry.close();
        return new ObjectMapper().readTree(requestBody.get());
    }
}
