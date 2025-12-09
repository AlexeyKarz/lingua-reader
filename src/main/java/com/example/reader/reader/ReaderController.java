package com.example.reader.reader;

import io.micrometer.core.annotation.Timed;
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

    public ReaderController(ReaderRepository repo) {
        this.repo = repo;
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
            @RequestParam String src,
            @RequestParam String tgt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "dp-mlMiniLM-v1-de-en") String method
    ) {
        log.info("Reader page requested: bookId='{}' {} -> {} page={} size={} method={}",
                bookId, src, tgt, page, size, method);

        ReaderRepository.EditionIds ids = repo.findEditionIds(bookId, src, tgt);
        if (ids == null) {
            log.warn("Reader page request failed: no editions for bookId='{}' src='{}' tgt='{}'",
                    bookId, src, tgt);
            return Map.of(
                    "bookId", bookId,
                    "src", src, "tgt", tgt,
                    "page", page, "size", size,
                    "left", List.of(),
                    "right", List.of(),
                    "alignments", List.of(),
                    "totalSrc", 0,
                    "totalTgt", 0
            );
        }

        long workId = ids.workId();
        long srcEd  = ids.srcEditionId();
        long tgtEd  = ids.tgtEditionId();
        ReaderRepository.WorkMeta meta = repo.fetchWorkMeta(workId);

        int totalSrc = repo.countSentences(srcEd);
        int totalTgt = repo.countSentences(tgtEd);

        // 1) page DE normally (using your body_start-aware fetchSentencePage)
        Long srcBodyStart = repo.findBodyStartSegmentId(srcEd);   // you already added this helper earlier
        Long tgtBodyStart = repo.findBodyStartSegmentId(tgtEd);

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
                bookId, src, tgt, page, left.size(), right.size(), links.size());

        Map<String,Object> resp = new java.util.HashMap<>();
        resp.put("bookId", bookId);
        resp.put("src", src);
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
        return resp;
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
