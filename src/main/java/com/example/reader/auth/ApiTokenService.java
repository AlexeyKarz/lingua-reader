package com.example.reader.auth;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class ApiTokenService {
    public static final String SCOPE_ADMIN_ALL = "admin:*";
    public static final String SCOPE_ADMIN_INGEST = "admin:ingest";
    public static final String SCOPE_ADMIN_METRICS = "admin:metrics";

    private static final Set<String> ALLOWED_SCOPES = Set.of(
            SCOPE_ADMIN_ALL,
            SCOPE_ADMIN_INGEST,
            SCOPE_ADMIN_METRICS
    );

    private final ApiTokenRepository tokens;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiTokenService(ApiTokenRepository tokens) {
        this.tokens = tokens;
    }

    public CreatedApiTokenResponse create(long userId, CreateApiTokenRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        String name = trimToNull(request.name());
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("name is required");
        }
        List<String> scopes = normalizeScopes(request.scopes());
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("at least one scope is required");
        }
        if (request.expiresAt() != null && request.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }

        String secret = newSecret();
        ApiToken token = tokens.create(userId, name, hash(secret), scopes, request.expiresAt());
        return new CreatedApiTokenResponse(ApiTokenDto.from(token), secret);
    }

    public List<ApiTokenDto> list(long userId) {
        return tokens.listForUser(userId).stream()
                .map(ApiTokenDto::from)
                .toList();
    }

    public void revoke(long userId, long tokenId) {
        tokens.revoke(userId, tokenId);
    }

    public boolean allows(String rawToken, String requiredScope) {
        return authenticate(rawToken)
                .filter(token -> token.scopes().contains(SCOPE_ADMIN_ALL) || token.scopes().contains(requiredScope))
                .map(token -> true)
                .orElse(false);
    }

    public Optional<ApiToken> authenticate(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return Optional.empty();
        }
        return tokens.findValidByHash(hash(rawToken.trim()))
                .map(token -> {
                    tokens.markUsed(token.id());
                    return token;
                });
    }

    private List<String> normalizeScopes(List<String> rawScopes) {
        if (rawScopes == null) return List.of();
        return rawScopes.stream()
                .map(ApiTokenService::normalizeScope)
                .filter(StringUtils::hasText)
                .distinct()
                .peek(scope -> {
                    if (!ALLOWED_SCOPES.contains(scope)) {
                        throw new IllegalArgumentException("Unsupported scope: " + scope);
                    }
                })
                .toList();
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "mlr_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String normalizeScope(String scope) {
        String trimmed = trimToNull(scope);
        return trimmed == null ? "" : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
