package com.example.reader.reader;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reader")
public class ReaderController {

    private static final Logger log = LoggerFactory.getLogger(ReaderController.class);

    private final ReaderRepository repo;
    private final MeterRegistry registry;

    public ReaderController(ReaderRepository repo, MeterRegistry registry) {
        this.repo = repo;
        this.registry = registry;
    }

    @Timed(
            value = "reader.aligned",
            description = "Fetch aligned paragraphs",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )
    @GetMapping("/aligned")
    public Map<String, Object> aligned(
            @RequestParam String bookId,
            @RequestParam String src,
            @RequestParam String tgt,
            @RequestParam(defaultValue = "0") int page) {

        // still fake for now – real alignment will come after embeddings
        return Map.of(
                "bookId", bookId,
                "src", src, "tgt", tgt, "page", page,
                "left",  List.of("Hello world.", "This is a sample."),
                "right", List.of("Hallo Welt.", "Dies ist ein Beispiel.")
        );
    }

    @Timed(value = "reader.pages", description = "Fetch aligned page")
    @GetMapping("/pages")
    public Map<String, Object> pages(
            @RequestParam String bookId,
            @RequestParam(required = false) String src,
            @RequestParam String tgt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String method
    ) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "ok";
        String hasAlignments = "false";
        String workIdTag = "unknown";
        String srcEditionTag = "unknown";
        String tgtEditionTag = "unknown";
        Map<String, Object> resp;

        // Resolve src if missing: prefer original_lang
        String resolvedSrc = src;
        if (resolvedSrc == null || resolvedSrc.isBlank()) {
            var meta = repo.fetchWorkMetaByBookId(bookId);
            if (meta != null && meta.originalLang() != null && !meta.originalLang().isBlank()) {
                resolvedSrc = meta.originalLang();
            } else {
                // fallback: use tgt as src to avoid null
                resolvedSrc = tgt;
            }
        }

        String resolvedMethod = method;
        if (resolvedMethod == null || resolvedMethod.isBlank()) {
            resolvedMethod = "dp-mlMiniLM-v1-" + resolvedSrc + "-" + tgt;
        }

