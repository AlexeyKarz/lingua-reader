package com.example.reader.metrics;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class AdminMetricsAggregationJob {
    private final AdminMetricsRepository metrics;

    public AdminMetricsAggregationJob(AdminMetricsRepository metrics) {
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshTodayOnStartup() {
        metrics.refreshDailyAggregates(LocalDate.now());
    }

    @Scheduled(cron = "0 20 0 * * *")
    public void refreshYesterdayAndPrune() {
        metrics.refreshDailyAggregates(LocalDate.now().minusDays(1));
        metrics.pruneRetention();
    }
}
