package com.example.reader.metrics;

import com.example.reader.auth.AppUser;
import com.example.reader.auth.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class ProductEventService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final UserRepository users;

    public ProductEventService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, UserRepository users) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.users = users;
    }

    public void record(
            String eventType,
            Authentication authentication,
            Long workId,
            String sourceLang,
            String targetLang,
            String operation,
            String provider,
            Boolean success,
            Duration latency,
            Integer statusCode,
            String errorCode,
            Integer textLength,
            Boolean cacheHit,
            Map<String, ?> metadata
    ) {
        jdbc.update("""
                insert into app_event(
                    event_type, user_id, work_id, source_lang, target_lang, operation,
                    provider, success, latency_ms, status_code, error_code, text_length,
                    cache_hit, metadata_json
                )
                values(
                    :eventType, :userId, :workId, :sourceLang, :targetLang, :operation,
                    :provider, :success, :latencyMs, :statusCode, :errorCode, :textLength,
                    :cacheHit, cast(:metadata as jsonb)
                )
                """,
                new MapSqlParameterSource()
                        .addValue("eventType", eventType)
                        .addValue("userId", userId(authentication))
                        .addValue("workId", resolveExistingWorkId(workId))
                        .addValue("sourceLang", normalize(sourceLang))
                        .addValue("targetLang", normalize(targetLang))
                        .addValue("operation", trimToNull(operation))
                        .addValue("provider", trimToNull(provider))
                        .addValue("success", success)
                        .addValue("latencyMs", latency == null ? null : Math.toIntExact(Math.min(Integer.MAX_VALUE, latency.toMillis())))
                        .addValue("statusCode", statusCode)
                        .addValue("errorCode", trimToNull(errorCode))
                        .addValue("textLength", textLength)
                        .addValue("cacheHit", cacheHit)
                        .addValue("metadata", writeJson(metadata == null ? Map.of() : metadata)));
    }

    public void recordSimple(String eventType, Authentication authentication, Map<String, ?> metadata) {
        record(eventType, authentication, null, null, null, null, null, true, null, null, null, null, null, metadata);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return users.findByEmail(authentication.getName())
                .map(AppUser::id)
                .orElse(null);
    }

    private String writeJson(Map<String, ?> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private Long resolveExistingWorkId(Long workId) {
        if (workId == null) {
            return null;
        }
        List<Long> ids = jdbc.query(
                """
                select id
                from work
                where id = :id
                """,
                new MapSqlParameterSource("id", workId),
                (rs, rowNum) -> rs.getLong("id")
        );
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static String normalize(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
