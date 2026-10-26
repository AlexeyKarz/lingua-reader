package com.example.reader.ingestion;

import java.util.List;

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
        String contentType,
        Boolean dictionaryWarmup,
        List<String> dictionaryTargetLangs,
        Boolean ocrCleanup,
        String ocrCleanupModel
) {
    public IngestionRequest(String workTitle, String lang, String fileSpec, Integer year,
                            String publisher, String translator, String author, String description,
                            String originalLang, String contentType, Boolean dictionaryWarmup,
                            List<String> dictionaryTargetLangs) {
        this(workTitle, lang, fileSpec, year, publisher, translator, author, description,
                originalLang, contentType, dictionaryWarmup, dictionaryTargetLangs, false, null);
    }
    public IngestionRequest(String workTitle, String lang, String fileSpec, Integer year,
                            String publisher, String translator, String author,
                            String description, String originalLang) {
        this(workTitle, lang, fileSpec, year, publisher, translator, author, description, originalLang, "prose", false, List.of());
    }

    public IngestionRequest(String workTitle, String lang, String fileSpec, Integer year,
                            String publisher, String translator, String author,
                            String description, String originalLang, String contentType) {
        this(workTitle, lang, fileSpec, year, publisher, translator, author, description,
                originalLang, contentType, false, List.of());
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
        boolean normalizedDictionaryWarmup = Boolean.TRUE.equals(dictionaryWarmup);
        String dictionarySourceLang = normalizedLang;
        List<String> normalizedDictionaryTargets = dictionaryTargetLangs == null ? List.of() : dictionaryTargetLangs.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase())
                .filter(value -> !value.equals(dictionarySourceLang))
                .distinct()
                .toList();
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
                normalizedContentType,
                normalizedDictionaryWarmup,
                normalizedDictionaryTargets,
                Boolean.TRUE.equals(ocrCleanup),
                trimToNull(ocrCleanupModel)
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
        if (Boolean.TRUE.equals(dictionaryWarmup) && (dictionaryTargetLangs == null || dictionaryTargetLangs.isEmpty())) {
            throw new IllegalArgumentException("dictionaryTargetLangs is required when dictionaryWarmup is enabled");
        }
        // Multipart metadata is validated before the uploaded file has been persisted and
        // therefore uses this sentinel. The completed request is validated again with the
        // real stored filename by the job service.
        if (Boolean.TRUE.equals(ocrCleanup)
                && !"uploaded-source".equals(fileSpec)
                && !fileSpec.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("AI OCR cleanup is available only for PDF files");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
