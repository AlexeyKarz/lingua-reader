package com.example.reader.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

// Enable scheduling by adding @EnableScheduling on the main class OR a @Configuration class.
@Component
public class IngestionScheduler {
    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    // TODO: fetch/normalize texts, align sentences, persist results
    @Scheduled(cron = "0 0 2 * * *") // daily at 02:00
    public void runIngestion() {
        log.info("Ingestion job started (TODO implement)");
    }
}
