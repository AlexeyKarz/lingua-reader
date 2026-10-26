package com.example.reader.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class UserReaderStateRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public UserReaderStateRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<ReaderProgressDto> findProgress(long userId, String bookId, String source, String target) {
        ValidatedProgress input = validateProgress(bookId, source, target, 0, 1, false);
        return jdbc.query("""
                select book_id_text, source_lang, target_lang, page, page_size, updated_at
                from reader_progress
                where user_id = :userId
                  and book_id_text = :bookId
                  and source_lang = :source
                  and target_lang = :target
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("bookId", input.bookId())
                        .addValue("source", input.source())
                        .addValue("target", input.target()),
                rs -> rs.next() ? Optional.of(mapProgress(rs)) : Optional.empty());
    }

    public List<ReaderProgressDto> recentProgress(long userId, int limit) {
        int sanitizedLimit = Math.max(1, Math.min(limit, 20));
        return jdbc.query("""
                select book_id_text, source_lang, target_lang, page, page_size, updated_at
                from reader_progress
                where user_id = :userId
                order by updated_at desc
                limit :limit
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("limit", sanitizedLimit),
                (rs, rowNum) -> mapProgress(rs));
    }

    public ReaderProgressDto saveProgress(long userId, SaveReaderProgressRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        ValidatedProgress input = validateProgress(
                request.bookId(),
                request.source(),
                request.target(),
                request.page() == null ? 0 : request.page(),
                request.size() == null ? 20 : request.size(),
                true
        );

        jdbc.update("""
                insert into reader_progress(
                    user_id, work_id, book_id_text, source_lang, target_lang, page, page_size, updated_at
                )
                values(
                    :userId, :workId, :bookId, :source, :target, :page, :size, now()
                )
                on conflict(user_id, book_id_text, source_lang, target_lang)
                do update set
                    work_id = excluded.work_id,
                    page = excluded.page,
                    page_size = excluded.page_size,
                    updated_at = now()
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("workId", resolveExistingWorkId(input.bookId()))
                        .addValue("bookId", input.bookId())
                        .addValue("source", input.source())
                        .addValue("target", input.target())
                        .addValue("page", input.page())
                        .addValue("size", input.size()));

        return findProgress(userId, input.bookId(), input.source(), input.target())
                .orElseThrow(() -> new IllegalStateException("Reader progress was not saved"));
    }

    public Map<String, JsonNode> preferences(long userId) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        jdbc.query("""
                select key, value_json
                from user_preference
                where user_id = :userId
                order by key
                """,
                new MapSqlParameterSource("userId", userId),
                rs -> {
                    result.put(rs.getString("key"), readJson(rs.getString("value_json")));
                });
        return result;
    }

    public JsonNode savePreference(long userId, String key, JsonNode value) {
        String normalizedKey = validatePreference(key, value);
        String json = writeJson(value);
        jdbc.update("""
                insert into user_preference(user_id, key, value_json, updated_at)
                values(:userId, :key, cast(:value as jsonb), now())
                on conflict(user_id, key)
                do update set
                    value_json = excluded.value_json,
                    updated_at = now()
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("key", normalizedKey)
                        .addValue("value", json));
        return value;
    }

    private ReaderProgressDto mapProgress(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReaderProgressDto(
                rs.getString("book_id_text"),
                rs.getString("source_lang"),
                rs.getString("target_lang"),
                rs.getInt("page"),
                rs.getInt("page_size"),
                epochMillis(rs.getTimestamp("updated_at"))
        );
    }

    private ValidatedProgress validateProgress(
            String rawBookId,
            String rawSource,
            String rawTarget,
            int page,
            int size,
            boolean validatePageAndSize
    ) {
        String bookId = trimToNull(rawBookId);
        String source = trimLower(rawSource);
        String target = trimLower(rawTarget);
        if (!StringUtils.hasText(bookId)) throw new IllegalArgumentException("bookId is required");
        if (!StringUtils.hasText(source)) throw new IllegalArgumentException("source is required");
        if (!StringUtils.hasText(target)) throw new IllegalArgumentException("target is required");
        if (validatePageAndSize) {
            if (page < 0) throw new IllegalArgumentException("page must be >= 0");
            if (size < 1 || size > 500) throw new IllegalArgumentException("size must be between 1 and 500");
        }
        return new ValidatedProgress(bookId, source, target, page, size);
    }

    private String validatePreference(String key, JsonNode value) {
        String normalized = trimToNull(key);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("preference key is required");
        }
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("preference value is required");
        }
        return switch (normalized) {
            case "reader.followAlignment", "reader.visualAlignment" -> {
                if (!value.isBoolean()) {
                    throw new IllegalArgumentException(normalized + " must be boolean");
                }
                yield normalized;
            }
            case "reader.mobilePaneLayout" -> {
                if (!value.isTextual() || (!"stack".equals(value.asText()) && !"split".equals(value.asText()))) {
                    throw new IllegalArgumentException("reader.mobilePaneLayout must be stack or split");
                }
                yield normalized;
            }
            default -> throw new IllegalArgumentException("Unsupported preference key");
        };
    }

    private JsonNode readJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored preference is not valid JSON", ex);
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Preference value is not valid JSON", ex);
        }
    }

    private Long resolveExistingWorkId(String bookId) {
        Long parsed = parseLong(bookId);
        if (parsed == null) {
            return null;
        }
        List<Long> ids = jdbc.query(
                """
                select id
                from work
                where id = :id
                """,
                new MapSqlParameterSource("id", parsed),
                (rs, rowNum) -> rs.getLong("id")
        );
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static String trimLower(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? "" : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long epochMillis(Timestamp timestamp) {
        return timestamp == null ? 0 : timestamp.toInstant().toEpochMilli();
    }

    private record ValidatedProgress(String bookId, String source, String target, int page, int size) {}
}
