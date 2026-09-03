package com.example.reader.ingestion;

import io.micrometer.core.annotation.Timed;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/ingest")
public class IngestionController {

    private final IngestionJobService jobService;
    private final IngestionUploadStorage uploadStorage;

    public IngestionController(IngestionJobService jobService, IngestionUploadStorage uploadStorage) {
        this.jobService = jobService;
        this.uploadStorage = uploadStorage;
    }

    @Timed(value = "ingestion.jobs.submit", description = "Submit ingestion jobs")
    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartIngestionResponse submit(@RequestBody IngestionRequest request) {
        long jobId = jobService.startJob(request);
        return new StartIngestionResponse(jobId, "PENDING", "/api/admin/ingest/jobs/" + jobId);
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
            @RequestPart("file") MultipartFile file
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
                originalLang
        ).withDefaults();
        metadata.validate();

        String fileSpec = uploadStorage.store(file, metadata.workTitle(), metadata.lang());
        IngestionRequest request = new IngestionRequest(
                metadata.workTitle(),
                metadata.lang(),
                fileSpec,
                metadata.year(),
                metadata.publisher(),
                metadata.translator(),
                metadata.author(),
                metadata.description(),
                metadata.originalLang()
        );
        long jobId = jobService.startJob(request);
        return new StartIngestionResponse(jobId, "PENDING", "/api/admin/ingest/jobs/" + jobId);
    }

    @Timed(value = "ingestion.jobs.status", description = "Fetch ingestion job status")
    @GetMapping("/jobs/{id}")
    public IngestionJob job(@PathVariable long id) {
        return jobService.getJob(id);
    }

    public record StartIngestionResponse(long jobId, String status, String statusUrl) {}
}
