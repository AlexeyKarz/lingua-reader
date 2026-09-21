package com.example.reader.auth;

import java.time.Instant;
import java.util.List;

public record ApiTokenDto(
        long id,
        String name,
        List<String> scopes,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt
) {
    static ApiTokenDto from(ApiToken token) {
        return new ApiTokenDto(
                token.id(),
                token.name(),
                token.scopes(),
                token.createdAt(),
                token.expiresAt(),
                token.lastUsedAt(),
                token.revokedAt()
        );
    }
}
