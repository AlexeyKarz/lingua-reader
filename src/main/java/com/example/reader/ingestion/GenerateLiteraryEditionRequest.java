package com.example.reader.ingestion;

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
        Integer contextChars
) {
    private static final String DEFAULT_TRANSLATOR = "AI literary translation";

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
                maxSourceChars == null ? 120_000 : maxSourceChars,
                chunkMaxChars == null ? 1_800 : chunkMaxChars,
                contextChars == null ? 600 : contextChars
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
