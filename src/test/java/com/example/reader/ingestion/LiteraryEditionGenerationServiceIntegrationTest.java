package com.example.reader.ingestion;

import com.example.reader.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "translate.provider=stub",
        "mlr.admin.enabled=false"
})
class LiteraryEditionGenerationServiceIntegrationTest extends PostgresIntegrationTest {

    private final List<String> createdTitles = new ArrayList<>();

    @Autowired
    LiteraryEditionGenerationService service;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        createdTitles.forEach(this::deleteWork);
        createdTitles.clear();
    }

    @Test
    void generatesEditionWhenBodyOnlySourceHasNoBodyStartMarker() throws Exception {
        String title = "CI Test Literary " + UUID.randomUUID();
        long workId = insertWork(title, "el");
        long sourceEditionId = insertEdition(workId, "el", "test://source-el", "epub");
        long chapterId = insertSegment(sourceEditionId, "chapter", 1, null, "Chapter 1");
        long paragraphId = insertSegment(sourceEditionId, "paragraph", 1, chapterId, "Πρώτη πρόταση. Δεύτερη πρόταση.");
        insertSegment(sourceEditionId, "sentence", 1, paragraphId, "Πρώτη πρόταση.");
        insertSegment(sourceEditionId, "sentence", 2, paragraphId, "Δεύτερη πρόταση.");

        IngestionResult result = service.generate(
                new GenerateLiteraryEditionRequest(
                        title,
                        "el",
                        "en",
                        1911,
                        "Generated",
                        "AI",
                        true,
                        true,
                        10_000,
                        1_000,
                        0,
                        null
                ),
                null
        );

        assertThat(result.workId()).isEqualTo(workId);
        assertThat(result.chapters()).isEqualTo(1);
        assertThat(result.paragraphs()).isEqualTo(1);
        assertThat(result.sentences()).isGreaterThan(0);

        String generatedParagraph = jdbc.queryForObject(
                """
                select s.text
                from segment s
                join edition e on e.id = s.edition_id
                where e.work_id = ?
                  and e.lang = 'en'
                  and s.level = 'paragraph'
                order by s.id
                limit 1
                """,
                String.class,
                workId
        );
        assertThat(generatedParagraph).contains("Literary translation stub [el->en]");

        Integer alignmentCount = jdbc.queryForObject(
                """
                select count(*)
                from alignment
                where work_id = ?
                  and edition_src_id = ?
                  and edition_tgt_id = ?
                  and method = 'ai-literary-v1-el-en'
                """,
                Integer.class,
                workId,
                sourceEditionId,
                result.editionId()
        );
        assertThat(alignmentCount).isEqualTo(2);
    }

    @Test
    void estimatesGenerationInReadableUnits() {
        String title = "CI Test Estimate " + UUID.randomUUID();
        long workId = insertWork(title, "el");
        long sourceEditionId = insertEdition(workId, "el", "test://estimate-el", "text-play");
        long chapterId = insertSegment(sourceEditionId, "chapter", 1, null, "Play");
        long paragraphId = insertSegment(sourceEditionId, "paragraph", 1, chapterId, "Πρώτη γραμμή του έργου.");
        insertSegment(sourceEditionId, "sentence", 1, paragraphId, "Πρώτη γραμμή του έργου.");
        jdbc.update("update segment set speaker = 'ΜΗΔΕΙΑ' where id = ?", paragraphId);
        long secondParagraphId = insertSegment(sourceEditionId, "paragraph", 2, chapterId, "Δεύτερη γραμμή του έργου.");
        insertSegment(sourceEditionId, "sentence", 1, secondParagraphId, "Δεύτερη γραμμή του έργου.");
        jdbc.update("update segment set speaker = 'ΜΗΔΕΙΑ' where id = ?", secondParagraphId);

        LiteraryGenerationEstimate estimate = service.estimate(new GenerateLiteraryEditionRequest(
                title, "el", "en", null, null, null, true, true,
                10_000, 1_800, 600, null
        ));

        assertThat(estimate.sourceWords()).isGreaterThan(0);
        assertThat(estimate.estimatedInputTokens()).isGreaterThan(0);
        assertThat(estimate.sourceParagraphs()).isEqualTo(2);
        assertThat(estimate.translationBatches()).isEqualTo(1);
        assertThat(estimate.withinSourceLimit()).isTrue();
        assertThat(estimate.summary()).contains("words", "AI requests", "minutes");
    }

    private long insertWork(String title, String originalLang) {
        createdTitles.add(title);
        Long id = jdbc.queryForObject(
                """
                insert into work(title, author, description, pub_year, original_lang, public_domain)
                values (?, ?, ?, ?, ?, true)
                returning id
                """,
                Long.class,
                title,
                "CI Author",
                "CI Description",
                1911,
                originalLang
        );
        assertThat(id).isNotNull();
        return id;
    }

    private long insertEdition(long workId, String lang, String sourceUri, String format) {
        Long id = jdbc.queryForObject(
                """
                insert into edition(work_id, lang, source_uri, format)
                values (?, ?, ?, ?)
                returning id
                """,
                Long.class,
                workId,
                lang,
                sourceUri,
                format
        );
        assertThat(id).isNotNull();
        return id;
    }

    private long insertSegment(long editionId, String level, int ord, Long parentId, String text) {
        Long id = jdbc.queryForObject(
                """
                insert into segment(edition_id, level, ord, parent_id, text)
                values (?, cast(? as segment_level), ?, ?, ?)
                returning id
                """,
                Long.class,
                editionId,
                level,
                ord,
                parentId,
                text
        );
        assertThat(id).isNotNull();
        return id;
    }

    private void deleteWork(String title) {
        jdbc.update(
                """
                delete from alignment
                where work_id in (select id from work where title = ?)
                """,
                title
        );
        jdbc.update(
                """
                delete from embedding
                where segment_id in (
                  select s.id
                  from segment s
                  join edition e on e.id = s.edition_id
                  join work w on w.id = e.work_id
                  where w.title = ?
                )
                """,
                title
        );
        jdbc.update(
                """
                delete from segment
                where edition_id in (
                  select e.id
                  from edition e
                  join work w on w.id = e.work_id
                  where w.title = ?
                )
                """,
                title
        );
        jdbc.update(
                """
                delete from edition
                where work_id in (select id from work where title = ?)
                """,
                title
        );
        jdbc.update("delete from work where title = ?", title);
    }
}
