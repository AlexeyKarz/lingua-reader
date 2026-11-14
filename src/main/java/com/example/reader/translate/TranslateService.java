package com.example.reader.translate;

import com.example.reader.dictionary.DictionaryLookupResult;
import com.example.reader.dictionary.DictionaryService;
import com.example.reader.translate.providers.ProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.annotation.Timed;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TranslateService {

    private static final Logger log = LoggerFactory.getLogger(TranslateService.class);

    private final ProviderClient provider;
    private final DictionaryService dictionaryService;
    private final TranslationCacheRepository cache;
    private final ObjectMapper objectMapper;
    private final String providerName;

    public TranslateService(
            ProviderClient provider,
            DictionaryService dictionaryService,
            TranslationCacheRepository cache,
            ObjectMapper objectMapper
    ) {
        this.provider = provider;
        this.dictionaryService = dictionaryService;
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.providerName = provider.getClass().getSimpleName();
        log.info("Translation provider active: {}", provider.getClass().getSimpleName());
    }

    @Timed(
            value = "translate.service.word",
            description = "Service layer word translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "service"}
    )
    public WordTranslationResult word(String text, String source, String target) {
        return word(text, source, target, "");
    }

    public WordTranslationResult word(String text, String source, String target, String sentenceContext) {
        log.info("Word translate request: {} -> {} ({} chars)", source, target, text.length());
        DictionaryLookupResult dictionary = dictionaryService.lookup(text, source, target);
        if (dictionary.found()) {
            java.util.List<String> translations = dictionary.translations();
            String formatted = dictionary.formattedText();
            AiWordAnalysis analysis = null;
            if (translations.isEmpty()) {
                analysis = contextualWordAnalysis(
                        text,
                        source,
                        target,
                        wordAnalysisContext(sentenceContext, dictionary)
                );
                if (!analysis.translations().isEmpty()) {
                    translations = analysis.translations();
                    formatted = formatWordAnalysis(analysis);
                }
            }
            return new WordTranslationResult(
                    text,
                    source,
                    target,
                    formatted,
                    true,
                    analysis != null,
                    prefer(analysis == null ? null : analysis.lemma(), dictionary.lemma()),
                    prefer(analysis == null ? null : analysis.partOfSpeech(), dictionary.partOfSpeech()),
                    prefer(analysis == null ? null : analysis.gender(), dictionary.gender()),
                    prefer(analysis == null ? null : analysis.baseForm(), dictionary.baseSurface()),
                    dictionary.baseNormalized(),
                    analysis == null
                            ? dictionary.definitions()
                            : analysis.usageNote().isBlank() ? List.of() : List.of(analysis.usageNote()),
                    dictionary.examples(),
                    translations,
                    analysis == null ? dictionaryFormTags(dictionary) : analysis.grammaticalFeatures(),
                    dictionary.entries(),
                    dictionary.sourceName(),
                    dictionary.sourceUrl()
            );
        }

        AiWordAnalysis analysis = contextualWordAnalysis(text, source, target, sentenceContext);
        return new WordTranslationResult(
                text,
                source,
                target,
                formatWordAnalysis(analysis),
                false,
                true,
                analysis.lemma(),
                analysis.partOfSpeech(),
                analysis.gender(),
                analysis.baseForm(),
                normalizeWord(analysis.baseForm()),
                analysis.usageNote().isBlank() ? List.of() : List.of(analysis.usageNote()),
                List.of(),
                analysis.translations(),
                analysis.grammaticalFeatures(),
                List.of(),
                "AI contextual analysis",
                ""
        );
    }

    @Timed(
            value = "translate.service.sentence",
            description = "Service layer sentence translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "service"}
    )
    public String sentence(String text, String source, String target) {
        log.info("Sentence translate request: {} -> {} ({} chars)", source, target, text.length());
        return cachedProvider("sentence", text, source, target, () -> provider.literal(text, source, target));
    }

    @Timed(
            value = "translate.service.literary",
            description = "Service layer literary translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "service"}
    )
    public String literary(String text, String source, String target, String bookContext) {
        return literary(text, source, target, bookContext, null);
    }

    public String literary(String text, String source, String target, String bookContext, String modelOverride) {
        String normalizedModelOverride = trimToNull(modelOverride);
        log.info("Literary translate request: {} -> {} ({} chars, ctx {} chars, model {})",
                source,
                target,
                text.length(),
                bookContext == null ? 0 : bookContext.length(),
                normalizedModelOverride == null ? "default" : normalizedModelOverride);
        String cacheText = literaryCacheText(text, bookContext);
        return cachedProvider(
                "literary_sentence",
                cacheText,
                source,
                target,
                literaryCacheProviderName(normalizedModelOverride),
                () -> provider.literary(text, source, target, bookContext, normalizedModelOverride)
        );
    }

    @Timed(
            value = "translate.service.explain",
            description = "Service layer explanatory translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "service"}
    )
    public String explain(String text, String source, String target, String bookContext) {
        log.info("Explain request: {} -> {} ({} chars, ctx {} chars)",
                source, target, text.length(), bookContext == null ? 0 : bookContext.length());
        return provider.explain(text, source, target, bookContext);
    }

    public String providerName() {
        return providerName;
    }

    private AiWordAnalysis contextualWordAnalysis(
            String text,
            String source,
            String target,
            String sentenceContext
    ) {
        String context = sentenceContext == null ? "" : sentenceContext.strip();
        String cacheText = context.isBlank()
                ? text
                : "Selected word:\n" + text + "\n\nSentence context:\n" + context;
        String raw = cachedProvider(
                "word_analysis_v2",
                cacheText,
                source,
                target,
                () -> provider.wordAnalysis(text, source, target, context)
        );
        AiWordAnalysis analysis = parseWordAnalysis(raw, text);
        if (analysis.translations().isEmpty()) {
            throw new IllegalStateException("AI word analysis did not include a translation");
        }
        return analysis;
    }

    private String wordAnalysisContext(String sentenceContext, DictionaryLookupResult dictionary) {
        List<String> evidence = new java.util.ArrayList<>();
        if (dictionary != null) {
            if (!clean(dictionary.lemma()).isBlank()) evidence.add("dictionary lemma: " + clean(dictionary.lemma()));
            if (!clean(dictionary.baseSurface()).isBlank()) evidence.add("dictionary base form: " + clean(dictionary.baseSurface()));
            if (dictionary.definitions() != null) {
                dictionary.definitions().stream()
                        .map(this::clean)
                        .filter(value -> !value.isBlank())
                        .limit(6)
                        .map(value -> "dictionary definition: " + value)
                        .forEach(evidence::add);
            }
        }
        String context = sentenceContext == null ? "" : sentenceContext.strip();
        if (evidence.isEmpty()) return context;
        return context + (context.isBlank() ? "" : "\n") + "Dictionary evidence: " + String.join(" | ", evidence);
    }

    private AiWordAnalysis parseWordAnalysis(String raw, String selectedText) {
        if (raw == null || raw.isBlank()) {
            return AiWordAnalysis.translationOnly(selectedText, "");
        }
        String json = raw.strip();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .strip();
        }
        int objectStart = json.indexOf('{');
        int objectEnd = json.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            json = json.substring(objectStart, objectEnd + 1);
        }
        try {
            AiWordAnalysis parsed = objectMapper.readValue(json, AiWordAnalysis.class);
            return normalizeWordAnalysis(parsed, selectedText);
        } catch (Exception error) {
            log.warn("Word-analysis provider returned non-JSON output; using it as a translation: {}", error.getMessage());
            return AiWordAnalysis.translationOnly(selectedText, raw);
        }
    }

    private AiWordAnalysis normalizeWordAnalysis(AiWordAnalysis value, String selectedText) {
        if (value == null) return AiWordAnalysis.translationOnly(selectedText, "");
        String selected = clean(selectedText);
        String lemmaCandidate = prefer(value.lemma(), selected);
        String lemma = sharesLetterScript(lemmaCandidate, selected) ? lemmaCandidate : selected;
        String baseCandidate = prefer(value.baseForm(), lemma);
        String baseForm = sharesLetterScript(baseCandidate, selected) ? baseCandidate : lemma;
        String partOfSpeech = clean(value.partOfSpeech());
        String gender = clean(value.gender());
        List<String> translations = cleanList(value.translations());
        List<String> grammaticalFeatures = cleanList(value.grammaticalFeatures()).stream()
                .filter(feature -> !feature.equalsIgnoreCase(partOfSpeech))
                .filter(feature -> !feature.equalsIgnoreCase(gender))
                .toList();
        String usageNote = clean(value.usageNote());
        if (!usageNote.isBlank() && !translations.isEmpty()
                && !sharesLetterScript(usageNote, String.join(" ", translations))) {
            usageNote = "";
        }
        return new AiWordAnalysis(
                selected,
                lemma,
                baseForm,
                partOfSpeech,
                gender,
                grammaticalFeatures,
                translations,
                usageNote
        );
    }

    private String formatWordAnalysis(AiWordAnalysis analysis) {
        StringBuilder text = new StringBuilder(String.join("; ", analysis.translations()));
        if (!analysis.baseForm().isBlank()) appendLine(text, "Base form: " + analysis.baseForm());
        if (!analysis.partOfSpeech().isBlank()) appendLine(text, "Part of speech: " + analysis.partOfSpeech());
        if (!analysis.gender().isBlank()) appendLine(text, "Gender: " + analysis.gender());
        if (!analysis.grammaticalFeatures().isEmpty()) {
            appendLine(text, "Selected form: " + String.join(", ", analysis.grammaticalFeatures()));
        }
        if (!analysis.usageNote().isBlank()) appendLine(text, analysis.usageNote());
        return text.toString();
    }

    private void appendLine(StringBuilder text, String line) {
        if (!text.isEmpty()) text.append('\n');
        text.append(line);
    }

    private List<String> dictionaryFormTags(DictionaryLookupResult dictionary) {
        if (dictionary.entries() == null) return List.of();
        return dictionary.entries().stream()
                .flatMap(entry -> entry.formTags() == null ? java.util.stream.Stream.empty() : entry.formTags().stream())
                .map(this::clean)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(this::clean).filter(value -> !value.isBlank()).distinct().toList();
    }

    private String prefer(String preferred, String fallback) {
        String cleaned = clean(preferred);
        return cleaned.isBlank() ? clean(fallback) : cleaned;
    }

    private String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private String normalizeWord(String value) {
        return clean(value).toLowerCase(java.util.Locale.ROOT);
    }

    private boolean sharesLetterScript(String candidate, String reference) {
        Set<Character.UnicodeScript> referenceScripts = letterScripts(reference);
        Set<Character.UnicodeScript> candidateScripts = letterScripts(candidate);
        if (referenceScripts.isEmpty()) return true;
        if (candidateScripts.isEmpty()) return false;
        return candidateScripts.stream().anyMatch(referenceScripts::contains);
    }

    private Set<Character.UnicodeScript> letterScripts(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return value.codePoints()
                .filter(Character::isLetter)
                .mapToObj(Character.UnicodeScript::of)
                .filter(script -> script != Character.UnicodeScript.COMMON)
                .filter(script -> script != Character.UnicodeScript.INHERITED)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String cachedProvider(
            String operation,
            String text,
            String source,
            String target,
            Supplier<String> supplier
    ) {
        return cachedProvider(operation, text, source, target, providerName, supplier);
    }

    private String cachedProvider(
            String operation,
            String text,
            String source,
            String target,
            String cacheProviderName,
            Supplier<String> supplier
    ) {
        return cache.find(operation, source, target, text, cacheProviderName)
                .filter(this::isCacheable)
                .orElseGet(() -> {
                    String translated = supplier.get();
                    if (isCacheable(translated)) {
                        cache.save(operation, source, target, text, cacheProviderName, translated);
                    }
                    return translated;
                });
    }

    private String literaryCacheProviderName(String modelOverride) {
        if (modelOverride == null) {
            return providerName;
        }
        return providerName + "|literaryModel=" + modelOverride;
    }

    private String literaryCacheText(String text, String bookContext) {
        if (bookContext == null || bookContext.isBlank()) {
            return text;
        }
        return "Context:\n" + bookContext.strip() + "\n\nText:\n" + text;
    }

    private boolean isCacheable(String translated) {
        if (translated == null || translated.isBlank()) {
            return false;
        }
        return !translated.contains("Showing demo stub output instead")
                && !translated.startsWith("AI provider is temporarily unavailable")
                && !translated.startsWith("Daily AI limit reached")
                && !translated.startsWith("The public demo reached its daily AI limit")
                && !looksLikeRefusal(translated);
    }

    private boolean looksLikeRefusal(String translated) {
        String normalized = translated.toLowerCase(java.util.Locale.ROOT)
                .replace('’', '\'')
                .replace('‘', '\'');
        return normalized.contains("i can't help")
                || normalized.contains("i cannot help")
                || normalized.contains("i can't assist")
                || normalized.contains("i cannot assist")
                || normalized.contains("sorry, but i")
                || normalized.contains("извините, я не могу")
                || normalized.contains("не могу помочь с этой просьбой")
                || normalized.contains("δεν μπορώ να βοηθήσω")
                || normalized.contains("je ne peux pas vous aider")
                || normalized.contains("no puedo ayudar");
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
