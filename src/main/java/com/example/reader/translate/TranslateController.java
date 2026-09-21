package com.example.reader.translate;

import com.example.reader.metrics.ProductEventService;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/translate")
public class TranslateController {

    private static final Logger log = LoggerFactory.getLogger(TranslateController.class);

    private final TranslateService svc;
    private final ProductEventService events;

    public TranslateController(TranslateService svc, ProductEventService events) {
        this.svc = svc;
        this.events = events;
    }

    @Timed(
            value = "translate.word",
            description = "Word lookups",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )
    @GetMapping("/word")
    public WordTranslationResult word(
            @RequestParam String text,
            @RequestParam String source,
            @RequestParam String target,
            Authentication authentication) {

        log.info("User word lookup: {} -> {} ({} chars)", source, target, text.length());
        Instant start = Instant.now();
        recordTranslation("translation_requested", authentication, "word", source, target, null, null, text.length(), null, null);
        try {
            WordTranslationResult result = svc.word(text, source, target);
            recordTranslation(
                    "translation_succeeded",
                    authentication,
                    "word",
                    source,
                    target,
                    result.dictionary() ? "dictionary" : svc.providerName(),
                    Duration.between(start, Instant.now()),
                    text.length(),
                    200,
                    Map.of("dictionary", result.dictionary())
            );
            return result;
        } catch (RuntimeException ex) {
            recordTranslation(
                    "translation_failed",
                    authentication,
                    "word",
                    source,
                    target,
                    svc.providerName(),
                    Duration.between(start, Instant.now()),
                    text.length(),
                    500,
                    Map.of("exception", ex.getClass().getSimpleName())
            );
            throw ex;
        }
    }

    @Timed(
            value = "translate.sentence",
            description = "Literal sentence translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )
    @PostMapping("/sentence")
    public Map<String,String> sentence(@RequestBody Map<String,String> body, Authentication authentication) {
        var text = body.getOrDefault("text","");
        var source = body.getOrDefault("source","en");
        var target = body.getOrDefault("target","de");
        log.info("User sentence translate: {} -> {} ({} chars)", source, target, text.length());
        return translateText("sentence", text, source, target, authentication, () -> svc.sentence(text, source, target));
    }

    @Timed(
            value = "translate.literary",
            description = "Natural literary sentence translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )
    @PostMapping("/literary")
    public Map<String,String> literary(@RequestBody Map<String,String> body, Authentication authentication) {
        var text = body.getOrDefault("text","");
        var source = body.getOrDefault("source","en");
        var target = body.getOrDefault("target","de");
        var context = body.getOrDefault("context","");
        log.info("User literary translate: {} -> {} ({} chars, ctx {} chars)",
                source, target, text.length(), context.length());
        return translateText("literary", text, source, target, authentication, () -> svc.literary(text, source, target, context));
    }

    @Timed(
            value = "translate.explain",
            description = "Sentence explanation requests",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )
    @PostMapping("/explain")
    public Map<String,String> explain(@RequestBody Map<String,String> body, Authentication authentication) {
        var text = body.getOrDefault("text","");
        var source = body.getOrDefault("source","");
        var target = body.getOrDefault("target","en");
        var context = body.getOrDefault("context","");
        var translation = body.getOrDefault("translation","");

        if (!translation.isBlank()) {
            context = context + " | Literal translation shown to user: " + translation;
        }

        log.info("User explain request: {} -> {} ({} chars, ctx {} chars)",
                source, target, text.length(), context.length());
        String finalContext = context;
        Instant start = Instant.now();
        recordTranslation("translation_requested", authentication, "explain", source, target, svc.providerName(), null, text.length(), null, null);
        try {
            String explanation = svc.explain(text, source, target, finalContext);
            recordTranslation(
                    "translation_succeeded",
                    authentication,
                    "explain",
                    source,
                    target,
                    svc.providerName(),
                    Duration.between(start, Instant.now()),
                    text.length(),
                    200,
                    Map.of()
            );
            return Map.of("explanation", explanation);
        } catch (RuntimeException ex) {
            recordTranslation(
                    "translation_failed",
                    authentication,
                    "explain",
                    source,
                    target,
                    svc.providerName(),
                    Duration.between(start, Instant.now()),
                    text.length(),
                    500,
                    Map.of("exception", ex.getClass().getSimpleName())
            );
            throw ex;
        }
    }

    private Map<String, String> translateText(
            String operation,
            String text,
            String source,
            String target,
            Authentication authentication,
            java.util.function.Supplier<String> supplier
    ) {
        Instant start = Instant.now();
        recordTranslation("translation_requested", authentication, operation, source, target, svc.providerName(), null, text.length(), null, null);
        try {
            String translated = supplier.get();
            recordTranslation(
                    "translation_succeeded",
                    authentication,
                    operation,
                    source,
                    target,
                    svc.providerName(),
                    Duration.between(start, Instant.now()),
                    text.length(),
                    200,
                    Map.of()
            );
            return Map.of("translated", translated);
        } catch (RuntimeException ex) {
            recordTranslation(
                    "translation_failed",
                    authentication,
                    operation,
                    source,
                    target,
                    svc.providerName(),
                    Duration.between(start, Instant.now()),
                    text.length(),
                    500,
                    Map.of("exception", ex.getClass().getSimpleName())
            );
            throw ex;
        }
    }

    private void recordTranslation(
            String eventType,
            Authentication authentication,
            String operation,
            String source,
            String target,
            String provider,
            Duration latency,
            int textLength,
            Integer statusCode,
            Map<String, ?> metadata
    ) {
        events.record(
                eventType,
                authentication,
                null,
                source,
                target,
                operation,
                provider,
                !eventType.endsWith("_failed"),
                latency,
                statusCode,
                eventType.endsWith("_failed") ? operation + "_failed" : null,
                textLength,
                null,
                metadata
        );
    }
}
