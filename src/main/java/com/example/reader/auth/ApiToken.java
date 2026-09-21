package com.example.reader.auth;

import java.time.Instant;
import java.util.List;

public record ApiToken(
        long id,
        long userId,
        String name,
        String tokenHash,
        List<String> scopes,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt
) {}
