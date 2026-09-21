package com.example.reader.user;

import com.example.reader.auth.CurrentUserService;
import com.example.reader.metrics.ProductEventService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/vocabulary")
public class UserVocabularyController {
    private final CurrentUserService currentUser;
    private final UserVocabularyRepository vocabulary;
    private final ProductEventService events;
    private final ObjectMapper objectMapper;

    public UserVocabularyController(
            CurrentUserService currentUser,
            UserVocabularyRepository vocabulary,
            ProductEventService events,
            ObjectMapper objectMapper
    ) {
        this.currentUser = currentUser;
        this.vocabulary = vocabulary;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<SavedVocabularyItemDto> list(Authentication authentication) {
        return vocabulary.list(currentUser.requireUser(authentication).id());
    }

    @PostMapping
    public SavedVocabularyItemDto save(
            Authentication authentication,
            @RequestBody SaveVocabularyRequest request
    ) {
        SavedVocabularyItemDto saved = vocabulary.save(currentUser.requireUser(authentication).id(), request);
        events.record(
                "vocabulary_saved",
                authentication,
                parseLong(saved.bookId()),
                saved.source(),
                saved.target(),
                saved.kind(),
                null,
                true,
                null,
                200,
                null,
                saved.text().length(),
                null,
                Map.of("dictionary", saved.dictionary())
        );
        return saved;
    }

    @PatchMapping("/{id}")
    public SavedVocabularyItemDto update(
            Authentication authentication,
            @PathVariable String id,
            @RequestBody SaveVocabularyRequest request
    ) {
        SavedVocabularyItemDto updated = vocabulary.update(currentUser.requireUser(authentication).id(), id, request);
        events.record(
                "vocabulary_updated",
                authentication,
                parseLong(updated.bookId()),
                updated.source(),
                updated.target(),
                updated.kind(),
                null,
                true,
                null,
                200,
                null,
                updated.text().length(),
                null,
                Map.of("dictionary", updated.dictionary())
        );
        return updated;
    }

    @PostMapping("/import")
    public VocabularyImportResponse importVocabulary(
            Authentication authentication,
            @RequestBody JsonNode body
    ) {
        long userId = currentUser.requireUser(authentication).id();
        List<SavedVocabularyItemDto> items = importItems(body);
        List<VocabularyImportFailure> failures = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int skipped = 0;

        for (int index = 0; index < items.size(); index += 1) {
            try {
                switch (vocabulary.importItem(userId, items.get(index))) {
                    case CREATED -> created += 1;
                    case UPDATED -> updated += 1;
                    case SKIPPED -> skipped += 1;
                }
            } catch (RuntimeException ex) {
                failures.add(new VocabularyImportFailure(index, ex.getMessage() == null ? "Import item failed" : ex.getMessage()));
            }
        }

        VocabularyImportResponse response = new VocabularyImportResponse(
                created,
                updated,
                skipped,
                failures.size(),
                failures
        );
        events.recordSimple("vocabulary_imported", authentication, Map.of(
                "created", created,
                "updated", updated,
                "skipped", skipped,
                "failed", failures.size()
        ));
        return response;
    }

    @PostMapping("/{id}/review")
    public SavedVocabularyItemDto review(
            Authentication authentication,
            @PathVariable String id,
            @RequestBody ReviewVocabularyRequest request
    ) {
        SavedVocabularyItemDto reviewed = vocabulary.review(currentUser.requireUser(authentication).id(), id, request == null ? null : request.grade());
        events.record(
                "vocabulary_reviewed",
                authentication,
                parseLong(reviewed.bookId()),
                reviewed.source(),
                reviewed.target(),
                reviewed.kind(),
                null,
                true,
                null,
                200,
                null,
                reviewed.text().length(),
                null,
                Map.of("grade", request == null || request.grade() == null ? "" : request.grade())
        );
        return reviewed;
    }

    @DeleteMapping("/{id}")
    public void delete(Authentication authentication, @PathVariable String id) {
        vocabulary.delete(currentUser.requireUser(authentication).id(), id);
        events.recordSimple("vocabulary_deleted", authentication, Map.of("id", id));
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<SavedVocabularyItemDto> importItems(JsonNode body) {
        if (body == null || body.isNull()) {
            throw new IllegalArgumentException("Request body is required");
        }
        JsonNode items = body.isArray() ? body : body.get("items");
        if (items == null || !items.isArray()) {
            throw new IllegalArgumentException("Request body must be an array or contain an items array");
        }
        return objectMapper.convertValue(items, new TypeReference<List<SavedVocabularyItemDto>>() {});
    }
}
