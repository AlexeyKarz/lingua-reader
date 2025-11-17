package com.example.reader.reader;

import io.micrometer.core.annotation.Timed;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reader")
public class ReaderController {

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
        ReaderRepository.EditionIds ids = repo.findEditionIds(bookId, src, tgt);
        if (ids == null) {
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

        return Map.of(
                "bookId", bookId,
                "src", src,
                "tgt", tgt,
                "page", page,
                "size", size,
                "totalSrc", totalSrc,
                "totalTgt", totalTgt,
                "left", left,
                "right", right,
                "alignments", links
        );
    }

    @GetMapping("/front-matter")
    public Map<String, Object> frontMatter(
            @RequestParam String bookId,
            @RequestParam String src,   // e.g. "de"
            @RequestParam String tgt    // e.g. "en"
    ) {
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

//    @Timed(value = "reader.pages", description = "Fetch aligned page")
//    @GetMapping("/pages")
//    public Map<String, Object> pages(
//            @RequestParam String bookId,
//            @RequestParam String src,
//            @RequestParam String tgt,
//            @RequestParam(defaultValue = "0") int page,
//            @RequestParam(defaultValue = "20") int size,
//            @RequestParam(defaultValue = "dp-mlMiniLM-v1-de-en") String method
//    ) {
//        ReaderRepository.EditionIds ids = repo.findEditionIds(bookId, src, tgt);
//        if (ids == null) {
//            return Map.of(
//                    "bookId", bookId,
//                    "src", src, "tgt", tgt,
//                    "page", page, "size", size,
//                    "left", List.of(),
//                    "right", List.of(),
//                    "alignments", List.of(),
//                    "totalSrc", 0,
//                    "totalTgt", 0
//            );
//        }
//
//        long workId = ids.workId();
//        long srcEd  = ids.srcEditionId();
//        long tgtEd  = ids.tgtEditionId();
//
//        int totalSrc = repo.countSentences(srcEd);
//        int totalTgt = repo.countSentences(tgtEd); // still useful info
//
//        // 1) Find start ids for the body
//        Long srcBodyStart = repo.findBodyStartSegmentId(srcEd);
//        Long tgtBodyStart = repo.findBodyStartSegmentId(tgtEd);
//
//        // 2) Page source sentences (DE)
//        List<SentenceDto> left  = repo.fetchSentencePage(srcEd, srcBodyStart, page, size);
//        List<Long> leftIds = left.stream().map(SentenceDto::id).toList();
//
//        // 3) Get alignments for those left IDs (across the *whole* EN edition)
//        List<AlignmentLinkDto> links = repo.fetchAlignmentsForLeftIds(
//                workId, srcEd, tgtEd, method, leftIds
//        );
//
//        // 4) Collect distinct right IDs and fetch those EN sentences
//        List<Long> rightIds = links.stream()
//                .map(AlignmentLinkDto::rightId)
//                .distinct()
//                .sorted()
//                .toList();
//
//        List<SentenceDto> right = repo.fetchSentencesByIds(rightIds);
//
////        List<SentenceDto> right = repo.fetchSentencePage(tgtEd, tgtBodyStart, page, size);
//
//
//        return Map.of(
//                "bookId", bookId,
//                "src", src,
//                "tgt", tgt,
//                "page", page,
//                "size", size,
//                "totalSrc", totalSrc,
//                "totalTgt", totalTgt,
//                "left", left,
//                "right", right,
//                "alignments", links
//        );
//    }

}
