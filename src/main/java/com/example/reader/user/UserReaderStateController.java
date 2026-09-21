package com.example.reader.user;

import com.example.reader.auth.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserReaderStateController {
    private final CurrentUserService currentUser;
    private final UserReaderStateRepository readerState;

    public UserReaderStateController(CurrentUserService currentUser, UserReaderStateRepository readerState) {
        this.currentUser = currentUser;
        this.readerState = readerState;
    }

    @GetMapping("/reader-progress")
    public ResponseEntity<ReaderProgressDto> progress(
            Authentication authentication,
            @RequestParam String bookId,
            @RequestParam String source,
            @RequestParam String target
    ) {
        long userId = currentUser.requireUser(authentication).id();
        return readerState.findProgress(userId, bookId, source, target)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/reader-progress/recent")
    public List<ReaderProgressDto> recentProgress(
            Authentication authentication,
            @RequestParam(defaultValue = "5") int limit
    ) {
        long userId = currentUser.requireUser(authentication).id();
        return readerState.recentProgress(userId, limit);
    }

    @PutMapping("/reader-progress")
    public ReaderProgressDto saveProgress(
            Authentication authentication,
            @RequestBody SaveReaderProgressRequest request
    ) {
        return readerState.saveProgress(currentUser.requireUser(authentication).id(), request);
    }

    @GetMapping("/preferences")
    public Map<String, JsonNode> preferences(Authentication authentication) {
        return readerState.preferences(currentUser.requireUser(authentication).id());
    }

    @PutMapping("/preferences/{key}")
    public JsonNode savePreference(
            Authentication authentication,
            @PathVariable String key,
            @RequestBody SaveUserPreferenceRequest request
    ) {
        return readerState.savePreference(
                currentUser.requireUser(authentication).id(),
                key,
                request == null ? null : request.value()
        );
    }
}
