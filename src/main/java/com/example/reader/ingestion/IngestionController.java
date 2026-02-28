package com.example.reader.ingestion;

import io.micrometer.core.annotation.Timed;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/ingest")
public class IngestionController {

    private final IngestionJobService jobService;

    public IngestionController(IngestionJobService jobService) {
        this.jobService = jobService;
    }

    @Timed(value = "ingestion.jobs.submit", description = "Submit ingestion jobs")
    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartIngestionResponse submit(@RequestBody IngestionRequest request) {
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
