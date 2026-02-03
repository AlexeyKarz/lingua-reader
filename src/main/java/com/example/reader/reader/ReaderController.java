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
            @RequestParam(defaultValue = "dp-mlMiniLM-v1-de-en") String method
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
            var meta = repo.fetchWorkMetaByTitle(bookId);
            if (meta != null && meta.originalLang() != null && !meta.originalLang().isBlank()) {
                resolvedSrc = meta.originalLang();
            } else {
                // fallback: use tgt as src to avoid null
                resolvedSrc = tgt;
            }
        }

        try {
            log.info("Reader page requested: bookId='{}' {} -> {} page={} size={} method={}",
                    bookId, resolvedSrc, tgt, page, size, method);

            ReaderRepository.EditionIds ids = repo.findEditionIds(bookId, resolvedSrc, tgt);
            if (ids == null) {
                outcome = "missing_editions";
                log.warn("Reader page request failed: no editions for bookId='{}' src='{}' tgt='{}'",
                        bookId, resolvedSrc, tgt);
                resp = Map.of(
                        "bookId", bookId,
                        "src", resolvedSrc, "tgt", tgt,
                        "page", page, "size", size,
                        "left", List.of(),
                        "right", List.of(),
                        "alignments", List.of(),
                        "totalSrc", 0,
                        "totalTgt", 0
                );
                return resp;
            }

            long workId = ids.workId();
            long srcEd  = ids.srcEditionId();
            long tgtEd  = ids.tgtEditionId();
            ReaderRepository.WorkMeta meta = repo.fetchWorkMeta(workId);
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
                    workId, srcEd, tgtEd, method, leftIds
            );

            // Fallback: if no alignments for requested method, try any method for this pair
            if (links.isEmpty()) {
                log.warn("No alignments found for method {} on bookId='{}' {}->{}, falling back to any method",
                        method, bookId, src, tgt);
                registry.counter(
                        "reader.page.fallback",
                        "workId", workIdTag,
                        "src", resolvedSrc,
                        "tgt", tgt,
                        "method", method
                ).increment();
                links = repo.fetchAlignmentsForLeftIdsAnyMethod(workId, srcEd, tgtEd, leftIds);
            }

            List<SentenceDto> right;

            if (links.isEmpty()) {
                // no alignments on this page: for now show nothing on the right;
                // later you can fall back to a simple paging of tgt if you want.
                right = List.of();
            } else {
                // 3) get the min and max rightId for this page
                List<Long> rightIds = links.stream()
                        .map(AlignmentLinkDto::rightId)
                        .distinct()
                        .sorted()
                        .toList();

                long minRightId = rightIds.get(0);
                long maxRightId = rightIds.get(rightIds.size() - 1);

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
            resp.put("page", page);
            resp.put("size", size);
            resp.put("totalSrc", totalSrc);
            resp.put("totalTgt", totalTgt);
            resp.put("left", left);
            resp.put("right", right);
            resp.put("alignments", links);
            hasAlignments = links.isEmpty() ? "false" : "true";
            return resp;
        } finally {
            recordReaderMetrics(sample, outcome, hasAlignments, workIdTag, srcEditionTag, tgtEditionTag, resolvedSrc, tgt, method, size);
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
