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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mlr.auth.bootstrap.admin-email=phase1-admin@example.test",
        "mlr.auth.bootstrap.admin-password=phase1-admin-password",
        "mlr.auth.bootstrap.admin-display-name=Phase 1 Admin"
})
@AutoConfigureMockMvc
class UserReaderStateControllerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void readerStateRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/user/preferences"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/user/reader-progress")
                        .param("bookId", "101")
                        .param("source", "en")
                        .param("target", "de"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/user/reader-progress/recent"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/user/import-local-state")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void savesReaderProgressAndPreferences() throws Exception {
        Cookie session = login();

        mvc.perform(get("/api/user/reader-progress")
                        .cookie(session)
                        .param("bookId", "101")
                        .param("source", "EN")
                        .param("target", "DE"))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/user/reader-progress")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookId": "101",
                                  "source": "EN",
                                  "target": "DE",
                                  "page": 4,
                                  "size": 30
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookId").value("101"))
                .andExpect(jsonPath("$.source").value("en"))
                .andExpect(jsonPath("$.target").value("de"))
                .andExpect(jsonPath("$.page").value(4))
                .andExpect(jsonPath("$.size").value(30));

        mvc.perform(get("/api/user/reader-progress")
                        .cookie(session)
                        .param("bookId", "101")
                        .param("source", "en")
                        .param("target", "de"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(4))
                .andExpect(jsonPath("$.size").value(30));

        mvc.perform(get("/api/user/reader-progress/recent")
                        .cookie(session)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookId").value("101"))
                .andExpect(jsonPath("$[0].source").value("en"))
                .andExpect(jsonPath("$[0].target").value("de"))
                .andExpect(jsonPath("$[0].page").value(4))
                .andExpect(jsonPath("$[0].size").value(30));

        mvc.perform(put("/api/user/preferences/reader.followAlignment")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(true));

        mvc.perform(put("/api/user/preferences/reader.mobilePaneLayout")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"split\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("split"));

        mvc.perform(put("/api/user/preferences/reader.visualAlignment")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(true));

        mvc.perform(get("/api/user/preferences").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['reader.followAlignment']").value(true))
                .andExpect(jsonPath("$.['reader.visualAlignment']").value(true))
                .andExpect(jsonPath("$.['reader.mobilePaneLayout']").value("split"));
    }

    @Test
    void importsLocalReaderStateInOneRequest() throws Exception {
        Cookie session = login();

        mvc.perform(post("/api/user/import-local-state")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "vocabulary": [
                                    {
                                      "kind": "sentence",
                                      "text": "Bulk import phrase",
                                      "source": "EN",
                                      "target": "DE",
                                      "result": "Bulk-Import-Satz",
                                      "dictionary": false,
                                      "bookId": "bulk-import-book",
                                      "title": "Bulk Import Book",
                                      "author": "CI Author",
                                      "page": 2,
                                      "sentenceId": 44
                                    }
                                  ],
                                  "progress": [
                                    {
                                      "bookId": "bulk-import-book",
                                      "source": "EN",
                                      "target": "DE",
                                      "page": 2,
                                      "size": 25
                                    }
                                  ],
                                  "preferences": {
                                    "reader.followAlignment": true,
                                    "reader.mobilePaneLayout": "split"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vocabularyImported").value(1))
                .andExpect(jsonPath("$.progressImported").value(1))
                .andExpect(jsonPath("$.preferencesImported").value(2))
                .andExpect(jsonPath("$.failed").value(0));

        mvc.perform(get("/api/user/vocabulary").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        Integer importedVocabulary = jdbc.queryForObject("""
                select count(*)
                from saved_vocabulary_item
                where normalized_text = 'bulk import phrase'
                  and source_lang = 'en'
                  and target_lang = 'de'
                """, Integer.class);
        assertThat(importedVocabulary).isGreaterThan(0);

        mvc.perform(get("/api/user/reader-progress")
                        .cookie(session)
                        .param("bookId", "bulk-import-book")
                        .param("source", "en")
                        .param("target", "de"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(25));

        mvc.perform(get("/api/user/preferences").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['reader.followAlignment']").value(true))
                .andExpect(jsonPath("$.['reader.mobilePaneLayout']").value("split"));
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
