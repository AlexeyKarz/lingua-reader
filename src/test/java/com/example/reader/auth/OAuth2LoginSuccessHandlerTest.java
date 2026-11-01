package com.example.reader.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2LoginSuccessHandlerTest {

    @Test
    void onlyAcceptsLocalRedirectPaths() {
        assertThat(OAuth2LoginSuccessHandler.isSafeRelativePath("/admin/ingest?retry=1")).isTrue();
        assertThat(OAuth2LoginSuccessHandler.isSafeRelativePath("//evil.example/path")).isFalse();
        assertThat(OAuth2LoginSuccessHandler.isSafeRelativePath("https://evil.example/path")).isFalse();
        assertThat(OAuth2LoginSuccessHandler.isSafeRelativePath("/\\evil.example/path")).isFalse();
    }
}
