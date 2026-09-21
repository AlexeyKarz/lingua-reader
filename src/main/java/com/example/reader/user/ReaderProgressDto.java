package com.example.reader.user;

public record ReaderProgressDto(
        String bookId,
        String source,
        String target,
        int page,
        int size,
        long updatedAt
) {}
