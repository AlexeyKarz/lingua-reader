package com.example.reader.auth;

import com.example.reader.support.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mlr.auth.bootstrap.admin-email=phase1-admin@example.test",
        "mlr.auth.bootstrap.admin-password=phase1-admin-password",
        "mlr.auth.bootstrap.admin-display-name=Phase 1 Admin",
        "mlr.auth.google.enabled=true",
        "mlr.auth.google.client-id=test-google-client",
        "mlr.auth.google.client-secret=test-google-secret",
        "mlr.auth.google.admin-emails=phase1-admin@example.test"
})
@AutoConfigureMockMvc
class AuthControllerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    GoogleOidcAccountService googleAccounts;

    @Test
    void meReturnsAnonymousWhenNoSessionExists() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    void exposesGoogleProviderAndStartsLoginWithSafeReturnPath() throws Exception {
        mvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.google").value(true));

        mvc.perform(get("/api/auth/oauth2/google").param("next", "/admin/ingest"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/oauth2/authorization/google"));
    }

    @Test
    void verifiedConfiguredGoogleAccountLinksToExistingAdmin() {
        OidcUser google = mock(OidcUser.class);
        when(google.getSubject()).thenReturn("phase1-google-subject");
        when(google.getEmail()).thenReturn("PHASE1-ADMIN@example.test");
        when(google.getEmailVerified()).thenReturn(true);
        when(google.getFullName()).thenReturn("Google Admin");

        AppUser linked = googleAccounts.provision(google);

        assertThat(linked.email()).isEqualTo("phase1-admin@example.test");
        assertThat(linked.roles()).contains("ADMIN", "USER");
        assertThat(users.findEmailByOauthIdentity("google", "phase1-google-subject"))
                .contains("phase1-admin@example.test");
    }

    @Test
    void loginCreatesSessionAndMeReturnsBootstrappedAdmin() throws Exception {
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
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.email").value("phase1-admin@example.test"))
                .andExpect(jsonPath("$.user.displayName").value("Phase 1 Admin"))
                .andExpect(jsonPath("$.user.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.user.roles[1]").value("USER"))
                .andReturn();

        Cookie session = sessionCookie(login);
        assertThat(session).isNotNull();

        mvc.perform(get("/api/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.email").value("phase1-admin@example.test"))
                .andExpect(jsonPath("$.user.roles").isArray());
    }

    @Test
    void bootstrappedAdminSessionCanAccessAdminApiWithoutStaticToken() throws Exception {
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

        Cookie session = sessionCookie(login);
        assertThat(session).isNotNull();

        mvc.perform(get("/api/admin/ingest/jobs?limit=1").cookie(session))
                .andExpect(status().isOk());
    }

    @Test
    void nonAdminSessionCannotAccessAdminApi() throws Exception {
        long userId = users.createUser(
                "plain-user@example.test",
                "Plain User",
                passwordEncoder.encode("plain-user-password"),
                true
        );
        users.addRole(userId, "USER");

        MvcResult login = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "plain-user@example.test",
                                  "password": "plain-user-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        Cookie session = sessionCookie(login);
        assertThat(session).isNotNull();

        mvc.perform(get("/api/admin/ingest/jobs?limit=1").cookie(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginRejectsInvalidPassword() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "phase1-admin@example.test",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void logoutClearsAuthenticatedSession() throws Exception {
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

        Cookie session = sessionCookie(login);
        assertThat(session).isNotNull();

        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));

        mvc.perform(get("/api/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    private Cookie sessionCookie(MvcResult result) {
        Cookie springSession = result.getResponse().getCookie("SESSION");
        if (springSession != null) {
            return springSession;
        }
        return result.getResponse().getCookie("JSESSIONID");
    }
}
