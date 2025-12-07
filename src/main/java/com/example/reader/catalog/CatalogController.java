package com.example.reader.catalog;

import io.micrometer.core.annotation.Timed;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private static final Logger log = LoggerFactory.getLogger(CatalogController.class);
    private final CatalogRepository repo;

    public CatalogController(CatalogRepository repo) {
        this.repo = repo;
    }

    @Timed(
            value = "catalog.books",
            description = "List available books",
            histogram = true,
            percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )
    @GetMapping("/books")
    public List<CatalogEntry> books() {
        log.info("Catalog fetch requested");
        return repo.listBooks();
    }
}
