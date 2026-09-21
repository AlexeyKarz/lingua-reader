package com.example.reader.metrics;

import com.example.reader.support.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
        "mlr.auth.bootstrap.admin-display-name=Phase 1 Admin"
})
@AutoConfigureMockMvc
class AdminMetricsControllerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void metricsRequireAdminSessionAndReturnOverview() throws Exception {
        mvc.perform(get("/api/admin/metrics/overview"))
                .andExpect(status().isForbidden());

        Cookie session = login();
        mvc.perform(get("/api/admin/metrics/overview").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredUsers").isNumber())
                .andExpect(jsonPath("$.translationCallsToday").isNumber())
                .andExpect(jsonPath("$.topLanguagePair").exists());

        mvc.perform(get("/api/admin/metrics/users").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/admin/metrics/translations/daily").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/admin/metrics/translations/providers").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/admin/metrics/translations/language-pairs").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/admin/metrics/reader/language-pairs").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/admin/metrics/vocabulary").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/admin/metrics/reviews").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/admin/metrics/system").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("ok"));
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
