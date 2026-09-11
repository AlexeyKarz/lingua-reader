package com.example.reader.ingestion;

import java.util.regex.Pattern;

public record GenerateLiteraryEditionRequest(
        String workTitle,
        String sourceLang,
        String targetLang,
        Integer year,
        String publisher,
        String translator,
        Boolean bodyOnly,
        Boolean replaceExisting,
        Integer maxSourceChars,
        Integer chunkMaxChars,
        Integer contextChars,
        String model
) {
    public static final int DEFAULT_MAX_SOURCE_CHARS = 250_000;
    static final int LEGACY_DEFAULT_MAX_SOURCE_CHARS = 120_000;

    private static final String DEFAULT_TRANSLATOR = "AI literary translation";
    private static final int MAX_MODEL_LENGTH = 128;
    private static final Pattern MODEL_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._:/+-]+");

    public GenerateLiteraryEditionRequest withDefaults() {
        return new GenerateLiteraryEditionRequest(
                trimToNull(workTitle),
                lower(trimToNull(sourceLang)),
                lower(trimToNull(targetLang)),
                year,
                trimToNull(publisher),
                trimToNull(translator) == null ? DEFAULT_TRANSLATOR : trimToNull(translator),
                bodyOnly == null || bodyOnly,
                replaceExisting == null || replaceExisting,
                maxSourceChars == null ? DEFAULT_MAX_SOURCE_CHARS : maxSourceChars,
                chunkMaxChars == null ? 1_800 : chunkMaxChars,
                contextChars == null ? 600 : contextChars,
                trimToNull(model)
        );
    }

    public void validate() {
        if (workTitle == null || workTitle.isBlank()) {
            throw new IllegalArgumentException("workTitle is required");
        }
        if (sourceLang == null || sourceLang.isBlank()) {
            throw new IllegalArgumentException("sourceLang is required");
        }
        if (targetLang == null || targetLang.isBlank()) {
            throw new IllegalArgumentException("targetLang is required");
        }
        if (sourceLang.equals(targetLang)) {
            throw new IllegalArgumentException("sourceLang and targetLang must differ");
        }
        if (maxSourceChars == null || maxSourceChars < 1) {
            throw new IllegalArgumentException("maxSourceChars must be >= 1");
        }
        if (chunkMaxChars == null || chunkMaxChars < 1) {
            throw new IllegalArgumentException("chunkMaxChars must be >= 1");
        }
        if (contextChars == null || contextChars < 0) {
            throw new IllegalArgumentException("contextChars must be >= 0");
        }
        if (model != null) {
            if (model.length() > MAX_MODEL_LENGTH) {
                throw new IllegalArgumentException("model must be at most " + MAX_MODEL_LENGTH + " characters");
            }
            if (!MODEL_NAME_PATTERN.matcher(model).matches()) {
                throw new IllegalArgumentException("model contains unsupported characters");
            }
        }
    }

    public IngestionRequest toJobRequest() {
        return new IngestionRequest(
                workTitle,
                targetLang,
                "ai-literary://" + sourceLang + "/" + targetLang + "/" + workTitle,
                year,
                publisher,
                translator,
                null,
                null,
                sourceLang
        );
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }
}
