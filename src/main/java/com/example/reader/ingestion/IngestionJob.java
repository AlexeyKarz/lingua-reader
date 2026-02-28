package com.example.reader.ingestion;

import java.time.Instant;

public record IngestionJob(
        long id,
        String status,
        String step,
        String message,
        String error,
        String workTitle,
        String lang,
        String fileSpec,
        Integer year,
        String author,
        String publisher,
        String translator,
        String originalLang,
        Long workId,
        Long editionId,
        int chapters,
        int paragraphs,
        int sentences,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
