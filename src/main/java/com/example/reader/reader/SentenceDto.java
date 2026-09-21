package com.example.reader.reader;

public record SentenceDto(
        long id,
        String text,
        String speaker,
        Long chapterId,
        String chapterTitle
) {}
