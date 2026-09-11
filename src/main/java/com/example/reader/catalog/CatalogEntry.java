package com.example.reader.catalog;

import java.util.List;
import java.util.Map;

public record CatalogEntry(
        long workId,         // stable numeric ID
        String title,        // human-readable
        String author,       // nullable
        String description,  // nullable
        Integer year,        // nullable
        List<String> langs,  // e.g. ["de", "en"]
        String originalLang, // nullable; preferred source
        Map<String, EditionMeta> editionMeta
) {
        public record EditionMeta(
                String translator,
                String format,
                boolean aiGenerated
        ) {}
}
