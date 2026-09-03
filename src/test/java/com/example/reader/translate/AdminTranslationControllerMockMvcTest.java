package com.example.reader.translate;

import com.example.reader.common.ApiErrorHandler;
import com.example.reader.security.AdminAccessProperties;
import com.example.reader.security.AdminApiInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AdminTranslationControllerMockMvcTest {

    @Test
    void literaryRequiresAdminToken() throws Exception {
        TranslateService service = mock(TranslateService.class);
        MockMvc mvc = mockMvc(service, true, "secret");

        mvc.perform(post("/api/admin/translate/literary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Hallo","source":"de","target":"en","context":"chapter"}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void literaryReturnsNotFoundWhenAdminApiIsDisabled() throws Exception {
        TranslateService service = mock(TranslateService.class);
        MockMvc mvc = mockMvc(service, false, "secret");

        mvc.perform(post("/api/admin/translate/literary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Hallo","source":"de","target":"en","context":"chapter"}
                                """))
                .andExpect(status().isNotFound());

        verifyNoInteractions(service);
    }

    @Test
    void literaryDelegatesToService() throws Exception {
        TranslateService service = mock(TranslateService.class);
        when(service.literary(eq("Hallo"), eq("de"), eq("en"), eq("chapter"))).thenReturn("Hello");
        MockMvc mvc = mockMvc(service, true, "secret");

        mvc.perform(post("/api/admin/translate/literary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Hallo","source":"de","target":"en","context":"chapter"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translated").value("Hello"));
    }

    private MockMvc mockMvc(TranslateService service, boolean enabled, String token) {
        AdminAccessProperties properties = new AdminAccessProperties();
        properties.setEnabled(enabled);
        properties.setToken(token);
        return standaloneSetup(new AdminTranslationController(service))
                .addInterceptors(new AdminApiInterceptor(properties))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }
}