        try {
            log.info("Reader page requested: bookId='{}' {} -> {} page={} size={} method={}",
                    bookId, resolvedSrc, tgt, page, size, resolvedMethod);

            ReaderRepository.EditionIds ids = repo.findEditionIds(bookId, resolvedSrc, tgt);
            if (ids == null) {
                outcome = "missing_editions";
                log.warn("Reader page request failed: no editions for bookId='{}' src='{}' tgt='{}'",
                        bookId, resolvedSrc, tgt);
                resp = new java.util.HashMap<>();
                resp.put("bookId", bookId);
                resp.put("src", resolvedSrc);
                resp.put("tgt", tgt);
                resp.put("page", page);
                resp.put("size", size);
                resp.put("left", List.of());
                resp.put("right", List.of());
                resp.put("alignments", List.of());
                resp.put("totalSrc", 0);
                resp.put("totalTgt", 0);
                resp.put("targetAiGenerated", false);
                return resp;
            }

            long workId = ids.workId();
            long srcEd  = ids.srcEditionId();
            long tgtEd  = ids.tgtEditionId();
            ReaderRepository.WorkMeta meta = repo.fetchWorkMeta(workId);
            ReaderRepository.EditionMeta targetMeta = repo.fetchEditionMeta(tgtEd);
            workIdTag = String.valueOf(workId);
            srcEditionTag = String.valueOf(srcEd);
            tgtEditionTag = String.valueOf(tgtEd);

            // 1) page DE normally (using your body_start-aware fetchSentencePage)
            Long srcBodyStart = repo.findBodyStartSegmentId(srcEd);   // you already added this helper earlier
            Long tgtBodyStart = repo.findBodyStartSegmentId(tgtEd);

            int totalSrc = repo.countSentencesFrom(srcEd, srcBodyStart);
            int totalTgt = repo.countSentencesFrom(tgtEd, tgtBodyStart);

            List<SentenceDto> left = repo.fetchSentencePage(srcEd, srcBodyStart, page, size);
            List<Long> leftIds = left.stream().map(SentenceDto::id).toList();

            // 2) fetch alignments for just these left IDs
            List<AlignmentLinkDto> links = repo.fetchAlignmentsForLeftIds(
                    workId, srcEd, tgtEd, resolvedMethod, leftIds
            );

            // Fallback: if no alignments for requested method, try any method for this pair
            if (links.isEmpty()) {
                log.warn("No alignments found for method {} on bookId='{}' {}->{}, falling back to any method",
                        resolvedMethod, bookId, resolvedSrc, tgt);
                registry.counter(
                        "reader.page.fallback",
                        "workId", workIdTag,
                        "src", resolvedSrc,
                        "tgt", tgt,
                        "method", resolvedMethod
                ).increment();
                links = repo.fetchAlignmentsForLeftIdsAnyMethod(workId, srcEd, tgtEd, leftIds);
            }

            List<SentenceDto> right;

            if (links.isEmpty()) {
                right = repo.fetchSentencePage(tgtEd, tgtBodyStart, page, size);
            } else {
                // 3) get the min and max rightId for this page
                List<Long> rightIds = links.stream()
                        .map(AlignmentLinkDto::rightId)
                        .distinct()
                        .sorted()
                        .toList();

                long minRightId = rightIds.getFirst();
                long maxRightId = rightIds.getLast();

                // 4) fetch *all* sentences in that contiguous range: aligned + unaligned
                right = repo.fetchSentencesInRange(tgtEd, minRightId, maxRightId);
            }

            log.info("Reader page response: bookId='{}' {}->{} page={} left={} right={} alignments={}",
                    bookId, resolvedSrc, tgt, page, left.size(), right.size(), links.size());

            resp = new java.util.HashMap<>();
            resp.put("bookId", bookId);
            resp.put("src", resolvedSrc);
            resp.put("tgt", tgt);
            resp.put("title", meta.title());
            resp.put("author", meta.author() == null ? "" : meta.author());
            resp.put("description", meta.description() == null ? "" : meta.description());
            resp.put("targetTranslator", targetMeta.translator());
            resp.put("targetFormat", targetMeta.format());
            resp.put("targetAiGenerated", targetMeta.aiGenerated());
            resp.put("page", page);
            resp.put("size", size);
            resp.put("totalSrc", totalSrc);
            resp.put("totalTgt", totalTgt);
            resp.put("left", left);
            resp.put("right", right);
            resp.put("alignments", links);
            hasAlignments = links.isEmpty() ? "false" : "true";
            return resp;
        } catch (Exception e) {
            outcome = "error";
            throw e;
        } finally {
            recordReaderMetrics(sample, outcome, hasAlignments, workIdTag, srcEditionTag, tgtEditionTag, resolvedSrc, tgt, resolvedMethod, size);
        }
    }

    private void recordReaderMetrics(
            Timer.Sample sample,
            String outcome,
            String hasAlignments,
            String workIdTag,
            String srcEditionTag,
            String tgtEditionTag,
            String src,
            String tgt,
            String method,
            int size
    ) {
        Timer timer = Timer.builder("reader.page.fetch")
                .tags(
                        "workId", workIdTag,
                        "editionSrc", srcEditionTag,
                        "editionTgt", tgtEditionTag,
                        "src", src,
                        "tgt", tgt,
                        "method", method,
                        "pageSize", String.valueOf(size),
                        "outcome", outcome,
                        "hasAlignments", hasAlignments
                )
                .publishPercentileHistogram()
                .register(registry);
        sample.stop(timer);
        registry.counter(
                "reader.page.requests",
                "workId", workIdTag,
                "editionSrc", srcEditionTag,
                "editionTgt", tgtEditionTag,
                "src", src,
                "tgt", tgt,
                "method", method,
                "pageSize", String.valueOf(size),
                "outcome", outcome
        ).increment();
    }

    @GetMapping("/front-matter")
    public Map<String, Object> frontMatter(
            @RequestParam String bookId,
            @RequestParam String src,   // e.g. "de"
            @RequestParam String tgt    // e.g. "en"
    ) {
        log.info("Front matter requested: bookId='{}' {} -> {}", bookId, src, tgt);
        ReaderRepository.EditionIds ids = repo.findEditionIds(bookId, src, tgt);
        if (ids == null) {
            return Map.of(
                    "bookId", bookId,
                    "src", src,
                    "tgt", tgt,
                    "left", List.of(),
                    "right", List.of()
            );
        }

        long srcEd  = ids.srcEditionId();
        long tgtEd  = ids.tgtEditionId();

        Long srcBodyStart = repo.findBodyStartSegmentId(srcEd);
        Long tgtBodyStart = repo.findBodyStartSegmentId(tgtEd);

        var leftFm  = repo.fetchFrontMatter(srcEd, srcBodyStart);
        var rightFm = repo.fetchFrontMatter(tgtEd, tgtBodyStart);

        return Map.of(
                "bookId", bookId,
                "src", src,
                "tgt", tgt,
                "left", leftFm,
                "right", rightFm
        );
    }
}
