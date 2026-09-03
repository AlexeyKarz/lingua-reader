package com.example.reader.ingestion;

import com.example.reader.common.ApiErrorHandler;
import com.example.reader.security.AdminAccessProperties;
import com.example.reader.security.AdminApiInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class IngestionControllerMockMvcTest {

    private IngestionJobService jobService;
    private IngestionUploadStorage uploadStorage;

    @BeforeEach
    void setUp() {
        jobService = mock(IngestionJobService.class);
        uploadStorage = mock(IngestionUploadStorage.class);
    }

    @Test
    void uploadRequiresAdminToken() throws Exception {
        MockMvc mvc = mockMvc(true, "secret");

        mvc.perform(validUpload())
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(uploadStorage, jobService);
    }

    @Test
    void uploadReturnsNotFoundWhenAdminApiIsDisabled() throws Exception {
        MockMvc mvc = mockMvc(false, "secret");

        mvc.perform(validUpload().header(HttpHeaders.AUTHORIZATION, "Bearer secret"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(uploadStorage, jobService);
    }

    @Test
    void uploadStoresFileAndSubmitsNormalizedIngestionRequest() throws Exception {
        MockMvc mvc = mockMvc(true, "secret");
        when(uploadStorage.store(any(MultipartFile.class), eq("A Book"), eq("en")))
                .thenReturn("file:/tmp/a-book.epub");
        when(jobService.startJob(any(IngestionRequest.class))).thenReturn(42L);

        mvc.perform(uploadFile()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer secret")
                        .param("workTitle", " A Book ")
                        .param("lang", " EN ")
                        .param("originalLang", " EN ")
                        .param("author", " Author ")
                        .param("description", " Description ")
                        .param("publisher", " Publisher ")
                        .param("translator", " Translator ")
                        .param("year", "1843"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(42))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.statusUrl").value("/api/admin/ingest/jobs/42"));

        ArgumentCaptor<IngestionRequest> requestCaptor = ArgumentCaptor.forClass(IngestionRequest.class);
        verify(jobService).startJob(requestCaptor.capture());
        IngestionRequest request = requestCaptor.getValue();
        assertThat(request.workTitle()).isEqualTo("A Book");
        assertThat(request.lang()).isEqualTo("en");
        assertThat(request.fileSpec()).isEqualTo("file:/tmp/a-book.epub");
        assertThat(request.originalLang()).isEqualTo("en");
        assertThat(request.author()).isEqualTo("Author");
        assertThat(request.description()).isEqualTo("Description");
        assertThat(request.publisher()).isEqualTo("Publisher");
        assertThat(request.translator()).isEqualTo("Translator");
        assertThat(request.year()).isEqualTo(1843);
    }

    @Test
    void uploadValidationErrorsReturnBadRequest() throws Exception {
        MockMvc mvc = mockMvc(true, "secret");

        mvc.perform(uploadFile()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer secret")
                        .param("workTitle", " ")
                        .param("lang", "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("workTitle is required"));

        verifyNoInteractions(uploadStorage, jobService);
    }

    private MockMvc mockMvc(boolean enabled, String token) {
        AdminAccessProperties properties = new AdminAccessProperties();
        properties.setEnabled(enabled);
        properties.setToken(token);
        return standaloneSetup(new IngestionController(jobService, uploadStorage))
                .addInterceptors(new AdminApiInterceptor(properties))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    private MockHttpServletRequestBuilder validUpload() {
        return uploadFile()
                .param("workTitle", "Book")
                .param("lang", "en");
    }

    private MockHttpServletRequestBuilder uploadFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "book.txt",
                "text/plain",
                "Chapter 1\n\nHello.".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        return multipart("/api/admin/ingest/jobs/upload")
                .file(file);
    }
}
