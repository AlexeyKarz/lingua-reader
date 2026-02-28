package com.example.reader.ingestion;

public record IngestionRequest(
        String workTitle,
        String lang,
        String fileSpec,
        Integer year,
        String publisher,
        String translator,
        String author,
        String originalLang
) {
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
        String normalizedOriginalLang = trimToNull(originalLang);
        if (normalizedOriginalLang != null) {
            normalizedOriginalLang = normalizedOriginalLang.toLowerCase();
        }
        return new IngestionRequest(
                normalizedTitle,
                normalizedLang,
                normalizedFile,
                normalizedYear,
                normalizedPublisher,
                normalizedTranslator,
                normalizedAuthor,
                normalizedOriginalLang
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
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
