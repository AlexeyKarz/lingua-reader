package com.example.reader.reader;

import io.micrometer.core.annotation.Timed;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reader")
public class ReaderController {

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
}
