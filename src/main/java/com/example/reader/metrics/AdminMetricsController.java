package com.example.reader.metrics;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/metrics")
public class AdminMetricsController {
    private final AdminMetricsRepository metrics;

    public AdminMetricsController(AdminMetricsRepository metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.overview(range.from(), range.to());
    }

    @GetMapping("/usage/daily")
    public List<Map<String, Object>> usageDaily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "reader_page_viewed") String eventType
    ) {
        DateRange range = range(from, to);
        return metrics.usageDaily(range.from(), range.to(), eventType);
    }

    @GetMapping("/translations/operations")
    public List<Map<String, Object>> translationOperations(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.translationOperations(range.from(), range.to());
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.usersDaily(range.from(), range.to());
    }

    @GetMapping("/translations/daily")
    public List<Map<String, Object>> translationsDaily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.translationsDaily(range.from(), range.to());
    }

    @GetMapping("/translations/providers")
    public List<Map<String, Object>> translationProviders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.translationProviders(range.from(), range.to());
    }

    @GetMapping("/translations/language-pairs")
    public List<Map<String, Object>> translationLanguagePairs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.translationLanguagePairs(range.from(), range.to());
    }

    @GetMapping("/books")
    public List<Map<String, Object>> books(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.topBooks(range.from(), range.to());
    }

    @GetMapping("/language-pairs")
    public List<Map<String, Object>> languagePairs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.languagePairs(range.from(), range.to());
    }

    @GetMapping("/reader/language-pairs")
    public List<Map<String, Object>> readerLanguagePairs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.readerLanguagePairs(range.from(), range.to());
    }

    @GetMapping("/vocabulary")
    public List<Map<String, Object>> vocabulary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.vocabulary(range.from(), range.to());
    }

    @GetMapping("/reviews")
    public List<Map<String, Object>> reviews(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.reviews(range.from(), range.to());
    }

    @GetMapping("/ingestion")
    public Map<String, Object> ingestion(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.ingestion(range.from(), range.to());
    }

    @GetMapping("/errors")
    public List<Map<String, Object>> errors(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.errors(range.from(), range.to());
    }

    @GetMapping("/system")
    public Map<String, Object> system(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        DateRange range = range(from, to);
        return metrics.system(range.from(), range.to());
    }

    private DateRange range(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(13) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from must be before or equal to to");
        }
        return new DateRange(start, end);
    }

    private record DateRange(LocalDate from, LocalDate to) {}
}
