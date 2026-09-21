package com.example.reader.user;

public record SaveVocabularyRequest(
        String kind,
        String text,
        String source,
        String target,
        String result,
        Boolean dictionary,
        String bookId,
        String title,
        String author,
        Integer page,
        Long sentenceId
) {}
