package com.example.reader.auth;

import java.time.Instant;
import java.util.List;

public record AppUser(
        long id,
        String email,
        String displayName,
        String passwordHash,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt,
        List<String> roles
) {}
