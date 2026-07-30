package com.example.reader.translate;

import com.example.reader.dictionary.DictionaryNormalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class TranslationCacheRepository {
    private final JdbcTemplate jdbc;

    public TranslationCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> find(String operation, String source, String target, String text, String provider) {
        String normalized = DictionaryNormalizer.normalizeText(text);
        String key = key(operation, source, target, normalized, provider);

        List<String> results = jdbc.query(
                """
                select response_text
                from translation_cache
                where cache_key = ?
                limit 1
                """,
                (rs, rowNum) -> rs.getString("response_text"),
                key
        );

        if (results.isEmpty()) {
            return Optional.empty();
        }

        jdbc.update("""
                update translation_cache
                set hit_count = hit_count + 1,
                    last_accessed_at = now()
                where cache_key = ?
                """, key);
        return Optional.ofNullable(results.getFirst());
    }

    public void save(String operation, String source, String target, String text, String provider, String response) {
        String normalized = DictionaryNormalizer.normalizeText(text);
        if (normalized.isBlank() || response == null || response.isBlank()) {
            return;
        }

        jdbc.update("""
                insert into translation_cache (
                  cache_key, operation, source_lang, target_lang, normalized_text, provider, response_text
                )
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (cache_key) do update set
                  response_text = excluded.response_text,
                  last_accessed_at = now()
                """,
                key(operation, source, target, normalized, provider),
                operation,
                source.toLowerCase(Locale.ROOT),
                target.toLowerCase(Locale.ROOT),
                normalized,
                provider,
                response
        );
    }

    private String key(String operation, String source, String target, String normalizedText, String provider) {
        String raw = String.join("|",
                operation.toLowerCase(Locale.ROOT),
                source.toLowerCase(Locale.ROOT),
                target.toLowerCase(Locale.ROOT),
                provider.toLowerCase(Locale.ROOT),
                normalizedText
        );
        return sha256(raw);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create translation cache key", e);
        }
    }
}
