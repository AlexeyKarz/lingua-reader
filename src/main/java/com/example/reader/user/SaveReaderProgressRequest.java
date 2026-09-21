package com.example.reader.user;

public record SaveReaderProgressRequest(
        String bookId,
        String source,
        String target,
        Integer page,
        Integer size
) {}
