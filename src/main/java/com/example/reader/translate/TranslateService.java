package com.example.reader.translate;

import com.example.reader.translate.providers.ProviderClient;
import io.micrometer.core.annotation.Timed;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TranslateService {

    private static final Logger log = LoggerFactory.getLogger(TranslateService.class);

    private final ProviderClient provider;

    public TranslateService(ProviderClient provider) {
        this.provider = provider;
        log.info("Translation provider active: {}", provider.getClass().getSimpleName());
    }

    @Timed(
            value = "translate.service.word",
            description = "Service layer word translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "service"}
    )
    public String word(String text, String source, String target) {
        // maybe later: dictionary cache -> fallback to provider
        log.info("Word translate request: {} -> {} ({} chars)", source, target, text.length());
        return provider.literal(text, source, target);
    }

    @Timed(
            value = "translate.service.sentence",
            description = "Service layer sentence translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "service"}
    )
    public String sentence(String text, String source, String target) {
        log.info("Sentence translate request: {} -> {} ({} chars)", source, target, text.length());
        return provider.literal(text, source, target);
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
}
