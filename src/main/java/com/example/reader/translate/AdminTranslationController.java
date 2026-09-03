package com.example.reader.translate;

import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/translate")
public class AdminTranslationController {
    private static final Logger log = LoggerFactory.getLogger(AdminTranslationController.class);

    private final TranslateService svc;

    public AdminTranslationController(TranslateService svc) {
        this.svc = svc;
    }

    @Timed(
            value = "translate.admin.literary",
            description = "Admin literary translation for ingestion",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )
    @PostMapping("/literary")
    public Map<String, String> literary(@RequestBody Map<String, String> body) {
        var text = body.getOrDefault("text", "");
        var source = body.getOrDefault("source", "en");
        var target = body.getOrDefault("target", "de");
        var context = body.getOrDefault("context", "");
        log.info("Admin literary translate: {} -> {} ({} chars, ctx {} chars)",
                source, target, text.length(), context.length());
        return Map.of("translated", svc.literary(text, source, target, context));
    }
}
