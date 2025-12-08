package com.example.reader.ingestion;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class IngestCommand implements CommandLineRunner {
    private final ResourceLoader loader;
    private final EpubExtractor extractor;
    private final SegmentWriter writer;

    public IngestCommand(ResourceLoader loader, EpubExtractor extractor, SegmentWriter writer) {
        this.loader = loader;
        this.extractor = extractor;
        this.writer = writer;
    }

    @Override public void run(String... args) throws Exception {
        if (args.length == 0 || !"ingest".equals(args[0])) return;

        Map<String,String> flags = parseFlags(Arrays.copyOfRange(args, 1, args.length));
        String workTitle = flags.getOrDefault("--work", "Untitled");
        String lang      = flags.getOrDefault("--lang", "en");
        String fileSpec  = flags.getOrDefault("--file", "classpath:books/faust_en.epub");
        Integer year     = Integer.valueOf(flags.getOrDefault("--year", "0"));
        String publisher = flags.getOrDefault("--publisher", null);
        String translator= flags.getOrDefault("--translator", null);
        String author = flags.getOrDefault("--author", null);

        Resource res = loader.getResource(fileSpec);
        if (!res.exists()) throw new IllegalArgumentException("EPUB not found: " + fileSpec);

        // Extract chapters + paragraphs into a normalized structure
        var doc = extractor.extract(res);

        // Upsert work/edition -> returns IDs
        long workId = writer.ensureWork(workTitle, author, year);
        long editionId = writer.ensureEdition(workId, lang, publisher, translator, year, fileSpec, "epub3");

        // Write hierarchy: chapters -> paragraphs -> sentences
        int chOrd = 0;
        for (var ch : doc.chapters()) {
            long chapterId = writer.insertSegment(editionId, "chapter", ++chOrd, null, ch.title(), ch.cfi());
            int pOrd = 0;
            for (var para : ch.paragraphs()) {
                long paraId = writer.insertSegment(editionId, "paragraph", ++pOrd, chapterId, para.text(), para.cfi());
                // sentence split per paragraph (OpenNLP)
                for (var s : extractor.splitSentences(lang, para.text())) {
                    writer.insertSegment(editionId, "sentence", s.ord(), paraId, s.text(), s.cfi());
                    // (Optional next step) compute embeddings and writer.insertEmbedding(segmentId, model, vector)
                }
            }
        }

        System.out.println("Ingested " + workTitle + " [" + lang + "] from " + fileSpec);
        // Exit so CLI runs (bootRun with ingest) terminate instead of leaving the web server running.
        System.exit(0);
    }

    private Map<String,String> parseFlags(String[] arr) {
        Map<String,String> m = new HashMap<>();
        for (int i=0;i<arr.length;i++) {
            if (arr[i].startsWith("--")) {
                String v = (i+1 < arr.length && !arr[i+1].startsWith("--")) ? arr[++i] : "true";
                m.put(arr[i-1], v);
            }
        }
        return m;
    }
}
