package com.example.reader.ingestion;

public record IngestionRequest(
        String workTitle,
        String lang,
        String fileSpec,
        Integer year,
        String publisher,
        String translator,
        String author,
        String description,
        String originalLang,
        String contentType
) {
    public IngestionRequest(String workTitle, String lang, String fileSpec, Integer year,
                            String publisher, String translator, String author,
                            String description, String originalLang) {
        this(workTitle, lang, fileSpec, year, publisher, translator, author, description, originalLang, "prose");
    }

    public IngestionRequest withDefaults() {
        String normalizedTitle = trimToNull(workTitle);
        String normalizedLang = trimToNull(lang);
        if (normalizedLang != null) {
            normalizedLang = normalizedLang.toLowerCase();
        }
        String normalizedFile = trimToNull(fileSpec);
        Integer normalizedYear = year;
        String normalizedPublisher = trimToNull(publisher);
        String normalizedTranslator = trimToNull(translator);
        String normalizedAuthor = trimToNull(author);
        String normalizedDescription = trimToNull(description);
        String normalizedOriginalLang = trimToNull(originalLang);
        if (normalizedOriginalLang != null) {
            normalizedOriginalLang = normalizedOriginalLang.toLowerCase();
        }
        String normalizedContentType = trimToNull(contentType);
        normalizedContentType = normalizedContentType == null ? "prose" : normalizedContentType.toLowerCase();
        return new IngestionRequest(
                normalizedTitle,
                normalizedLang,
                normalizedFile,
                normalizedYear,
                normalizedPublisher,
                normalizedTranslator,
                normalizedAuthor,
                normalizedDescription,
                normalizedOriginalLang,
                normalizedContentType
        );
    }

    public void validate() {
        if (workTitle == null || workTitle.isBlank()) {
            throw new IllegalArgumentException("workTitle is required");
        }
        if (lang == null || lang.isBlank()) {
            throw new IllegalArgumentException("lang is required");
        }
        if (fileSpec == null || fileSpec.isBlank()) {
            throw new IllegalArgumentException("fileSpec is required");
        }
        if (!"prose".equals(contentType) && !"play".equals(contentType)) {
            throw new IllegalArgumentException("contentType must be 'prose' or 'play'");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
