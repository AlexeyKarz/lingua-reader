package com.example.reader.ingestion;

public record LiteraryGenerationEstimate(
        int sourceCharacters,
        int sourceWords,
        int sourceParagraphs,
        int translationBatches,
        int estimatedInputTokens,
        int estimatedOutputTokens,
        int estimatedMinutes,
        boolean withinSourceLimit,
        String summary,
        String warning
) {}
