package com.example.reader.dictionary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class DictionaryRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DictionaryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<DictionaryEntry> find(String sourceLang, String targetLang, String word) {
        String normalized = DictionaryNormalizer.normalizeWord(word);
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        List<DictionaryEntry> entries = jdbc.query("""
                select id, source_lang, target_lang, surface, normalized, lemma, part_of_speech, gender,
                       definitions_json::text, examples_json::text, translations_json::text,
                       source_name, source_url
                from dictionary_entry
                where source_lang = ?
                  and normalized = ?
                  and target_lang in (?, '*')
                order by case when target_lang = ? then 0 else 1 end, hit_count desc, id
                limit 1
                """,
                (rs, rowNum) -> new DictionaryEntry(
                        rs.getLong("id"),
                        rs.getString("source_lang"),
                        rs.getString("target_lang"),
                        rs.getString("surface"),
                        rs.getString("normalized"),
                        nullToEmpty(rs.getString("lemma")),
                        nullToEmpty(rs.getString("part_of_speech")),
                        nullToEmpty(rs.getString("gender")),
                        readList(rs.getString("definitions_json")),
                        readList(rs.getString("examples_json")),
                        readList(rs.getString("translations_json")),
                        nullToEmpty(rs.getString("source_name")),
                        nullToEmpty(rs.getString("source_url"))
                ),
                sourceLang.toLowerCase(Locale.ROOT),
                normalized,
                targetLang.toLowerCase(Locale.ROOT),
                targetLang.toLowerCase(Locale.ROOT)
        );

        if (entries.isEmpty()) {
            return Optional.empty();
        }

        DictionaryEntry entry = entries.getFirst();
        jdbc.update("""
                update dictionary_entry
                set hit_count = hit_count + 1,
                    last_accessed_at = now()
                where id = ?
                """, entry.id());
        return Optional.of(entry);
    }

    private List<String> readList(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
