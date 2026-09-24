package com.example.reader.translate.providers;

import java.util.List;

public record LiteraryModelCatalog(
        String provider,
        String defaultModel,
        List<String> models,
        boolean live,
        String warning
) {
    public LiteraryModelCatalog {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
