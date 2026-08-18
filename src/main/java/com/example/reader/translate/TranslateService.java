package com.example.reader.translate;

import com.example.reader.dictionary.DictionaryLookupResult;
import com.example.reader.dictionary.DictionaryService;
import com.example.reader.translate.providers.ProviderClient;
import io.micrometer.core.annotation.Timed;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TranslateService {

    private static final Logger log = LoggerFactory.getLogger(TranslateService.class);

    private final ProviderClient provider;
    private final DictionaryService dictionaryService;
    private final TranslationCacheRepository cache;
    private final String providerName;

    public TranslateService(
            ProviderClient provider,
            DictionaryService dictionaryService,
            TranslationCacheRepository cache
    ) {
        this.provider = provider;
        this.dictionaryService = dictionaryService;
        this.cache = cache;
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
        log.info("Word translate request: {} -> {} ({} chars)", source, target, text.length());
        DictionaryLookupResult dictionary = dictionaryService.lookup(text, source, target);
        if (dictionary.found()) {
            return new WordTranslationResult(
                    text,
                    source,
                    target,
                    dictionary.formattedText(),
                    true,
                    dictionary.lemma(),
                    dictionary.partOfSpeech(),
                    dictionary.gender(),
                    dictionary.baseSurface(),
                    dictionary.baseNormalized(),
                    dictionary.definitions(),
                    dictionary.examples(),
                    dictionary.translations(),
                    dictionary.entries(),
                    dictionary.sourceName(),
                    dictionary.sourceUrl()
            );
        }

        return WordTranslationResult.provider(text, source, target, cachedLiteral("word", text, source, target));
    }

    @Timed(
            value = "translate.service.sentence",
            description = "Service layer sentence translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "service"}
    )
    public String sentence(String text, String source, String target) {
        log.info("Sentence translate request: {} -> {} ({} chars)", source, target, text.length());
        return cachedLiteral("sentence", text, source, target);
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

    private String cachedLiteral(String operation, String text, String source, String target) {
        return cache.find(operation, source, target, text, providerName)
                .orElseGet(() -> {
                    String translated = provider.literal(text, source, target);
                    if (isCacheable(translated)) {
                        cache.save(operation, source, target, text, providerName, translated);
                    }
                    return translated;
                });
    }

    private boolean isCacheable(String translated) {
        if (translated == null || translated.isBlank()) {
            return false;
        }
        return !translated.contains("Showing demo stub output instead")
                && !translated.startsWith("AI provider is temporarily unavailable")
                && !translated.startsWith("Daily AI limit reached")
                && !translated.startsWith("The public demo reached its daily AI limit");
    }
}
