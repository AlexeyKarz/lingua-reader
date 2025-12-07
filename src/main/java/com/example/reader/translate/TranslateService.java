package com.example.reader.translate;

import com.example.reader.translate.providers.ProviderClient;
import io.micrometer.core.annotation.Timed;
import org.springframework.stereotype.Service;

@Service
public class TranslateService {

    private final ProviderClient provider;

    public TranslateService(ProviderClient provider) {
        this.provider = provider;
    }

    @Timed(
            value = "translate.service.word",
            description = "Service layer word translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "service"}
    )
    public String word(String text, String source, String target) {
        // maybe later: dictionary cache -> fallback to provider
        return provider.literal(text, source, target);
    }

    @Timed(
            value = "translate.service.sentence",
            description = "Service layer sentence translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "service"}
    )
    public String sentence(String text, String source, String target) {
        return provider.literal(text, source, target);
    }

    @Timed(
            value = "translate.service.explain",
            description = "Service layer explanatory translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "service"}
    )
    public String explainSentence(String text, String source, String target, String bookContext) {
        return provider.explain(text, source, target, bookContext);
    }
}
