package com.example.reader.ingestion;

import com.example.reader.translate.AdminTranslationContext;
import com.example.reader.translate.providers.ProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

@Service
public class OcrCleanupService {
    private static final Logger log = LoggerFactory.getLogger(OcrCleanupService.class);
    private static final double MAX_EDIT_RATIO = 0.20;
    private final ProviderClient provider;

    public OcrCleanupService(ProviderClient provider) {
        this.provider = provider;
    }

    public List<String> clean(List<String> rawPages, Resource source, String language, String model) {
        preserveRaw(rawPages, source);
        List<String> cleaned = new ArrayList<>(rawPages.size());
        for (int index = 0; index < rawPages.size(); index++) {
            String raw = deterministic(rawPages.get(index));
            if (raw.isBlank()) {
                cleaned.add(raw);
                continue;
            }
            String candidate = AdminTranslationContext.call(() -> provider.cleanOcr(raw, language, model));
            candidate = deterministic(stripWrapping(candidate));
            double ratio = editRatio(raw, candidate);
            if (candidate.isBlank() || ratio > MAX_EDIT_RATIO) {
                log.warn("Rejected unsafe OCR cleanup for page {}: editRatio={}", index + 1, ratio);
                cleaned.add(raw);
            } else {
                cleaned.add(candidate);
            }
        }
        return cleaned;
    }

    public List<String> cleanOcrPages(
            List<PdfOcrService.OcrPage> pages,
            Resource source,
            String language,
            String model
    ) {
        List<String> rawPages = pages.stream().map(PdfOcrService.OcrPage::text).toList();
        preserveRaw(rawPages, source);
        List<String> cleaned = new ArrayList<>(pages.size());
        for (int index = 0; index < pages.size(); index++) {
            PdfOcrService.OcrPage page = pages.get(index);
            String raw = deterministic(page.text());
            if (raw.isBlank()) {
                cleaned.add(raw);
                continue;
            }
            String candidate = AdminTranslationContext.call(
                    () -> provider.cleanOcr(raw, page.imagePng(), language, model)
            );
            cleaned.add(acceptedOrRaw(raw, candidate, index));
        }
        return cleaned;
    }

    private String acceptedOrRaw(String raw, String candidate, int index) {
        candidate = deterministic(stripWrapping(candidate));
        double ratio = editRatio(raw, candidate);
        if (candidate.isBlank() || ratio > MAX_EDIT_RATIO) {
            log.warn("Rejected unsafe OCR cleanup for page {}: editRatio={}", index + 1, ratio);
            return raw;
        }
        return candidate;
    }

    String deterministic(String input) {
        if (input == null) return "";
        return Normalizer.normalize(input, Normalizer.Form.NFC)
                .replace('\u00ad', '-')
                .replace('\u00a0', ' ')
                .replaceAll("(?<=\\p{L})-\\R(?=\\p{L})", "")
                .replaceAll("[ \\t]+(?=\\R)", "")
                .strip();
    }

    double editRatio(String before, String after) {
        if (before.equals(after)) return 0;
        int max = Math.max(before.length(), after.length());
        if (max == 0) return 0;
        int[] previous = new int[after.length() + 1];
        int[] current = new int[after.length() + 1];
        for (int j = 0; j <= after.length(); j++) previous[j] = j;
        for (int i = 1; i <= before.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= after.length(); j++) {
                int substitution = previous[j - 1] + (before.charAt(i - 1) == after.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return (double) previous[after.length()] / max;
    }

    private String stripWrapping(String value) {
        if (value == null) return "";
        String result = value.strip();
        if (result.startsWith("```") && result.endsWith("```")) {
            result = result.substring(3, result.length() - 3).replaceFirst("^[a-zA-Z]+\\R", "").strip();
        }
        return result;
    }

    private void preserveRaw(List<String> pages, Resource source) {
        try {
            Path original = source.getFile().toPath();
            Path sidecar = original.resolveSibling(original.getFileName() + ".ocr-raw.txt");
            Files.writeString(sidecar, String.join("\n\n\f\n\n", pages), StandardCharsets.UTF_8);
        } catch (Exception error) {
            log.warn("Could not preserve raw OCR sidecar for {}: {}", source.getFilename(), error.getMessage());
        }
    }
}
