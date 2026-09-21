package com.example.reader.catalog;

import com.example.reader.metrics.ProductEventService;
import io.micrometer.core.annotation.Timed;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private static final Logger log = LoggerFactory.getLogger(CatalogController.class);
    private final CatalogRepository repo;
    private final ProductEventService events;

    public CatalogController(CatalogRepository repo, ProductEventService events) {
        this.repo = repo;
        this.events = events;
    }

    @Timed(
            value = "catalog.books",
            description = "List available books",
            histogram = true,
            percentiles = {0.5, 0.95, 0.99},
            extraTags = {"component", "controller"}
    )
    @GetMapping("/books")
    public List<CatalogEntry> books(Authentication authentication) {
        log.info("Catalog fetch requested");
        events.recordSimple("catalog_viewed", authentication, Map.of());
        return repo.listBooks();
    }
}
