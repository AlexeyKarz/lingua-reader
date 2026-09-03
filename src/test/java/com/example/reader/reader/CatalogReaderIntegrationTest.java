package com.example.reader.reader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "translate.provider=stub",
        "mlr.admin.enabled=false"
})
@AutoConfigureMockMvc
class CatalogReaderIntegrationTest {

    private final List<String> createdTitles = new ArrayList<>();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        createdTitles.forEach(this::deleteWork);
        createdTitles.clear();
    }

    @Test
    void catalogListsInsertedWorksWithLanguageEditions() throws Exception {
        long workId = insertWork("CI Test Catalog " + UUID.randomUUID(), "en");
        insertEdition(workId, "en");
        insertEdition(workId, "de");

        String body = mockMvc.perform(get("/api/catalog/books"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode entry = findCatalogEntry(body, workId);
        List<String> langs = StreamSupport.stream(entry.get("langs").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        assertThat(entry.get("title").asText()).startsWith("CI Test Catalog ");
        assertThat(entry.get("author").asText()).isEqualTo("CI Author");
        assertThat(entry.get("description").asText()).isEqualTo("CI Description");
        assertThat(entry.get("year").asInt()).isEqualTo(2026);
        assertThat(entry.get("originalLang").asText()).isEqualTo("en");
        assertThat(langs).containsExactlyInAnyOrder("en", "de");
    }

    @Test
    void readerPagesReturnAlignedSentencesFromDatabase() throws Exception {
        long workId = insertWork("CI Test Reader " + UUID.randomUUID(), "en");
        long srcEdition = insertEdition(workId, "en");
        long tgtEdition = insertEdition(workId, "de");

        long srcChapter = insertSegment(srcEdition, "chapter", 0, null, "Chapter 1");
        long srcParagraph = insertSegment(srcEdition, "paragraph", 0, srcChapter, "Source paragraph");
        long srcSentence = insertSegment(srcEdition, "sentence", 0, srcParagraph, "Hello database.");
        long secondSrcSentence = insertSegment(srcEdition, "sentence", 1, srcParagraph, "Second source sentence.");

        long tgtChapter = insertSegment(tgtEdition, "chapter", 0, null, "Kapitel 1");
        long tgtParagraph = insertSegment(tgtEdition, "paragraph", 0, tgtChapter, "Target paragraph");
        long tgtSentence = insertSegment(tgtEdition, "sentence", 0, tgtParagraph, "Hallo Datenbank.");
        long secondTgtSentence = insertSegment(tgtEdition, "sentence", 1, tgtParagraph, "Zweiter Zielsatz.");

        jdbc.update("update edition set body_start_segment_id = ? where id = ?", srcSentence, srcEdition);
        jdbc.update("update edition set body_start_segment_id = ? where id = ?", tgtSentence, tgtEdition);
        insertAlignment(workId, srcEdition, tgtEdition, srcSentence, tgtSentence, "dp-mlMiniLM-v1-en-de");
        insertAlignment(workId, srcEdition, tgtEdition, secondSrcSentence, secondTgtSentence, "dp-mlMiniLM-v1-en-de");

        String body = mockMvc.perform(get("/api/reader/pages")
                        .param("bookId", String.valueOf(workId))
                        .param("src", "en")
                        .param("tgt", "de")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("bookId").asText()).isEqualTo(String.valueOf(workId));
        assertThat(json.get("src").asText()).isEqualTo("en");
        assertThat(json.get("tgt").asText()).isEqualTo("de");
        assertThat(json.get("totalSrc").asInt()).isEqualTo(2);
        assertThat(json.get("totalTgt").asInt()).isEqualTo(2);
        assertThat(json.get("left").get(0).get("text").asText()).isEqualTo("Hello database.");
        assertThat(json.get("left").get(0).get("chapterTitle").asText()).isEqualTo("Chapter 1");
        assertThat(json.get("right").get(0).get("text").asText()).isEqualTo("Hallo Datenbank.");
        assertThat(json.get("alignments").get(0).get("leftId").asLong()).isEqualTo(srcSentence);
        assertThat(json.get("alignments").get(0).get("rightId").asLong()).isEqualTo(tgtSentence);
    }

    private JsonNode findCatalogEntry(String body, long workId) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        return StreamSupport.stream(root.spliterator(), false)
                .filter(entry -> entry.get("workId").asLong() == workId)
                .findFirst()
                .orElseThrow();
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
                2026,
                originalLang
        );
        assertThat(id).isNotNull();
        return id;
    }

    private long insertEdition(long workId, String lang) {
        Long id = jdbc.queryForObject(
                """
                insert into edition(work_id, lang, source_uri, format)
                values (?, ?, ?, ?)
                returning id
                """,
                Long.class,
                workId,
                lang,
                "test://" + workId + "/" + lang,
                "epub"
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

    private void insertAlignment(
            long workId,
            long srcEdition,
            long tgtEdition,
            long srcSentence,
            long tgtSentence,
            String method
    ) {
        jdbc.update(
                """
                insert into alignment(
                  work_id, edition_src_id, edition_tgt_id, left_segment_id, right_segment_id, method, score
                )
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                workId,
                srcEdition,
                tgtEdition,
                srcSentence,
                tgtSentence,
                method,
                0.99
        );
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
