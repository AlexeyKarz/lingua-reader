package com.example.reader.ingestion;

import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;

@Service
public class IngestionJobService {
    private static final Logger log = LoggerFactory.getLogger(IngestionJobService.class);
    private static final int DEFAULT_HISTORY_LIMIT = 25;
    private static final int MAX_HISTORY_LIMIT = 100;
    private static final String GENERATED_FILE_SPEC_PREFIX = "ai-literary://";

    private final IngestionFacade ingestionFacade;
    private final LiteraryEditionGenerationService generationService;
    private final IngestionJobRepository jobRepository;
    private final Executor ingestionTaskExecutor;

    public IngestionJobService(
            IngestionFacade ingestionFacade,
            LiteraryEditionGenerationService generationService,
            IngestionJobRepository jobRepository,
            @Qualifier("ingestionTaskExecutor") Executor ingestionTaskExecutor
    ) {
        this.ingestionFacade = ingestionFacade;
        this.generationService = generationService;
        this.jobRepository = jobRepository;
        this.ingestionTaskExecutor = ingestionTaskExecutor;
    }

    @Timed(value = "ingestion.jobs.start", description = "Start ingestion job")
    public long startJob(IngestionRequest rawRequest) {
        if (rawRequest == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        IngestionRequest request = rawRequest.withDefaults();
        request.validate();

        long jobId = jobRepository.create(request);
        log.info("Ingestion job {} queued for work='{}' lang='{}' file={}",
                jobId, request.workTitle(), request.lang(), request.fileSpec());

        ingestionTaskExecutor.execute(() -> runJob(jobId, request));
        return jobId;
    }

    @Timed(value = "ingestion.jobs.generate_literary.start", description = "Start literary edition generation job")
    public long startGeneratedLiteraryJob(GenerateLiteraryEditionRequest rawRequest) {
        if (rawRequest == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        GenerateLiteraryEditionRequest request = rawRequest.withDefaults();
        request.validate();

        long jobId = jobRepository.createGenerated(request);
        log.info("Literary generation job {} queued for work='{}' source='{}' target='{}'",
                jobId, request.workTitle(), request.sourceLang(), request.targetLang());

        ingestionTaskExecutor.execute(() -> runGeneratedLiteraryJob(jobId, request));
        return jobId;
    }

    public IngestionJob getJob(long id) {
        IngestionJob job = jobRepository.findById(id);
        if (job == null) {
            throw new IllegalArgumentException("Ingestion job not found: " + id);
        }
        return job;
    }

    public List<IngestionJob> listJobs(Integer limit) {
        int requestedLimit = limit == null ? DEFAULT_HISTORY_LIMIT : limit;
        int boundedLimit = Math.max(1, Math.min(requestedLimit, MAX_HISTORY_LIMIT));
        return jobRepository.findRecent(boundedLimit);
    }

    public long retryJob(long failedJobId) {
        IngestionJob job = getJob(failedJobId);
        boolean generatedJob = job.fileSpec() != null && job.fileSpec().startsWith(GENERATED_FILE_SPEC_PREFIX);
        if (generatedJob) {
            return retryGeneratedLiteraryJob(job);
        }
        if (!"FAILED".equals(job.status())) {
            throw new IllegalArgumentException("Only failed upload jobs and generated literary jobs can be retried");
        }

        IngestionRequest request = new IngestionRequest(
                job.workTitle(),
                job.lang(),
                job.fileSpec(),
                job.year(),
                job.publisher(),
                job.translator(),
                job.author(),
                job.description(),
                job.originalLang()
        );
        return startJob(request);
    }

    private void runJob(long jobId, IngestionRequest request) {
        jobRepository.markRunning(jobId);
        log.info("Ingestion job {} started", jobId);

        try {
            IngestionResult result = ingestionFacade.ingest(request, (step, message, chapters, paragraphs, sentences) ->
                    jobRepository.updateProgress(jobId, step, message, chapters, paragraphs, sentences)
            );
            jobRepository.markSucceeded(jobId, result);
            log.info("Ingestion job {} completed: workId={}, editionId={}, chapters={}, paragraphs={}, sentences={}",
                    jobId, result.workId(), result.editionId(), result.chapters(), result.paragraphs(), result.sentences());
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jobRepository.markFailed(jobId, error);
            log.error("Ingestion job {} failed: {}", jobId, error, e);
        }
    }

    private void runGeneratedLiteraryJob(long jobId, GenerateLiteraryEditionRequest request) {
        jobRepository.markRunning(jobId);
        log.info("Literary generation job {} started", jobId);

        try {
            IngestionResult result = generationService.generate(request, (step, message, chapters, paragraphs, sentences) ->
                    jobRepository.updateProgress(jobId, step, message, chapters, paragraphs, sentences)
            );
            jobRepository.markSucceeded(jobId, result);
            log.info("Literary generation job {} completed: workId={}, editionId={}, chapters={}, paragraphs={}, sentences={}",
                    jobId, result.workId(), result.editionId(), result.chapters(), result.paragraphs(), result.sentences());
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jobRepository.markFailed(jobId, error);
            log.error("Literary generation job {} failed: {}", jobId, error, e);
        }
    }

    private long retryGeneratedLiteraryJob(IngestionJob job) {
        String[] fileSpecParts = job.fileSpec().substring(GENERATED_FILE_SPEC_PREFIX.length()).split("/", 3);
        if (fileSpecParts.length < 2) {
            throw new IllegalArgumentException("Could not retry generated literary job with malformed fileSpec");
        }

        String sourceLang = hasText(job.originalLang()) ? job.originalLang() : fileSpecParts[0];
        String targetLang = hasText(job.lang()) ? job.lang() : fileSpecParts[1];
        Integer maxSourceChars = retryMaxSourceChars(job.maxSourceChars());
        GenerateLiteraryEditionRequest request = new GenerateLiteraryEditionRequest(
                job.workTitle(),
                sourceLang,
                targetLang,
                job.year(),
                job.publisher(),
                job.translator(),
                job.bodyOnly(),
                job.replaceExisting(),
                maxSourceChars,
                job.chunkMaxChars(),
                job.contextChars(),
                job.model()
        );
        return startGeneratedLiteraryJob(request);
    }

    private Integer retryMaxSourceChars(Integer savedMaxSourceChars) {
        if (savedMaxSourceChars != null
                && savedMaxSourceChars == GenerateLiteraryEditionRequest.LEGACY_DEFAULT_MAX_SOURCE_CHARS) {
            return GenerateLiteraryEditionRequest.DEFAULT_MAX_SOURCE_CHARS;
        }
        return savedMaxSourceChars;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
