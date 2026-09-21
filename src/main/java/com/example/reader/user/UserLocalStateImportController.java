package com.example.reader.user;

import com.example.reader.auth.CurrentUserService;
import com.example.reader.metrics.ProductEventService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserLocalStateImportController {
    private static final int MAX_VOCABULARY_ITEMS = 1_000;
    private static final int MAX_PROGRESS_ITEMS = 200;
    private static final int MAX_PREFERENCES = 20;

    private final CurrentUserService currentUser;
    private final UserVocabularyRepository vocabulary;
    private final UserReaderStateRepository readerState;
    private final ProductEventService events;

    public UserLocalStateImportController(
            CurrentUserService currentUser,
            UserVocabularyRepository vocabulary,
            UserReaderStateRepository readerState,
            ProductEventService events
    ) {
        this.currentUser = currentUser;
        this.vocabulary = vocabulary;
        this.readerState = readerState;
        this.events = events;
    }

    @PostMapping("/import-local-state")
    public UserLocalStateImportResponse importLocalState(
            Authentication authentication,
            @RequestBody UserLocalStateImportRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }

        long userId = currentUser.requireUser(authentication).id();
        List<SaveVocabularyRequest> vocabularyItems = nullToEmpty(request.vocabulary());
        List<SaveReaderProgressRequest> progressItems = nullToEmpty(request.progress());
        Map<String, JsonNode> preferences = request.preferences() == null ? Map.of() : request.preferences();
        validateBatchSizes(vocabularyItems, progressItems, preferences);

        List<UserLocalStateImportFailure> failures = new ArrayList<>();
        int vocabularyImported = importVocabulary(userId, vocabularyItems, failures);
        int progressImported = importProgress(userId, progressItems, failures);
        int preferencesImported = importPreferences(userId, preferences, failures);

        UserLocalStateImportResponse response = new UserLocalStateImportResponse(
                vocabularyImported,
                progressImported,
                preferencesImported,
                failures.size(),
                failures
        );

        events.recordSimple("local_state_imported", authentication, Map.of(
                "vocabularyImported", response.vocabularyImported(),
                "progressImported", response.progressImported(),
                "preferencesImported", response.preferencesImported(),
                "failed", response.failed()
        ));
        return response;
    }

    private int importVocabulary(
            long userId,
            List<SaveVocabularyRequest> vocabularyItems,
            List<UserLocalStateImportFailure> failures
    ) {
        int imported = 0;
        for (int index = 0; index < vocabularyItems.size(); index += 1) {
            try {
                vocabulary.save(userId, vocabularyItems.get(index));
                imported += 1;
            } catch (RuntimeException ex) {
                failures.add(failure("vocabulary", index, ex));
            }
        }
        return imported;
    }

    private int importProgress(
            long userId,
            List<SaveReaderProgressRequest> progressItems,
            List<UserLocalStateImportFailure> failures
    ) {
        int imported = 0;
        for (int index = 0; index < progressItems.size(); index += 1) {
            try {
                readerState.saveProgress(userId, progressItems.get(index));
                imported += 1;
            } catch (RuntimeException ex) {
                failures.add(failure("progress", index, ex));
            }
        }
        return imported;
    }

    private int importPreferences(
            long userId,
            Map<String, JsonNode> preferences,
            List<UserLocalStateImportFailure> failures
    ) {
        int imported = 0;
        int index = 0;
        for (Map.Entry<String, JsonNode> entry : preferences.entrySet()) {
            try {
                readerState.savePreference(userId, entry.getKey(), entry.getValue());
                imported += 1;
            } catch (RuntimeException ex) {
                failures.add(failure("preferences", index, ex));
            }
            index += 1;
        }
        return imported;
    }

    private void validateBatchSizes(
            List<SaveVocabularyRequest> vocabularyItems,
            List<SaveReaderProgressRequest> progressItems,
            Map<String, JsonNode> preferences
    ) {
        if (vocabularyItems.size() > MAX_VOCABULARY_ITEMS) {
            throw new IllegalArgumentException("At most 1000 vocabulary items can be imported at once");
        }
        if (progressItems.size() > MAX_PROGRESS_ITEMS) {
            throw new IllegalArgumentException("At most 200 reader progress items can be imported at once");
        }
        if (preferences.size() > MAX_PREFERENCES) {
            throw new IllegalArgumentException("At most 20 preferences can be imported at once");
        }
    }

    private static UserLocalStateImportFailure failure(String section, int index, RuntimeException ex) {
        String message = ex.getMessage();
        return new UserLocalStateImportFailure(
                section,
                index,
                message == null || message.isBlank() ? "Import item failed" : message
        );
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
