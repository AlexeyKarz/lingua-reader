package com.example.reader.user;

import com.example.reader.support.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mlr.auth.bootstrap.admin-email=phase1-admin@example.test",
        "mlr.auth.bootstrap.admin-password=phase1-admin-password",
        "mlr.auth.bootstrap.admin-display-name=Phase 1 Admin"
})
@AutoConfigureMockMvc
class UserVocabularyControllerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void vocabularyRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/user/vocabulary"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unsafeVocabularyWritesRequireCsrf() throws Exception {
        Cookie session = login();

        mvc.perform(post("/api/user/vocabulary")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kind": "word",
                                  "text": "csrf",
                                  "source": "en",
                                  "target": "de",
                                  "result": "csrf",
                                  "bookId": "csrf-book"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void savesDeduplicatesReviewsAndDeletesVocabulary() throws Exception {
        Cookie session = login();

        MvcResult saved = mvc.perform(post("/api/user/vocabulary")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kind": "word",
                                  "text": "das Kind",
                                  "selectedText": "Kindern",
                                  "source": "DE",
                                  "target": "EN",
                                  "result": "Hello world",
                                  "dictionary": false,
                                  "bookId": "101",
                                  "title": "Smoke Book",
                                  "author": "CI Author",
                                  "page": 2,
                                  "sentenceId": 55
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("word"))
                .andExpect(jsonPath("$.text").value("das Kind"))
                .andExpect(jsonPath("$.selectedText").value("Kindern"))
                .andExpect(jsonPath("$.normalizedText").value("das kind"))
                .andExpect(jsonPath("$.source").value("de"))
                .andExpect(jsonPath("$.target").value("en"))
                .andExpect(jsonPath("$.review.status").value("new"))
                .andReturn();

        String id = com.jayway.jsonpath.JsonPath.read(saved.getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/user/vocabulary")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kind": "word",
                                  "text": "das Kind",
                                  "selectedText": "Kinder",
                                  "source": "de",
                                  "target": "en",
                                  "result": "Hi world",
                                  "dictionary": false,
                                  "bookId": "101",
                                  "page": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.result").value("Hi world"))
                .andExpect(jsonPath("$.selectedText").value("Kinder"))
                .andExpect(jsonPath("$.page").value(3));

        mvc.perform(get("/api/user/vocabulary").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThan(0)));

        mvc.perform(post("/api/user/vocabulary/{id}/review", id)
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"known\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.status").value("known"))
                .andExpect(jsonPath("$.review.reviewCount").value(1));

        mvc.perform(delete("/api/user/vocabulary/{id}", id).with(csrf()).cookie(session))
                .andExpect(status().isOk());
    }

    @Test
    void patchesAndImportsVocabularyWithReviewHistory() throws Exception {
        Cookie session = login();

        MvcResult saved = mvc.perform(post("/api/user/vocabulary")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kind": "word",
                                  "text": "Patch me",
                                  "source": "en",
                                  "target": "de",
                                  "result": "Old",
                                  "dictionary": false,
                                  "bookId": "patch-book",
                                  "page": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String id = com.jayway.jsonpath.JsonPath.read(saved.getResponse().getContentAsString(), "$.id");

        mvc.perform(patch("/api/user/vocabulary/{id}", id)
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kind": "word",
                                  "text": "Patch me",
                                  "source": "en",
                                  "target": "de",
                                  "result": "Updated",
                                  "dictionary": true,
                                  "bookId": "patch-book",
                                  "page": 4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("Updated"))
                .andExpect(jsonPath("$.dictionary").value(true))
                .andExpect(jsonPath("$.page").value(4));

        mvc.perform(post("/api/user/vocabulary/import")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {
                                    "id": "local-1",
                                    "kind": "sentence",
                                    "text": "Imported sentence",
                                    "normalizedText": "imported sentence",
                                    "source": "en",
                                    "target": "de",
                                    "result": "Importierter Satz",
                                    "dictionary": false,
                                    "bookId": "import-book",
                                    "title": "Import Book",
                                    "author": "CI Author",
                                    "page": 1,
                                    "sentenceId": 77,
                                    "createdAt": 1800000000000,
                                    "updatedAt": 1800000010000,
                                    "review": {
                                      "status": "review",
                                      "dueAt": 1800000100000,
                                      "intervalDays": 3,
                                      "ease": 2.7,
                                      "reviewCount": 5,
                                      "lastReviewedAt": 1800000005000
                                    }
                                  }
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0));

        mvc.perform(post("/api/user/vocabulary/import")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items": [
                                  {
                                    "id": "local-1",
                                    "kind": "sentence",
                                    "text": "Imported sentence",
                                    "normalizedText": "imported sentence",
                                    "source": "en",
                                    "target": "de",
                                    "result": "Older",
                                    "dictionary": false,
                                    "bookId": "import-book",
                                    "page": 1,
                                    "createdAt": 1700000000000,
                                    "updatedAt": 1700000010000,
                                    "review": {
                                      "status": "new",
                                      "dueAt": 1700000100000,
                                      "intervalDays": 0,
                                      "ease": 2.5,
                                      "reviewCount": 1
                                    }
                                  }
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(1));

        mvc.perform(get("/api/user/vocabulary").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].text").isArray());

        Integer reviewCount = jdbc.queryForObject("""
                select review_count
                from saved_vocabulary_item
                where normalized_text = 'imported sentence'
                  and source_lang = 'en'
                  and target_lang = 'de'
                order by updated_at desc
                limit 1
                """, Integer.class);
        String result = jdbc.queryForObject("""
                select result
                from saved_vocabulary_item
                where normalized_text = 'imported sentence'
                  and source_lang = 'en'
                  and target_lang = 'de'
                order by updated_at desc
                limit 1
                """, String.class);
        assertThat(reviewCount).isEqualTo(5);
        assertThat(result).isEqualTo("Importierter Satz");
    }

    @Test
    void productEventsDoNotPersistRawVocabularyText() throws Exception {
        Cookie session = login();

        mvc.perform(post("/api/user/vocabulary")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kind": "sentence",
                                  "text": "Sensitive raw phrase should not be in events",
                                  "source": "en",
                                  "target": "de",
                                  "result": "Sensitive translation",
                                  "dictionary": false,
                                  "bookId": "privacy-book",
                                  "page": 0
                                }
                                """))
                .andExpect(status().isOk());

        Integer leaked = jdbc.queryForObject("""
                select count(*)
                from app_event
                where metadata_json::text like '%Sensitive raw phrase%'
                   or metadata_json::text like '%Sensitive translation%'
                """, Integer.class);
        assertThat(leaked).isZero();
    }

    private Cookie login() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "phase1-admin@example.test",
                                  "password": "phase1-admin-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = login.getResponse().getCookie("SESSION");
        if (session == null) {
            session = login.getResponse().getCookie("JSESSIONID");
        }
        assertThat(session).isNotNull();
        return session;
    }
}
