package com.example.reader.dictionary;

import com.example.reader.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "mlr.admin.enabled=false")
class DictionaryWarmupServiceIntegrationTest extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @TempDir Path dictionaryDir;

    @Test
    void importsOnlyTermsUsedByTheIngestedEdition() throws Exception {
        String title = "Dictionary warmup " + UUID.randomUUID();
        long workId = jdbc.queryForObject(
                "insert into work(title, original_lang) values (?, 'el') returning id", Long.class, title);
        long editionId = jdbc.queryForObject(
                "insert into edition(work_id, lang, source_uri, format) values (?, 'el', 'test://dictionary', 'text-play') returning id",
                Long.class, workId);
        long chapterId = jdbc.queryForObject(
                "insert into segment(edition_id, level, ord, text) values (?, 'chapter', 1, 'Play') returning id",
                Long.class, editionId);
        long paragraphId = jdbc.queryForObject(
                "insert into segment(edition_id, level, ord, parent_id, text) values (?, 'paragraph', 1, ?, 'Μήδεια κόσμος') returning id",
                Long.class, editionId, chapterId);
        jdbc.update("insert into segment(edition_id, level, ord, parent_id, text) values (?, 'sentence', 1, ?, 'Μήδεια κόσμος')",
                editionId, paragraphId);

        Files.writeString(dictionaryDir.resolve("el.jsonl"), """
                {"word":"Μήδεια","lang_code":"el","pos":"name","senses":[{"glosses":["Medea"],"translations":[{"code":"ru","word":"Медея"}]}]}
                {"word":"άσχετο","lang_code":"el","pos":"adj","senses":[{"glosses":["irrelevant"]}]}
                """);

        DictionaryWarmupService service = new DictionaryWarmupService(jdbc, mapper, dictionaryDir.toString());
        DictionaryWarmupService.WarmupResult result = service.warmup(
                editionId, "el", List.of("ru"), null, 1, 1, 1);

        try {
            assertThat(result.terms()).isEqualTo(2);
            assertThat(result.imported()).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select translations_json->>0 from dictionary_entry where source_lang='el' and target_lang='ru' and normalized='μήδεια'",
                    String.class)).isEqualTo("Медея");
            assertThat(jdbc.queryForObject(
                    "select count(*) from dictionary_entry where source_lang='el' and normalized='άσχετο'",
                    Integer.class)).isZero();
        } finally {
            jdbc.update("delete from dictionary_entry where source_lang='el' and target_lang='ru' and normalized='μήδεια'");
            jdbc.update("delete from segment where edition_id = ?", editionId);
            jdbc.update("delete from edition where id = ?", editionId);
            jdbc.update("delete from work where id = ?", workId);
        }
    }
}
