package com.example.reader.catalog;

import java.util.List;

public record CatalogEntry(
        String id,           // used as bookId in API; for now == title
        String title,        // human-readable
        String author,       // nullable
        Integer year,        // nullable
        List<String> langs   // e.g. ["de", "en"]
) {}
