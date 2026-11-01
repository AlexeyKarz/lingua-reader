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
    private final String literaryModel;
    private final String explainModel;
    private final String providerName = "ollama";

    public OllamaProviderClient(OllamaTranslateProperties props, WebClient.Builder builder, MeterRegistry registry) {
        this.client = builder.baseUrl(props.getBaseUrl()).build();
        this.model = props.getModel();
        this.literaryModel = (props.getLiteraryModel() == null || props.getLiteraryModel().isBlank())
                ? props.getModel()
                : props.getLiteraryModel();
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
    public String wordAnalysis(String text, String source, String target, String sentenceContext) {
        String prompt = """
                You are a multilingual lexicographer. Analyse the selected %s word in its sentence context.
                Return ONLY valid JSON with the keys selectedForm, lemma, baseForm, partOfSpeech,
                gender, grammaticalFeatures (array), translations (array), and usageNote.
                Write translations and usageNote in %s. Include a definite article in baseForm for
                nouns when %s uses grammatical articles. Use context to disambiguate meaning and form.

                Selected word: %s
                Sentence context: %s
                """.formatted(source, target, source, text, sentenceContext == null ? "" : sentenceContext);
        return ProviderMetrics.record(
                registry,
                providerName,
                model,
                "word_analysis",
                source,
                target,
                () -> generate(model, prompt)
        );
    }

    @Override
    public String literary(String text, String source, String target, String bookContext) {
        return literary(text, source, target, bookContext, null);
    }

    @Override
    public String literary(String text, String source, String target, String bookContext, String modelOverride) {
        String effectiveModel = effectiveLiteraryModel(modelOverride);
        String prompt = """
                Translate naturally from %s to %s for a reader of literature.
                Preserve authorial tone, imagery, narrative voice, and meaning.
                Adapt idioms, wordplay, sayings, and grammar when a literal version would sound wrong.
                Output only translated prose. Do not explain, summarize, omit, or add commentary.

                Book context:
                %s

                Text:
                %s
                """.formatted(source, target, bookContext == null ? "" : bookContext, text);

        return ProviderMetrics.record(
                registry,
                providerName,
                effectiveModel,
                "literary",
                source,
                target,
                () -> generate(effectiveModel, prompt)
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

    @Override
    public String cleanOcr(String text, String language, String modelOverride) {
        String effectiveModel = effectiveLiteraryModel(modelOverride);
        String prompt = """
                Correct only clear OCR recognition errors in the following %s literary text.
                Preserve historical spelling, dialect, names, meaning, punctuation, paragraph
                boundaries, and style. Do not translate, modernize, summarize, explain, or invent.
                When uncertain, retain the original. Output only the corrected text.

                OCR text:
                %s
                """.formatted(language, text);
        return ProviderMetrics.record(
                registry, providerName, effectiveModel, "ocr_cleanup", language, language,
                () -> generate(effectiveModel, prompt)
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

    private String effectiveLiteraryModel(String modelOverride) {
        return modelOverride == null || modelOverride.isBlank() ? literaryModel : modelOverride.trim();
    }

    public static class OllamaResp {
        public String response;
    }
}
