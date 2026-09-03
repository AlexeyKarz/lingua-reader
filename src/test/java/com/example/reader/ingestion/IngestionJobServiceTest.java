package com.example.reader.ingestion;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionJobServiceTest {

    @Test
    void startGeneratedLiteraryJobCreatesAndRunsMonitoredJob() throws Exception {
        IngestionFacade ingestionFacade = mock(IngestionFacade.class);
        LiteraryEditionGenerationService generationService = mock(LiteraryEditionGenerationService.class);
        IngestionJobRepository repository = mock(IngestionJobRepository.class);
        IngestionJobService service = new IngestionJobService(
                ingestionFacade,
                generationService,
                repository,
                Runnable::run
        );
        when(repository.create(any(IngestionRequest.class))).thenReturn(12L);
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
                null
        ));

        assertThat(jobId).isEqualTo(12L);

        ArgumentCaptor<IngestionRequest> jobRequestCaptor = ArgumentCaptor.forClass(IngestionRequest.class);
        verify(repository).create(jobRequestCaptor.capture());
        assertThat(jobRequestCaptor.getValue().workTitle()).isEqualTo("Book");
        assertThat(jobRequestCaptor.getValue().lang()).isEqualTo("en");
        assertThat(jobRequestCaptor.getValue().fileSpec()).isEqualTo("ai-literary://de/en/Book");
        assertThat(jobRequestCaptor.getValue().translator()).isEqualTo("AI literary translation");

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
                Runnable::run
        );
        when(repository.create(any(IngestionRequest.class))).thenReturn(33L);
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
                Runnable::run
        );

        assertThatThrownBy(() -> service.startGeneratedLiteraryJob(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request body is required");
    }
}
