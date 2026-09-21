package com.example.reader.user;

import java.util.List;

public record UserLocalStateImportResponse(
        int vocabularyImported,
        int progressImported,
        int preferencesImported,
        int failed,
        List<UserLocalStateImportFailure> failures
) {}
