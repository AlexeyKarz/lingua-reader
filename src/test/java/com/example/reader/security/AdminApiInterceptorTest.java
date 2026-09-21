package com.example.reader.security;

import com.example.reader.auth.ApiTokenService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminApiInterceptorTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsNotFoundWhenAdminApiIsDisabled() throws Exception {
        AdminAccessProperties properties = new AdminAccessProperties();
        properties.setEnabled(false);
        properties.setToken("secret");
        AdminApiInterceptor interceptor = interceptor(properties);

        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void rejectsMissingTokenWhenAdminApiIsEnabled() throws Exception {
        AdminAccessProperties properties = new AdminAccessProperties();
        properties.setEnabled(true);
        properties.setToken("secret");
        AdminApiInterceptor interceptor = interceptor(properties);

        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void allowsBearerTokenWhenAdminApiIsEnabled() throws Exception {
        AdminAccessProperties properties = new AdminAccessProperties();
        properties.setEnabled(true);
        properties.setToken("secret");
        AdminApiInterceptor interceptor = interceptor(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void allowsAuthenticatedAdminSessionEvenWhenStaticAdminApiIsDisabled() throws Exception {
        AdminAccessProperties properties = new AdminAccessProperties();
        properties.setEnabled(false);
        properties.setToken("");
        AdminApiInterceptor interceptor = interceptor(properties);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin@example.test", "n/a", "ROLE_ADMIN")
        );

        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void allowsScopedApiTokenForIngestionWhenStaticAdminApiIsDisabled() throws Exception {
        AdminAccessProperties properties = new AdminAccessProperties();
        properties.setEnabled(false);
        properties.setToken("");
        ApiTokenService apiTokens = mock(ApiTokenService.class);
        when(apiTokens.allows("api-secret", ApiTokenService.SCOPE_ADMIN_INGEST)).thenReturn(true);
        AdminApiInterceptor interceptor = new AdminApiInterceptor(properties, apiTokens);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/ingest/jobs");
        request.addHeader("Authorization", "Bearer api-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    private AdminApiInterceptor interceptor(AdminAccessProperties properties) {
        return new AdminApiInterceptor(properties, mock(ApiTokenService.class));
    }
}
