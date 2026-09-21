package com.example.reader.user;

public record SavedVocabularyItemDto(
        String id,
        String kind,
        String text,
        String normalizedText,
        String source,
        String target,
        String result,
        boolean dictionary,
        String bookId,
        String title,
        String author,
        int page,
        Long sentenceId,
        long createdAt,
        long updatedAt,
        ReviewStateDto review
) {}
