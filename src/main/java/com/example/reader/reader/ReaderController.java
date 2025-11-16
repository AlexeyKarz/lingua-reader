package com.example.reader.reader;

import io.micrometer.core.annotation.Timed;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @GetMapping("/pages")
    public Map<String, Object> pages(
            @RequestParam String bookId,
            @RequestParam String src,     // original lang (left)
            @RequestParam String tgt,     // translation lang (right)
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "40") int size   // sentences per page
    ) {

        // For now, interpret bookId as work.title (e.g. "Faust (Part I)")
        Long srcEditionId = repo.findEditionIdByWorkTitleAndLang(bookId, src);
        Long tgtEditionId = repo.findEditionIdByWorkTitleAndLang(bookId, tgt);

        if (srcEditionId == null || tgtEditionId == null) {
            return Map.of(
                    "bookId", bookId,
                    "src", src, "tgt", tgt,
                    "page", page, "size", size,
                    "totalSrc", 0, "totalTgt", 0,
                    "left", List.of("No edition found for " + bookId + " / " + src),
                    "right", List.of("No edition found for " + bookId + " / " + tgt)
            );
        }

        int totalSrcSent = repo.countSentences(srcEditionId);
        int totalTgtSent = repo.countSentences(tgtEditionId);

        int totalSrcPages = (int) Math.ceil(totalSrcSent / (double) size);
        int totalTgtPages = (int) Math.ceil(totalTgtSent / (double) size);

        // clamp page to valid range for each side
        int safePageSrc = Math.max(0, Math.min(page, Math.max(0, totalSrcPages - 1)));
        int safePageTgt = Math.max(0, Math.min(page, Math.max(0, totalTgtPages - 1)));

        List<String> left = repo.fetchSentencePage(srcEditionId, safePageSrc, size);
        List<String> right = repo.fetchSentencePage(tgtEditionId, safePageTgt, size);

        return Map.of(
                "bookId", bookId,
                "src", src, "tgt", tgt,
                "page", page,
                "size", size,
                "totalSrc", totalSrcPages,
                "totalTgt", totalTgtPages,
                "left", left,
                "right", right
        );
    }
}
