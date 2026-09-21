package com.example.reader.metrics;

import com.example.reader.auth.AppUser;
import com.example.reader.auth.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

@Service
public class AdminAuditService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final UserRepository users;

    public AdminAuditService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, UserRepository users) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.users = users;
    }

    public void record(
            String action,
            Authentication authentication,
            HttpServletRequest request,
            String entityType,
            String entityId,
            Map<String, ?> metadata
    ) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action is required");
        }
        Optional<AppUser> actor = actor(authentication);
        jdbc.update("""
                insert into admin_audit_event(
                    actor_user_id, actor_email, action, entity_type, entity_id,
                    ip_hash, user_agent, metadata_json
                )
                values(
                    :actorUserId, :actorEmail, :action, :entityType, :entityId,
                    :ipHash, :userAgent, cast(:metadata as jsonb)
                )
                """,
                new MapSqlParameterSource()
                        .addValue("actorUserId", actor.map(AppUser::id).orElse(null))
                        .addValue("actorEmail", actor.map(AppUser::email).orElseGet(() -> authenticationName(authentication)))
                        .addValue("action", action)
                        .addValue("entityType", trimToNull(entityType))
                        .addValue("entityId", trimToNull(entityId))
                        .addValue("ipHash", ipHash(request))
                        .addValue("userAgent", userAgent(request))
                        .addValue("metadata", writeJson(metadata == null ? Map.of() : metadata)));
    }

    public void recordIfAdmin(
            String action,
            Authentication authentication,
            HttpServletRequest request,
            String entityType,
            String entityId,
            Map<String, ?> metadata
    ) {
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            record(action, authentication, request, entityType, entityId, metadata);
        }
    }

    private Optional<AppUser> actor(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return users.findByEmail(authentication.getName());
    }

    private String authenticationName(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? null : name;
    }

    private String ipHash(HttpServletRequest request) {
        if (request == null || request.getRemoteAddr() == null || request.getRemoteAddr().isBlank()) {
            return null;
        }
        String salt = LocalDate.now().getYear() + "-" + LocalDate.now().getDayOfYear();
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest((salt + ":" + request.getRemoteAddr()).getBytes(StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String userAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader("User-Agent");
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    private String writeJson(Map<String, ?> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
