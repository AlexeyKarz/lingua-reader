package com.example.reader.user;

public record UserLocalStateImportFailure(
        String section,
        int index,
        String message
) {}
