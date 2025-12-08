package com.example.reader.reader;

public record SentenceDto(
        long id,
        String text,
        Long chapterId,
        String chapterTitle
) {}
