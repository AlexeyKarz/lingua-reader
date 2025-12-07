package com.example.reader.translate;

import io.micrometer.core.annotation.Timed;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/translate")
public class TranslateController {

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
    public Map<String,String> word(
            @RequestParam String text,
            @RequestParam String source,
            @RequestParam String target) {

        String translated = svc.word(text, source, target);
        return Map.of(
                "text", text,
                "source", source,
                "target", target,
                "translated", translated
        );
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
        return Map.of("translated", svc.sentence(text, source, target));
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

        String explanation = svc.explain(text, source, target, context);
        return Map.of("explanation", explanation);
    }
}

