package com.example.reader.ingestion;

import com.example.reader.metrics.AdminAuditService;
import com.example.reader.metrics.ProductEventService;
import jakarta.servlet.http.HttpServletRequest;
import io.micrometer.core.annotation.Timed;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ingest")
public class IngestionController {

    private final IngestionJobService jobService;
    private final IngestionUploadStorage uploadStorage;
    private final ProductEventService events;
    private final AdminAuditService audit;

    public IngestionController(
            IngestionJobService jobService,
            IngestionUploadStorage uploadStorage,
            ProductEventService events,
            AdminAuditService audit
    ) {
        this.jobService = jobService;
        this.uploadStorage = uploadStorage;
        this.events = events;
        this.audit = audit;
    }

    @Timed(value = "ingestion.jobs.submit", description = "Submit ingestion jobs")
    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartIngestionResponse submit(
            @RequestBody IngestionRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        long jobId = jobService.startJob(request);
        events.recordSimple("ingestion_job_submitted", authentication, Map.of("jobId", jobId, "mode", "file"));
        audit.record("ingestion_job_submitted", authentication, servletRequest, "ingestion_job", String.valueOf(jobId), Map.of("mode", "file"));
        return new StartIngestionResponse(jobId, "PENDING", "/api/admin/ingest/jobs/" + jobId);
    }

    @Timed(value = "ingestion.jobs.history", description = "List recent ingestion jobs")
    @GetMapping("/jobs")
    public List<IngestionJob> jobs(@RequestParam(required = false) Integer limit) {
        return jobService.listJobs(limit);
    }

    @Timed(value = "ingestion.jobs.generate_literary.submit", description = "Submit literary edition generation jobs")
    @PostMapping("/jobs/generate-literary")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartIngestionResponse submitGeneratedLiterary(
            @RequestBody GenerateLiteraryEditionRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        long jobId = jobService.startGeneratedLiteraryJob(request);
        events.recordSimple("generated_literary_job_submitted", authentication, Map.of("jobId", jobId, "mode", "generate"));
        audit.record("generated_literary_job_submitted", authentication, servletRequest, "ingestion_job", String.valueOf(jobId), Map.of("mode", "generate"));
        return new StartIngestionResponse(jobId, "PENDING", "/api/admin/ingest/jobs/" + jobId);
    }

    @Timed(value = "ingestion.jobs.generate_literary.estimate", description = "Estimate literary edition generation")
    @PostMapping("/jobs/generate-literary/estimate")
    public LiteraryGenerationEstimate estimateGeneratedLiterary(@RequestBody GenerateLiteraryEditionRequest request) {
        return jobService.estimateGeneratedLiterary(request);
    }

    @Timed(value = "ingestion.jobs.upload.submit", description = "Upload source file and submit ingestion job")
    @PostMapping(value = "/jobs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartIngestionResponse submitUpload(
            @RequestParam String workTitle,
            @RequestParam String lang,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) String translator,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String originalLang,
            @RequestParam(defaultValue = "prose") String contentType,
            @RequestParam(defaultValue = "false") boolean dictionaryWarmup,
            @RequestParam(required = false) List<String> dictionaryTargetLangs,
            @RequestPart("file") MultipartFile file,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        IngestionRequest metadata = new IngestionRequest(
                workTitle,
                lang,
                "uploaded-source",
                year,
                publisher,
                translator,
                author,
                description,
                originalLang,
                contentType,
                dictionaryWarmup,
                dictionaryTargetLangs
        ).withDefaults();
        metadata.validate();

        String fileSpec = uploadStorage.store(file, metadata.workTitle(), metadata.lang(), metadata.contentType());
        IngestionRequest request = new IngestionRequest(
                metadata.workTitle(),
                metadata.lang(),
                fileSpec,
                metadata.year(),
                metadata.publisher(),
                metadata.translator(),
                metadata.author(),
                metadata.description(),
                metadata.originalLang(),
                metadata.contentType(),
                metadata.dictionaryWarmup(),
                metadata.dictionaryTargetLangs()
        );
        long jobId = jobService.startJob(request);
        events.recordSimple("ingestion_job_submitted", authentication, Map.of("jobId", jobId, "mode", "upload"));
        audit.record("ingestion_job_submitted", authentication, servletRequest, "ingestion_job", String.valueOf(jobId), Map.of("mode", "upload"));
        return new StartIngestionResponse(jobId, "PENDING", "/api/admin/ingest/jobs/" + jobId);
    }

    @Timed(value = "ingestion.jobs.status", description = "Fetch ingestion job status")
    @GetMapping("/jobs/{id}")
    public IngestionJob job(@PathVariable long id) {
        return jobService.getJob(id);
    }

    @Timed(value = "ingestion.jobs.retry", description = "Retry failed ingestion job")
    @PostMapping("/jobs/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartIngestionResponse retry(@PathVariable long id, Authentication authentication, HttpServletRequest servletRequest) {
        long jobId = jobService.retryJob(id);
        events.recordSimple("ingestion_job_retried", authentication, Map.of("sourceJobId", id, "jobId", jobId));
        audit.record("ingestion_job_retried", authentication, servletRequest, "ingestion_job", String.valueOf(jobId), Map.of("sourceJobId", id));
        return new StartIngestionResponse(jobId, "PENDING", "/api/admin/ingest/jobs/" + jobId);
    }

    public record StartIngestionResponse(long jobId, String status, String statusUrl) {}
}
