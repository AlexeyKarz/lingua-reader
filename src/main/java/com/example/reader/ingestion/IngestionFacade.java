package com.example.reader.ingestion;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class IngestionFacade {
    private static final Logger log = LoggerFactory.getLogger(IngestionFacade.class);

    private final ResourceLoader loader;
    private final EpubExtractor extractor;
    private final SegmentWriter writer;

    public IngestionFacade(ResourceLoader loader, EpubExtractor extractor, SegmentWriter writer) {
        this.loader = loader;
        this.extractor = extractor;
        this.writer = writer;
    }

    public IngestionResult ingest(IngestionRequest rawRequest, IngestionProgressSink sink) throws Exception {
        if (rawRequest == null) {
            throw new IllegalArgumentException("Ingestion request is required");
        }
        IngestionRequest request = rawRequest.withDefaults();
        request.validate();

        publish(sink, "resolve_resource", "Resolving EPUB resource", 0, 0, 0);
        Resource res = resolveResource(request.fileSpec());
        if (!res.exists()) {
            throw new IllegalArgumentException("EPUB not found: " + request.fileSpec());
        }

        publish(sink, "extract", "Extracting EPUB content", 0, 0, 0);
        var doc = extractor.extract(res);

        publish(sink, "upsert_entities", "Upserting work and edition", 0, 0, 0);
        long workId = writer.ensureWork(
                request.workTitle(),
                request.author(),
                request.year(),
                request.originalLang()
        );
        long editionId = writer.ensureEdition(
                workId,
                request.lang(),
                request.publisher(),
                request.translator(),
                request.year(),
                request.fileSpec(),
                "epub3"
        );

        int chapterCount = 0;
        int paragraphCount = 0;
        int sentenceCount = 0;

        publish(sink, "write_segments", "Writing chapters, paragraphs and sentences", 0, 0, 0);
        int chapterOrd = 0;
        for (var ch : doc.chapters()) {
            long chapterId = writer.insertSegment(editionId, "chapter", ++chapterOrd, null, ch.title(), ch.cfi());
            chapterCount++;

            int paragraphOrd = 0;
            for (var para : ch.paragraphs()) {
                long paragraphId = writer.insertSegment(
                        editionId, "paragraph", ++paragraphOrd, chapterId, para.text(), para.cfi()
                );
                paragraphCount++;

                for (var s : extractor.splitSentences(request.lang(), para.text())) {
                    writer.insertSegment(editionId, "sentence", s.ord(), paragraphId, s.text(), s.cfi());
                    sentenceCount++;
                }
            }
            publish(
                    sink,
                    "write_segments",
                    "Processed chapter " + chapterOrd + " / " + doc.chapters().size(),
                    chapterCount,
                    paragraphCount,
                    sentenceCount
            );
        }

        publish(sink, "done", "Ingestion completed", chapterCount, paragraphCount, sentenceCount);
        return new IngestionResult(workId, editionId, chapterCount, paragraphCount, sentenceCount);
    }

    private void publish(IngestionProgressSink sink, String step, String message, int chapters, int paragraphs, int sentences) {
        if (sink != null) {
            sink.update(step, message, chapters, paragraphs, sentences);
        }
    }

    private Resource resolveResource(String fileSpec) {
        Resource res = loader.getResource(fileSpec);
        if (res.exists()) {
            return res;
        }

        if (fileSpec != null && fileSpec.startsWith("classpath:")) {
            String rel = fileSpec.substring("classpath:".length());
            while (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            Path fallback = Path.of("src", "main", "resources").resolve(rel).normalize();
            if (Files.exists(fallback)) {
                log.info("Classpath resource not found, falling back to source file path: {}", fallback.toAbsolutePath());
                return loader.getResource("file:" + fallback.toAbsolutePath());
            }
        }
        return res;
    }
}
