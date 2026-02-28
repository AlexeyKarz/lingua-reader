package com.example.reader.ingestion;

public record IngestionResult(
        long workId,
        long editionId,
        int chapters,
        int paragraphs,
        int sentences
) {
}
