package com.example.reader.dictionary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

@Repository
public class DictionaryRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DictionaryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<DictionaryEntry> findEntries(String sourceLang, String targetLang, String word) {
        String normalized = DictionaryNormalizer.normalizeWord(word);
        if (normalized.isBlank()) {
            return List.of();
        }

        String source = sourceLang.toLowerCase(Locale.ROOT);
        String target = targetLang.toLowerCase(Locale.ROOT);
        List<DictionaryEntry> direct = queryByNormalized(source, target, normalized, 12);

        Set<String> baseForms = new LinkedHashSet<>();
        for (DictionaryEntry entry : direct) {
            if (!entry.baseNormalized().isBlank() && !entry.baseNormalized().equals(normalized)) {
                baseForms.add(entry.baseNormalized());
            }
            String lemma = DictionaryNormalizer.normalizeWord(entry.lemma());
            if (!lemma.isBlank() && !lemma.equals(normalized)) {
                baseForms.add(lemma);
            }
        }

        List<DictionaryEntry> result = new ArrayList<>(direct);
        Set<Long> seenIds = new LinkedHashSet<>();
        direct.forEach(entry -> seenIds.add(entry.id()));
        for (String baseForm : baseForms) {
            for (DictionaryEntry entry : queryByNormalized(source, target, baseForm, 8)) {
                if (seenIds.add(entry.id())) {
                    result.add(entry);
                }
            }
        }

        result.forEach(this::markHit);
        return result;
    }

    private List<DictionaryEntry> queryByNormalized(String sourceLang, String targetLang, String normalized, int limit) {
        return jdbc.query("""
                        select id, entry_key, source_lang, target_lang, surface, normalized,
                               lemma, part_of_speech, gender, base_surface, base_normalized,
                               form_tags_json::text, definitions_json::text, examples_json::text,
                               translations_json::text, source_name, source_url
                        from dictionary_entry
                        where source_lang = ?
                          and normalized = ?
                          and target_lang in (?, '*')
                        order by
                          case when target_lang = ? then 0 else 1 end,
                          case when jsonb_array_length(translations_json) > 0 then 0 else 1 end,
                          case when base_normalized is null or base_normalized = '' then 0 else 1 end,
                          case when jsonb_array_length(definitions_json) > 0 then 0 else 1 end,
                          hit_count desc,
                          id
                        limit ?
                        """,
                (rs, rowNum) -> new DictionaryEntry(
                        rs.getLong("id"),
                        rs.getString("entry_key"),
                        rs.getString("source_lang"),
                        rs.getString("target_lang"),
                        rs.getString("surface"),
                        rs.getString("normalized"),
                        nullToEmpty(rs.getString("lemma")),
                        nullToEmpty(rs.getString("part_of_speech")),
                        nullToEmpty(rs.getString("gender")),
                        nullToEmpty(rs.getString("base_surface")),
                        nullToEmpty(rs.getString("base_normalized")),
                        readList(rs.getString("form_tags_json")),
                        readList(rs.getString("definitions_json")),
                        readList(rs.getString("examples_json")),
                        readList(rs.getString("translations_json")),
                        nullToEmpty(rs.getString("source_name")),
                        nullToEmpty(rs.getString("source_url"))
                ),
                sourceLang,
                normalized,
                targetLang,
                targetLang,
                limit
        );
    }

    private void markHit(DictionaryEntry entry) {
        jdbc.update("""
                        update dictionary_entry
                        set hit_count = hit_count + 1,
                            last_accessed_at = now()
                        where id = ?
                        """,
                entry.id()
        );
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
