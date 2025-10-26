package com.example.reader.reader;

import io.micrometer.core.annotation.Timed;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reader")
public class ReaderController {

    private final BookTextService svc;

    public ReaderController(BookTextService svc) {
        this.svc = svc;
    }

    @Timed(
            value = "reader.aligned",
            description = "Fetch aligned paragraphs",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )

    // TODO fetch aligned paragraphs from storage produced by ingestion
    @Timed(value="reader.paragraphs", description="Fetch aligned paragraphs")
    @GetMapping("/aligned")
    public Map<String,Object> aligned(
            @RequestParam String bookId,
            @RequestParam String src,
            @RequestParam String tgt,
            @RequestParam(defaultValue="0") int page) {

        // placeholder: fake 2-paragraph page
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
            @RequestParam(defaultValue = "250") int size // words per page
    ) {

        int totalSrc = svc.totalPages(bookId, src, size);
        int totalTgt = svc.totalPages(bookId, tgt, size);
        // keep the page index the same for both for now (they'll drift in content length—OK for MVP)
        String left = svc.page(bookId, src, page, size);
        String right = svc.page(bookId, tgt, page, size);
        return Map.of(
                "bookId", bookId,
                "src", src, "tgt", tgt,
                "page", page, "size", size,
                "totalSrc", totalSrc, "totalTgt", totalTgt,
                "left", List.of(left),   // keeps UI unchanged (array of paras)
                "right", List.of(right)
        );
    }

}
