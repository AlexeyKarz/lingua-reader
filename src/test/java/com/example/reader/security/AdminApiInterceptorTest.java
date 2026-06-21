package com.example.reader.security;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminApiInterceptorTest {

    @Test
    void returnsNotFoundWhenAdminApiIsDisabled() throws Exception {
        AdminAccessProperties properties = new AdminAccessProperties();
        properties.setEnabled(false);
        properties.setToken("secret");
        AdminApiInterceptor interceptor = new AdminApiInterceptor(properties);

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
        AdminApiInterceptor interceptor = new AdminApiInterceptor(properties);

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
        AdminApiInterceptor interceptor = new AdminApiInterceptor(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }
}
