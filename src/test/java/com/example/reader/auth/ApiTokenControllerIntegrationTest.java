package com.example.reader.auth;

import com.example.reader.support.PostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mlr.auth.bootstrap.admin-email=phase1-admin@example.test",
        "mlr.auth.bootstrap.admin-password=phase1-admin-password",
        "mlr.auth.bootstrap.admin-display-name=Phase 1 Admin",
        "mlr.admin.enabled=false"
})
@AutoConfigureMockMvc
class ApiTokenControllerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsListsUsesAndRevokesScopedIngestionToken() throws Exception {
        Cookie session = login();

        MvcResult created = mvc.perform(post("/api/admin/tokens")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Deploy ingestion",
                                  "scopes": ["admin:ingest"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token.name").value("Deploy ingestion"))
                .andExpect(jsonPath("$.token.scopes[0]").value("admin:ingest"))
                .andExpect(jsonPath("$.secret").isString())
                .andReturn();

        String body = created.getResponse().getContentAsString();
        String secret = JsonPath.read(body, "$.secret");
        Number tokenId = JsonPath.read(body, "$.token.id");

        mvc.perform(get("/api/admin/tokens").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Deploy ingestion"))
                .andExpect(jsonPath("$[0].secret").doesNotExist());

        mvc.perform(get("/api/admin/ingest/jobs?limit=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/metrics/overview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/tokens/{id}/revoke", tokenId.longValue())
                        .with(csrf())
                        .cookie(session))
                .andExpect(status().isOk());

        Integer auditCount = jdbc.queryForObject("""
                select count(*)
                from admin_audit_event
                where action in ('api_token_created', 'api_token_revoked')
                  and entity_type = 'api_token'
                  and entity_id = ?
                """, Integer.class, String.valueOf(tokenId.longValue()));
        assertThat(auditCount).isEqualTo(2);

        mvc.perform(get("/api/admin/ingest/jobs?limit=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret))
                .andExpect(status().isForbidden());
    }

    @Test
    void metricsScopedTokenCanReadMetricsButCannotManageTokens() throws Exception {
        Cookie session = login();

        MvcResult created = mvc.perform(post("/api/admin/tokens")
                        .with(csrf())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Metrics",
                                  "scopes": ["admin:metrics"]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String secret = JsonPath.read(created.getResponse().getContentAsString(), "$.secret");

        mvc.perform(get("/api/admin/metrics/overview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/tokens")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret))
                .andExpect(status().isForbidden());
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
