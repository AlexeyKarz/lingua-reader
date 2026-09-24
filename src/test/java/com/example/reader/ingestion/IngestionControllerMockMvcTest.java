package com.example.reader.ingestion;

import com.example.reader.common.ApiErrorHandler;
import com.example.reader.auth.ApiTokenService;
import com.example.reader.metrics.AdminAuditService;
import com.example.reader.metrics.ProductEventService;
import com.example.reader.translate.providers.ProviderClient;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        when(uploadStorage.store(any(MultipartFile.class), eq("A Book"), eq("en"), eq("prose")))
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
        assertThat(request.contentType()).isEqualTo("prose");
        assertThat(request.description()).isEqualTo("Description");
        assertThat(request.publisher()).isEqualTo("Publisher");
        assertThat(request.translator()).isEqualTo("Translator");
        assertThat(request.year()).isEqualTo(1843);
    }

    @Test
    void generatedLiterarySubmitsJob() throws Exception {
        MockMvc mvc = mockMvc(true, "secret");
        when(jobService.startGeneratedLiteraryJob(any(GenerateLiteraryEditionRequest.class))).thenReturn(88L);

        mvc.perform(post("/api/admin/ingest/jobs/generate-literary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer secret")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workTitle": " A Book ",
                                  "sourceLang": " DE ",
                                  "targetLang": " EN ",
                                  "year": 1915,
                                  "publisher": "Generated",
                                  "translator": "AI",
                                  "maxSourceChars": 20000,
                                  "chunkMaxChars": 1500,
                                  "contextChars": 500,
                                  "model": "gpt-4o"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(88))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.statusUrl").value("/api/admin/ingest/jobs/88"));

        ArgumentCaptor<GenerateLiteraryEditionRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerateLiteraryEditionRequest.class);
        verify(jobService).startGeneratedLiteraryJob(requestCaptor.capture());
        GenerateLiteraryEditionRequest request = requestCaptor.getValue();
        assertThat(request.workTitle()).isEqualTo(" A Book ");
        assertThat(request.sourceLang()).isEqualTo(" DE ");
        assertThat(request.targetLang()).isEqualTo(" EN ");
        assertThat(request.publisher()).isEqualTo("Generated");
        assertThat(request.translator()).isEqualTo("AI");
        assertThat(request.maxSourceChars()).isEqualTo(20000);
        assertThat(request.chunkMaxChars()).isEqualTo(1500);
        assertThat(request.contextChars()).isEqualTo(500);
        assertThat(request.model()).isEqualTo("gpt-4o");
        verifyNoInteractions(uploadStorage);
    }

    @Test
    void historyListsRecentJobs() throws Exception {
        MockMvc mvc = mockMvc(true, "secret");
        when(jobService.listJobs(50)).thenReturn(List.of(new IngestionJob(
                42L,
                "FAILED",
                "failed",
                "Ingestion failed",
                "Provider unavailable",
                "Ithaca",
                "en",
                "ai-literary://el/en/Ithaca",
                1911,
                "Constantine Cavafy",
                "A short poem.",
                "Generated",
                "AI literary translation",
                "el",
                true,
                true,
                20_000,
                1_500,
                500,
                "gpt-4o",
                null,
                null,
                1,
                6,
                15,
                Instant.parse("2026-09-07T20:45:16Z"),
                Instant.parse("2026-09-07T20:45:17Z"),
                Instant.parse("2026-09-07T20:45:18Z")
        )));

        mvc.perform(get("/api/admin/ingest/jobs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer secret")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].workTitle").value("Ithaca"))
                .andExpect(jsonPath("$[0].description").value("A short poem."))
                .andExpect(jsonPath("$[0].originalLang").value("el"))
                .andExpect(jsonPath("$[0].model").value("gpt-4o"));

        verify(jobService).listJobs(50);
    }

    @Test
    void retryFailedJobSubmitsFreshJob() throws Exception {
        MockMvc mvc = mockMvc(true, "secret");
        when(jobService.retryJob(42L)).thenReturn(43L);

        mvc.perform(post("/api/admin/ingest/jobs/42/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer secret"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(43))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.statusUrl").value("/api/admin/ingest/jobs/43"));

        verify(jobService).retryJob(42L);
        verifyNoInteractions(uploadStorage);
    }

    @Test
    void uploadRouteReportsMissingFileAsBadRequest() throws Exception {
        MockMvc mvc = mockMvc(true, "secret");

        mvc.perform(multipart("/api/admin/ingest/jobs/upload")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer secret")
                        .param("workTitle", "Greek Book")
                        .param("lang", "el"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.path").value("/api/admin/ingest/jobs/upload"));

        verifyNoInteractions(uploadStorage, jobService);
    }

    @Test
    void unsupportedPostToJobStatusReturnsMethodNotAllowed() throws Exception {
        MockMvc mvc = mockMvc(true, "secret");

        mvc.perform(post("/api/admin/ingest/jobs/42")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer secret"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value("Request method is not supported"))
                .andExpect(jsonPath("$.path").value("/api/admin/ingest/jobs/42"));

        verifyNoInteractions(uploadStorage, jobService);
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
        return standaloneSetup(new IngestionController(
                        jobService,
                        uploadStorage,
                        mock(ProductEventService.class),
                        mock(AdminAuditService.class),
                        mock(ProviderClient.class)
                ))
                .addInterceptors(new AdminApiInterceptor(properties, mock(ApiTokenService.class)))
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
