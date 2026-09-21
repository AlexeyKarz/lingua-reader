package com.example.reader.ingestion;

import com.example.reader.metrics.ProductEventService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionJobServiceTest {

    @Test
    void listJobsBoundsHistoryLimit() {
        IngestionJobRepository repository = mock(IngestionJobRepository.class);
        IngestionJobService service = new IngestionJobService(
                mock(IngestionFacade.class),
                mock(LiteraryEditionGenerationService.class),
                repository,
                Runnable::run,
                mock(ProductEventService.class)
        );
        when(repository.findRecent(anyInt())).thenReturn(List.of());

        assertThat(service.listJobs(null)).isEmpty();
        assertThat(service.listJobs(500)).isEmpty();
        assertThat(service.listJobs(0)).isEmpty();

        verify(repository).findRecent(25);
        verify(repository).findRecent(100);
        verify(repository).findRecent(1);
    }

    @Test
    void retryFailedUploadJobCreatesFreshJobFromSavedMetadata() throws Exception {
        IngestionFacade ingestionFacade = mock(IngestionFacade.class);
        IngestionJobRepository repository = mock(IngestionJobRepository.class);
        IngestionJobService service = new IngestionJobService(
                ingestionFacade,
                mock(LiteraryEditionGenerationService.class),
                repository,
                Runnable::run,
                mock(ProductEventService.class)
        );
        when(repository.findById(7L)).thenReturn(new IngestionJob(
                7L,
                "FAILED",
                "failed",
                "Ingestion failed",
                "Old failure",
                "Ithaca",
                "el",
                "file:/tmp/ithaca.epub",
                1911,
                "Constantine Cavafy",
                "Description",
                "Publisher",
                "Translator",
                "el",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                0,
                Instant.now(),
                Instant.now(),
                Instant.now()
        ));
        when(repository.create(any(IngestionRequest.class))).thenReturn(8L);
        when(ingestionFacade.ingest(any(IngestionRequest.class), any(IngestionProgressSink.class)))
                .thenReturn(new IngestionResult(4L, 5L, 1, 2, 3));

        long retriedJobId = service.retryJob(7L);

        assertThat(retriedJobId).isEqualTo(8L);

        ArgumentCaptor<IngestionRequest> requestCaptor = ArgumentCaptor.forClass(IngestionRequest.class);
        verify(repository).create(requestCaptor.capture());
        IngestionRequest request = requestCaptor.getValue();
        assertThat(request.workTitle()).isEqualTo("Ithaca");
        assertThat(request.lang()).isEqualTo("el");
        assertThat(request.fileSpec()).isEqualTo("file:/tmp/ithaca.epub");
        assertThat(request.year()).isEqualTo(1911);
        assertThat(request.author()).isEqualTo("Constantine Cavafy");
        assertThat(request.description()).isEqualTo("Description");
        assertThat(request.publisher()).isEqualTo("Publisher");
        assertThat(request.translator()).isEqualTo("Translator");
        assertThat(request.originalLang()).isEqualTo("el");
        verify(repository).markSucceeded(8L, new IngestionResult(4L, 5L, 1, 2, 3));
    }

    @Test
    void retryFailedGeneratedLiteraryJobReconstructsGenerationRequest() throws Exception {
        LiteraryEditionGenerationService generationService = mock(LiteraryEditionGenerationService.class);
        IngestionJobRepository repository = mock(IngestionJobRepository.class);
        IngestionJobService service = new IngestionJobService(
                mock(IngestionFacade.class),
                generationService,
                repository,
                Runnable::run,
                mock(ProductEventService.class)
        );
        when(repository.findById(9L)).thenReturn(new IngestionJob(
                9L,
                "FAILED",
                "failed",
                "Ingestion failed",
                "Old failure",
                "Ithaca",
                "en",
                "ai-literary://el/en/Ithaca",
                1911,
                null,
                null,
                "Generated",
                "AI literary translation",
                "el",
                true,
                false,
                20_000,
                1_500,
                500,
                "gpt-4o",
                null,
                null,
                0,
                0,
                0,
                Instant.now(),
                Instant.now(),
                Instant.now()
        ));
        when(repository.createGenerated(any(GenerateLiteraryEditionRequest.class))).thenReturn(10L);
        when(generationService.generate(any(GenerateLiteraryEditionRequest.class), any(IngestionProgressSink.class)))
                .thenReturn(new IngestionResult(4L, 6L, 1, 2, 3));

        long retriedJobId = service.retryJob(9L);

        assertThat(retriedJobId).isEqualTo(10L);

        ArgumentCaptor<GenerateLiteraryEditionRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerateLiteraryEditionRequest.class);
        verify(generationService).generate(requestCaptor.capture(), any(IngestionProgressSink.class));
        GenerateLiteraryEditionRequest request = requestCaptor.getValue();
        assertThat(request.workTitle()).isEqualTo("Ithaca");
        assertThat(request.sourceLang()).isEqualTo("el");
        assertThat(request.targetLang()).isEqualTo("en");
        assertThat(request.year()).isEqualTo(1911);
        assertThat(request.publisher()).isEqualTo("Generated");
        assertThat(request.translator()).isEqualTo("AI literary translation");
        assertThat(request.bodyOnly()).isTrue();
        assertThat(request.replaceExisting()).isFalse();
        assertThat(request.maxSourceChars()).isEqualTo(20_000);
        assertThat(request.chunkMaxChars()).isEqualTo(1_500);
        assertThat(request.contextChars()).isEqualTo(500);
        assertThat(request.model()).isEqualTo("gpt-4o");
    }

    @Test
    void retryGeneratedLiteraryJobUpgradesLegacyDefaultSourceLimit() throws Exception {
        LiteraryEditionGenerationService generationService = mock(LiteraryEditionGenerationService.class);
        IngestionJobRepository repository = mock(IngestionJobRepository.class);
        IngestionJobService service = new IngestionJobService(
                mock(IngestionFacade.class),
                generationService,
                repository,
                Runnable::run,
                mock(ProductEventService.class)
        );
        when(repository.findById(13L)).thenReturn(new IngestionJob(
                13L,
                "FAILED",
                "failed",
                "Ingestion failed",
                "Source text is too large",
                "Homesick",
                "en",
                "ai-literary://el/en/Homesick",
                null,
                null,
                null,
                "Generated",
                "AI literary translation",
                "el",
                true,
                true,
                GenerateLiteraryEditionRequest.LEGACY_DEFAULT_MAX_SOURCE_CHARS,
                1_800,
                600,
                null,
                null,
                null,
                0,
                0,
                0,
                Instant.now(),
                Instant.now(),
                Instant.now()
        ));
        when(repository.createGenerated(any(GenerateLiteraryEditionRequest.class))).thenReturn(14L);
        when(generationService.generate(any(GenerateLiteraryEditionRequest.class), any(IngestionProgressSink.class)))
                .thenReturn(new IngestionResult(4L, 6L, 1, 2, 3));

        assertThat(service.retryJob(13L)).isEqualTo(14L);

        ArgumentCaptor<GenerateLiteraryEditionRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerateLiteraryEditionRequest.class);
        verify(repository).createGenerated(requestCaptor.capture());
        assertThat(requestCaptor.getValue().maxSourceChars())
                .isEqualTo(GenerateLiteraryEditionRequest.DEFAULT_MAX_SOURCE_CHARS);
    }

    @Test
    void retryRejectsNonFailedJob() {
        IngestionJobRepository repository = mock(IngestionJobRepository.class);
        IngestionJobService service = new IngestionJobService(
                mock(IngestionFacade.class),
                mock(LiteraryEditionGenerationService.class),
                repository,
                Runnable::run,
                mock(ProductEventService.class)
        );
        when(repository.findById(3L)).thenReturn(new IngestionJob(
                3L,
                "SUCCEEDED",
                "done",
                "Ingestion completed",
                null,
                "Book",
                "en",
                "file:/tmp/book.epub",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1L,
                2L,
                1,
                1,
                1,
                Instant.now(),
                Instant.now(),
                Instant.now()
        ));

        assertThatThrownBy(() -> service.retryJob(3L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only failed upload jobs and generated literary jobs can be retried");
    }

    @Test
    void retryAllowsSucceededGeneratedLiteraryJobForRegeneration() throws Exception {
        LiteraryEditionGenerationService generationService = mock(LiteraryEditionGenerationService.class);
        IngestionJobRepository repository = mock(IngestionJobRepository.class);
        IngestionJobService service = new IngestionJobService(
                mock(IngestionFacade.class),
                generationService,
                repository,
                Runnable::run,
                mock(ProductEventService.class)
        );
        when(repository.findById(11L)).thenReturn(new IngestionJob(
                11L,
                "SUCCEEDED",
                "done",
                "Generated",
                null,
                "Ithaca",
                "en",
                "ai-literary://el/en/Ithaca",
                1911,
                null,
                null,
                null,
                "AI literary translation",
                "el",
                null,
                null,
                null,
                null,
                null,
                "gpt-4o",
                27L,
                80L,
                1,
                6,
                15,
                Instant.now(),
                Instant.now(),
                Instant.now()
        ));
        when(repository.createGenerated(any(GenerateLiteraryEditionRequest.class))).thenReturn(12L);
        when(generationService.generate(any(GenerateLiteraryEditionRequest.class), any(IngestionProgressSink.class)))
                .thenReturn(new IngestionResult(27L, 80L, 1, 6, 15));

        long retriedJobId = service.retryJob(11L);

        assertThat(retriedJobId).isEqualTo(12L);
        ArgumentCaptor<GenerateLiteraryEditionRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerateLiteraryEditionRequest.class);
        verify(repository).createGenerated(requestCaptor.capture());
        assertThat(requestCaptor.getValue().replaceExisting()).isTrue();
        assertThat(requestCaptor.getValue().model()).isEqualTo("gpt-4o");
        verify(generationService).generate(any(GenerateLiteraryEditionRequest.class), any(IngestionProgressSink.class));
    }

    @Test
    void startGeneratedLiteraryJobCreatesAndRunsMonitoredJob() throws Exception {
        IngestionFacade ingestionFacade = mock(IngestionFacade.class);
        LiteraryEditionGenerationService generationService = mock(LiteraryEditionGenerationService.class);
        IngestionJobRepository repository = mock(IngestionJobRepository.class);
        IngestionJobService service = new IngestionJobService(
                ingestionFacade,
                generationService,
                repository,
                Runnable::run,
                mock(ProductEventService.class)
        );
        when(repository.createGenerated(any(GenerateLiteraryEditionRequest.class))).thenReturn(12L);
        when(generationService.generate(any(GenerateLiteraryEditionRequest.class), any(IngestionProgressSink.class)))
                .thenAnswer(invocation -> {
                    IngestionProgressSink sink = invocation.getArgument(1);
                    sink.update("generate_literary", "Generated paragraph 1 / 1", 1, 1, 2);
                    return new IngestionResult(4L, 5L, 1, 1, 2);
                });

        long jobId = service.startGeneratedLiteraryJob(new GenerateLiteraryEditionRequest(
                " Book ",
                " DE ",
                " EN ",
                1915,
                " Generated ",
                " ",
                null,
                null,
                null,
                null,
                null,
                "gpt-4o-mini"
        ));

        assertThat(jobId).isEqualTo(12L);

        ArgumentCaptor<GenerateLiteraryEditionRequest> jobRequestCaptor =
                ArgumentCaptor.forClass(GenerateLiteraryEditionRequest.class);
        verify(repository).createGenerated(jobRequestCaptor.capture());
        assertThat(jobRequestCaptor.getValue().workTitle()).isEqualTo("Book");
        assertThat(jobRequestCaptor.getValue().sourceLang()).isEqualTo("de");
        assertThat(jobRequestCaptor.getValue().targetLang()).isEqualTo("en");
        assertThat(jobRequestCaptor.getValue().translator()).isEqualTo("AI literary translation");
        assertThat(jobRequestCaptor.getValue().model()).isEqualTo("gpt-4o-mini");

        verify(repository).markRunning(12L);
        verify(repository).updateProgress(12L, "generate_literary", "Generated paragraph 1 / 1", 1, 1, 2);
        verify(repository).markSucceeded(12L, new IngestionResult(4L, 5L, 1, 1, 2));
    }

    @Test
    void startGeneratedLiteraryJobMarksFailureWhenGenerationFails() throws Exception {
        IngestionJobRepository repository = mock(IngestionJobRepository.class);
        LiteraryEditionGenerationService generationService = mock(LiteraryEditionGenerationService.class);
        IngestionJobService service = new IngestionJobService(
                mock(IngestionFacade.class),
                generationService,
                repository,
                Runnable::run,
                mock(ProductEventService.class)
        );
        when(repository.createGenerated(any(GenerateLiteraryEditionRequest.class))).thenReturn(33L);
        when(generationService.generate(any(GenerateLiteraryEditionRequest.class), any(IngestionProgressSink.class)))
                .thenThrow(new IllegalStateException("Provider unavailable"));

        long jobId = service.startGeneratedLiteraryJob(new GenerateLiteraryEditionRequest(
                "Book",
                "de",
                "en",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertThat(jobId).isEqualTo(33L);
        verify(repository).markRunning(33L);
        verify(repository).markFailed(33L, "Provider unavailable");
    }

    @Test
    void startGeneratedLiteraryJobRejectsNullRequest() {
        IngestionJobService service = new IngestionJobService(
                mock(IngestionFacade.class),
                mock(LiteraryEditionGenerationService.class),
                mock(IngestionJobRepository.class),
                Runnable::run,
                mock(ProductEventService.class)
        );

        assertThatThrownBy(() -> service.startGeneratedLiteraryJob(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request body is required");
    }
}
