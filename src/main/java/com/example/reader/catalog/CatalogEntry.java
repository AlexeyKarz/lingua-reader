package com.example.reader.catalog;

import java.util.List;

public record CatalogEntry(
        long workId,         // stable numeric ID
        String title,        // human-readable
        String author,       // nullable
        String description,  // nullable
        Integer year,        // nullable
        List<String> langs,  // e.g. ["de", "en"]
        String originalLang  // nullable; preferred source
) {}
