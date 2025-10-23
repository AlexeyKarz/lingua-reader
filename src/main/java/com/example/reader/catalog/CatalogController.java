package com.example.reader.catalog;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import io.micrometer.core.annotation.Timed;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    @Timed(
            value = "catalog.books",
            description = "List available books",
            histogram = true, percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )

    // TODO replace with DB later (Postgres)
    @GetMapping("/books")
    public List<Map<String,Object>> books() {
        return List.of(
                Map.of("id","faust","title","Faust","langs", List.of("en","de")),
                Map.of("id","quijote","title","Don Quixote","langs", List.of("es","en"))
        );
    }
}
