package com.example.reader.user;

import java.util.List;

public record VocabularyImportResponse(
        int created,
        int updated,
        int skipped,
        int failed,
        List<VocabularyImportFailure> failures
) {}
