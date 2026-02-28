package com.example.reader.ingestion;

@FunctionalInterface
public interface IngestionProgressSink {
    void update(String step, String message, int chapters, int paragraphs, int sentences);
}
