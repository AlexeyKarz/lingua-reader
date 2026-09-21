package com.example.reader.auth;

import java.time.Instant;
import java.util.List;

public record CreateApiTokenRequest(
        String name,
        List<String> scopes,
        Instant expiresAt
) {}
