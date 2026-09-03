package com.example.reader.translate;

import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/translate")
public class TranslateController {

    private static final Logger log = LoggerFactory.getLogger(TranslateController.class);

    private final TranslateService svc;

    public TranslateController(TranslateService svc) {
        this.svc = svc;
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
            @RequestParam String target) {

        log.info("User word lookup: {} -> {} ({} chars)", source, target, text.length());
        return svc.word(text, source, target);
    }

    @Timed(
            value = "translate.sentence",
            description = "Literal sentence translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )
    @PostMapping("/sentence")
    public Map<String,String> sentence(@RequestBody Map<String,String> body) {
        var text = body.getOrDefault("text","");
        var source = body.getOrDefault("source","en");
        var target = body.getOrDefault("target","de");
        log.info("User sentence translate: {} -> {} ({} chars)", source, target, text.length());
        return Map.of("translated", svc.sentence(text, source, target));
    }

    @Timed(
            value = "translate.literary",
            description = "Natural literary sentence translation",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )
    @PostMapping("/literary")
    public Map<String,String> literary(@RequestBody Map<String,String> body) {
        var text = body.getOrDefault("text","");
        var source = body.getOrDefault("source","en");
        var target = body.getOrDefault("target","de");
        var context = body.getOrDefault("context","");
        log.info("User literary translate: {} -> {} ({} chars, ctx {} chars)",
                source, target, text.length(), context.length());
        return Map.of("translated", svc.literary(text, source, target, context));
    }

    @Timed(
            value = "translate.explain",
            description = "Sentence explanation requests",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )
    @PostMapping("/explain")
    public Map<String,String> explain(@RequestBody Map<String,String> body) {
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
        String explanation = svc.explain(text, source, target, context);
        return Map.of("explanation", explanation);
    }
}
