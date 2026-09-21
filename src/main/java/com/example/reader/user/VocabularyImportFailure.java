package com.example.reader.user;

public record VocabularyImportFailure(
        int index,
        String message
) {}
