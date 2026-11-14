package com.example.reader.translate.providers;

import com.example.reader.translate.properties.OpenAITranslateProperties;
import com.example.reader.translate.properties.PromptProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAIProviderClientTest {

    @Test
    void lunaChatUsesCompletionTokenLimitAndNoReasoningWithTemperature() {
        OpenAIProviderClient client = client("none");

        Map<String, Object> body = client.chatRequestBody(
                "gpt-6-luna", "system", "user", 300, 0.1);

        assertThat(body)
                .containsEntry("max_completion_tokens", 300)
                .containsEntry("reasoning_effort", "none")
                .containsEntry("temperature", 0.1)
                .doesNotContainKey("max_tokens");
    }

    @Test
    void chatOmitsTemperatureWhenReasoningIsEnabled() {
        OpenAIProviderClient client = client("low");

        Map<String, Object> body = client.chatRequestBody(
                "gpt-6-luna", "system", "user", 300, 0.1);

        assertThat(body)
                .containsEntry("reasoning_effort", "low")
                .doesNotContainKey("temperature");
    }

    @Test
    void literaryResponsesExplicitlyDisableReasoning() {
        OpenAIProviderClient client = client("none");

        Map<String, Object> body = client.responsesRequestBody(
                "gpt-6-luna", "system", "user", 700, false);

        assertThat(body)
                .containsEntry("max_output_tokens", 700)
                .containsEntry("reasoning", Map.of("effort", "none"));
    }

    @Test
    void rejectsTokenTruncatedChatResponse() {
        OpenAIProviderClient client = client("none");
        OpenAIProviderClient.Choice choice = new OpenAIProviderClient.Choice();
        choice.finish_reason = "length";
        choice.message = message("partial translation");
        OpenAIProviderClient.OpenAIChatResponse response = new OpenAIProviderClient.OpenAIChatResponse();
        response.choices = List.of(choice);

        assertThatThrownBy(() -> client.extractChatText(response, 300))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("truncated")
                .hasMessageContaining("300");
    }

    @Test
    void rejectsBlankChatResponseWithFinishReason() {
        OpenAIProviderClient client = client("none");
        OpenAIProviderClient.Choice choice = new OpenAIProviderClient.Choice();
        choice.finish_reason = "stop";
        choice.message = message("   ");
        OpenAIProviderClient.OpenAIChatResponse response = new OpenAIProviderClient.OpenAIChatResponse();
        response.choices = List.of(choice);

        assertThatThrownBy(() -> client.extractChatText(response, 300))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not include output text")
                .hasMessageContaining("stop");
    }

    @Test
    void rejectsIncompleteResponsesResultEvenWhenItContainsPartialText() {
        OpenAIProviderClient client = client("none");
        OpenAIProviderClient.OpenAIResponsesResponse response = new OpenAIProviderClient.OpenAIResponsesResponse();
        response.status = "incomplete";
        response.output_text = "partial literary translation";
        response.incomplete_details = new OpenAIProviderClient.OpenAIResponsesIncompleteDetails();
        response.incomplete_details.reason = "max_output_tokens";

        assertThatThrownBy(() -> client.extractResponsesText(response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete")
                .hasMessageContaining("max_output_tokens");
    }

    @Test
    void extractsCompleteChatAndResponsesText() {
        OpenAIProviderClient client = client("none");
        OpenAIProviderClient.Choice choice = new OpenAIProviderClient.Choice();
        choice.finish_reason = "stop";
        choice.message = message("  translated text  ");
        OpenAIProviderClient.OpenAIChatResponse chat = new OpenAIProviderClient.OpenAIChatResponse();
        chat.choices = List.of(choice);

        OpenAIProviderClient.OpenAIResponsesResponse responses = new OpenAIProviderClient.OpenAIResponsesResponse();
        responses.status = "completed";
        responses.output_text = "literary text";

        assertThat(client.extractChatText(chat, 300)).isEqualTo("translated text");
        assertThat(client.extractResponsesText(responses)).isEqualTo("literary text");
    }

    private OpenAIProviderClient client(String reasoningEffort) {
        OpenAITranslateProperties properties = new OpenAITranslateProperties();
        properties.setApiKey("test-key");
        properties.setModel("gpt-6-luna");
        properties.setLiteraryModel("gpt-6-luna");
        properties.setReasoningEffort(reasoningEffort);
        return new OpenAIProviderClient(
                properties,
                new PromptProperties(),
                WebClient.builder(),
                new SimpleMeterRegistry()
        );
    }

    private OpenAIProviderClient.Message message(String content) {
        OpenAIProviderClient.Message message = new OpenAIProviderClient.Message();
        message.content = content;
        return message;
    }
}
