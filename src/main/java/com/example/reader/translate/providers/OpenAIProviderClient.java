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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

public class OpenAIProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIProviderClient.class);

    private final WebClient webClient;
    private final MeterRegistry registry;
    private final String model;
    private final String literaryModel;
    private final String ocrCleanupModel;
    private final int timeoutSeconds;
    private final int maxCompletionTokens;
    private final int literaryMaxCompletionTokens;
    private final String literalSystem;
    private final String wordSystem;
    private final String literarySystem;
    private final String explainSystem;
    private final String providerName = "openai";
    private static final Duration MODEL_CACHE_TTL = Duration.ofMinutes(10);
    private static final Set<String> UNSUITABLE_MODEL_MARKERS = Set.of(
            "audio", "chat", "codex", "cyber", "image", "instruct", "realtime", "search", "transcribe", "tts"
    );
    private volatile LiteraryModelCatalog cachedLiteraryModels;
    private volatile Instant literaryModelsCachedAt = Instant.EPOCH;

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
        this.ocrCleanupModel = props.getOcrCleanupModel() == null || props.getOcrCleanupModel().isBlank()
                ? this.literaryModel : props.getOcrCleanupModel();
        this.maxCompletionTokens = props.getMaxCompletionTokens();
        this.literaryMaxCompletionTokens = props.getLiteraryMaxCompletionTokens();
        this.webClient = builder
                .baseUrl("https://api.openai.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .build();
        this.registry = registry;
        log.info("OpenAI provider enabled with model {} and maxCompletionTokens {}", this.model, this.maxCompletionTokens);
        this.literalSystem = prompts.getLiteralSystem();
        this.wordSystem = prompts.getWordSystem();
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
    public String wordAnalysis(String text, String source, String target, String sentenceContext) {
        log.info("Calling OpenAI word analysis {} -> {} ({} chars, ctx {} chars)",
                source, target, text.length(), sentenceContext == null ? 0 : sentenceContext.length());
        String userPrompt = "Source language: " + source +
                "\nTarget language: " + target +
                "\nSelected word: " + text +
                "\nSentence context: " + (sentenceContext == null ? "" : sentenceContext);
        return ProviderMetrics.record(
                registry,
                providerName,
                model,
                "word_analysis",
                source,
                target,
                () -> callChat(model, wordSystem, userPrompt, maxCompletionTokens, 0.1)
        );
    }

    @Override
    public LiteraryModelCatalog literaryModels() {
        LiteraryModelCatalog cached = cachedLiteraryModels;
        if (cached != null && literaryModelsCachedAt.plus(MODEL_CACHE_TTL).isAfter(Instant.now())) {
            return cached;
        }

        synchronized (this) {
            cached = cachedLiteraryModels;
            if (cached != null && literaryModelsCachedAt.plus(MODEL_CACHE_TTL).isAfter(Instant.now())) {
                return cached;
            }
            try {
                OpenAIModelsResponse response = webClient.get()
                        .uri("/v1/models")
                        .retrieve()
                        .bodyToMono(OpenAIModelsResponse.class)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .block();
                List<String> models = response == null || response.data == null
                        ? List.of()
                        : response.data.stream()
                                .map(item -> item.id)
                                .filter(this::isSuitableLiteraryModel)
                                .distinct()
                                .sorted(Comparator.naturalOrder())
                                .toList();
                String warning = models.contains(literaryModel)
                        ? null
                        : "The configured default model is not present in the OpenAI models available to this API project.";
                cached = new LiteraryModelCatalog(providerName, literaryModel, models, true, warning);
            } catch (Exception error) {
                log.warn("Could not refresh OpenAI model catalog: {}", error.toString());
                cached = new LiteraryModelCatalog(
                        providerName,
                        literaryModel,
                        List.of(),
                        false,
                        "Could not verify OpenAI models right now. The server default remains available."
                );
            }
            cachedLiteraryModels = cached;
            literaryModelsCachedAt = Instant.now();
            return cached;
        }
    }

    private boolean isSuitableLiteraryModel(String id) {
        if (id == null) return false;
        String normalized = id.toLowerCase(Locale.ROOT);
        if (!(normalized.startsWith("gpt-4o")
                || normalized.startsWith("gpt-4.1")
                || normalized.startsWith("gpt-5")
                || normalized.startsWith("gpt-6"))) {
            return false;
        }
        return UNSUITABLE_MODEL_MARKERS.stream().noneMatch(normalized::contains);
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
                () -> callResponses(effectiveModel, literarySystem, userPrompt, literaryMaxCompletionTokens)
        );
    }

    @Override
    public String cleanOcr(String text, String language, String modelOverride) {
        return cleanOcr(text, null, language, modelOverride);
    }

    @Override
    public String cleanOcr(String text, byte[] pageImagePng, String language, String modelOverride) {
        String effectiveModel = modelOverride == null || modelOverride.isBlank()
                ? ocrCleanupModel : modelOverride.trim();
        String instructions = """
                Correct OCR errors by comparing the OCR text to the supplied page image when present.
                Return only the corrected text. Transcribe what is visibly printed, preserving the
                language, historical spelling, accents, dialect, names, punctuation, paragraph
                boundaries, verse line breaks, and style. Fix only clear recognition errors, broken
                words, character confusions, and accidental spacing. Never translate, modernize,
                paraphrase, summarize, censor, explain, or infer a different word merely because it
                fits the context. When the image is ambiguous, keep the OCR text.
                """;
        String input = "Language: " + language + "\nOCR text:\n" + text;
        Object responseInput = input;
        if (pageImagePng != null && pageImagePng.length > 0) {
            responseInput = List.of(Map.of(
                    "role", "user",
                    "content", List.of(
                            Map.of("type", "input_text", "text", input),
                            Map.of(
                                    "type", "input_image",
                                    "image_url", "data:image/png;base64," + Base64.getEncoder().encodeToString(pageImagePng),
                                    "detail", "high"
                            )
                    )
            ));
        }
        Object finalInput = responseInput;
        return ProviderMetrics.record(
                registry, providerName, effectiveModel, "ocr_cleanup", language, language,
                () -> callResponses(effectiveModel, instructions, finalInput,
                        Math.max(literaryMaxCompletionTokens, 2500), true)
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

    private String callResponses(String model, String systemPrompt, String userPrompt, int maxTokens) {
        return callResponses(model, systemPrompt, (Object) userPrompt, maxTokens, false);
    }

    private String callResponses(
            String model,
            String systemPrompt,
            Object responseInput,
            int maxTokens,
            boolean minimizeReasoning
    ) {
        long started = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("instructions", systemPrompt);
        body.put("input", responseInput);
        body.put("max_output_tokens", maxTokens);
        body.put("store", false);
        // OCR cleanup is a constrained transcription task. Without this, reasoning models can
        // spend the full output allowance thinking and return no (or truncated) corrected text.
        String normalizedModel = model.toLowerCase(Locale.ROOT);
        if (minimizeReasoning && (normalizedModel.startsWith("gpt-5") || normalizedModel.startsWith("gpt-6"))) {
            body.put("reasoning", Map.of("effort", "none"));
        }

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

    public static class OpenAIModelsResponse {
        public List<OpenAIModel> data;
    }

    public static class OpenAIModel {
        public String id;
    }
}
