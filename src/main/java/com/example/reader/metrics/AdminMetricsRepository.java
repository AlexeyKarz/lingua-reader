package com.example.reader.metrics;

import com.example.reader.ingestion.IngestionJob;
import com.example.reader.ingestion.IngestionJobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class AdminMetricsRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final IngestionJobRepository ingestionJobs;
    private final ObjectMapper objectMapper;

    public AdminMetricsRepository(NamedParameterJdbcTemplate jdbc, IngestionJobRepository ingestionJobs, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.ingestionJobs = ingestionJobs;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> overview(LocalDate from, LocalDate to) {
        MapSqlParameterSource params = rangeParams(from, to);
        long translationCallsToday = count("""
                select count(*)
                from app_event
                where event_type = 'translation_requested'
                  and created_at >= date_trunc('day', now())
                """, params);
        long translationFailuresToday = count("""
                select count(*)
                from app_event
                where event_type = 'translation_failed'
                  and created_at >= date_trunc('day', now())
                """, params);
        long cacheKnownToday = count("""
                select count(*)
                from app_event
                where event_type = 'translation_requested'
                  and cache_hit is not null
                  and created_at >= date_trunc('day', now())
                """, params);
        long cacheHitsToday = count("""
                select count(*)
                from app_event
                where event_type = 'translation_requested'
                  and cache_hit = true
                  and created_at >= date_trunc('day', now())
                """, params);

        return Map.ofEntries(
                Map.entry("registeredUsers", count("select count(*) from app_user", params)),
                Map.entry("activeUsersToday", count("""
                        select count(distinct user_id)
                        from app_event
                        where user_id is not null
                          and created_at >= date_trunc('day', now())
                        """, params)),
                Map.entry("activeUsers7d", count("""
                        select count(distinct user_id)
                        from app_event
                        where user_id is not null
                          and created_at >= now() - interval '7 days'
                        """, params)),
                Map.entry("translationCallsToday", translationCallsToday),
                Map.entry("translationFailuresToday", translationFailuresToday),
                Map.entry("translationFailureRateToday", ratio(translationFailuresToday, translationCallsToday)),
                Map.entry("cacheHitRateToday", cacheKnownToday == 0 ? 0 : ratio(cacheHitsToday, cacheKnownToday)),
                Map.entry("ingestionRunning", count("""
                        select count(*)
                        from ingestion_job
                        where status in ('PENDING', 'RUNNING')
                        """, params)),
                Map.entry("ingestionFailedToday", count("""
                        select count(*)
                        from ingestion_job
                        where status = 'FAILED'
                          and created_at >= date_trunc('day', now())
                        """, params)),
                Map.entry("translationP95LatencyMs", nullableLong("""
                        select percentile_cont(0.95) within group (order by latency_ms)
                        from app_event
                        where event_type = 'translation_succeeded'
                          and latency_ms is not null
                          and created_at >= date_trunc('day', now())
                        """, params)),
                Map.entry("topLanguagePair", topLanguagePair(params))
        );
    }

    public List<Map<String, Object>> usageDaily(LocalDate from, LocalDate to, String eventType) {
        return jdbc.queryForList("""
                select to_char(days.day, 'YYYY-MM-DD') as day,
                       count(e.id) as value
                from generate_series(cast(:fromDate as date), cast(:toDate as date), interval '1 day') days(day)
                left join app_event e on date(e.created_at) = days.day
                  and e.event_type = :eventType
                group by days.day
                order by days.day
                """,
                rangeParams(from, to).addValue("eventType", eventType));
    }

    public List<Map<String, Object>> translationOperations(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                select coalesce(operation, 'unknown') as operation,
                       count(*) filter (where event_type = 'translation_requested') as calls,
                       count(*) filter (where event_type = 'translation_failed') as failures,
                       percentile_cont(0.95) within group (order by latency_ms)
                         filter (where event_type = 'translation_succeeded' and latency_ms is not null) as p95_latency_ms
                from app_event
                where created_at >= cast(:fromDate as date)
                  and created_at < cast(:toDate as date) + interval '1 day'
                  and event_type in ('translation_requested', 'translation_succeeded', 'translation_failed')
                group by coalesce(operation, 'unknown')
                order by calls desc, operation
                """, rangeParams(from, to));
    }

    public List<Map<String, Object>> usersDaily(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                select to_char(days.day, 'YYYY-MM-DD') as day,
                       count(distinct e.user_id) as value
                from generate_series(cast(:fromDate as date), cast(:toDate as date), interval '1 day') days(day)
                left join app_event e on date(e.created_at) = days.day
                  and e.user_id is not null
                  and e.event_type in (
                    'login_succeeded', 'catalog_viewed', 'reader_page_viewed',
                    'translation_requested', 'vocabulary_saved', 'vocabulary_reviewed',
                    'ingestion_job_submitted', 'generated_literary_job_submitted'
                  )
                group by days.day
                order by days.day
                """, rangeParams(from, to));
    }

    public List<Map<String, Object>> translationsDaily(LocalDate from, LocalDate to) {
        return usageDaily(from, to, "translation_requested");
    }

    public List<Map<String, Object>> translationProviders(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                select coalesce(provider, 'unknown') as label,
                       count(*) filter (where event_type = 'translation_requested') as calls,
                       count(*) filter (where event_type = 'translation_failed') as failures,
                       percentile_cont(0.95) within group (order by latency_ms)
                         filter (where event_type = 'translation_succeeded' and latency_ms is not null) as p95_latency_ms
                from app_event
                where created_at >= cast(:fromDate as date)
                  and created_at < cast(:toDate as date) + interval '1 day'
                  and event_type in ('translation_requested', 'translation_succeeded', 'translation_failed')
                group by coalesce(provider, 'unknown')
                order by calls desc, label
                """, rangeParams(from, to));
    }

    public List<Map<String, Object>> translationLanguagePairs(LocalDate from, LocalDate to) {
        return languagePairsForEvents(from, to, List.of("translation_requested", "translation_succeeded", "translation_failed"));
    }

    public List<Map<String, Object>> readerLanguagePairs(LocalDate from, LocalDate to) {
        return languagePairsForEvents(from, to, List.of("reader_page_viewed", "reader_pair_opened"));
    }

    public List<Map<String, Object>> vocabulary(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                select event_type as label,
                       count(*) as value
                from app_event
                where created_at >= cast(:fromDate as date)
                  and created_at < cast(:toDate as date) + interval '1 day'
                  and event_type in ('vocabulary_saved', 'vocabulary_reviewed', 'vocabulary_deleted', 'vocabulary_imported', 'local_state_imported')
                group by event_type
                order by value desc, label
                """, rangeParams(from, to));
    }

    public List<Map<String, Object>> reviews(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                select coalesce(metadata_json ->> 'grade', 'unknown') as label,
                       count(*) as value
                from app_event
                where created_at >= cast(:fromDate as date)
                  and created_at < cast(:toDate as date) + interval '1 day'
                  and event_type = 'vocabulary_reviewed'
                group by coalesce(metadata_json ->> 'grade', 'unknown')
                order by value desc, label
                """, rangeParams(from, to));
    }

    public List<Map<String, Object>> topBooks(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                select coalesce(w.title, 'Work ' || e.work_id::text, 'Unknown') as label,
                       count(*) as value
                from app_event e
                left join work w on w.id = e.work_id
                where e.created_at >= cast(:fromDate as date)
                  and e.created_at < cast(:toDate as date) + interval '1 day'
                  and e.event_type = 'reader_page_viewed'
                group by coalesce(w.title, 'Work ' || e.work_id::text, 'Unknown')
                order by value desc, label
                limit 10
                """, rangeParams(from, to));
    }

    public List<Map<String, Object>> languagePairs(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                select upper(coalesce(source_lang, '?')) || ' -> ' || upper(coalesce(target_lang, '?')) as label,
                       count(*) as value
                from app_event
                where created_at >= cast(:fromDate as date)
                  and created_at < cast(:toDate as date) + interval '1 day'
                  and source_lang is not null
                  and target_lang is not null
                group by source_lang, target_lang
                order by value desc, label
                limit 10
                """, rangeParams(from, to));
    }

    private List<Map<String, Object>> languagePairsForEvents(LocalDate from, LocalDate to, List<String> eventTypes) {
        return jdbc.queryForList("""
                select upper(coalesce(source_lang, '?')) || ' -> ' || upper(coalesce(target_lang, '?')) as label,
                       count(*) as value
                from app_event
                where created_at >= cast(:fromDate as date)
                  and created_at < cast(:toDate as date) + interval '1 day'
                  and source_lang is not null
                  and target_lang is not null
                  and event_type in (:eventTypes)
                group by source_lang, target_lang
                order by value desc, label
                limit 10
                """, rangeParams(from, to).addValue("eventTypes", eventTypes));
    }

    public Map<String, Object> ingestion(LocalDate from, LocalDate to) {
        MapSqlParameterSource params = rangeParams(from, to);
        return Map.of(
                "byStatus", jdbc.queryForList("""
                        select status as label, count(*) as value
                        from ingestion_job
                        where created_at >= cast(:fromDate as date)
                          and created_at < cast(:toDate as date) + interval '1 day'
                        group by status
                        order by value desc, status
                        """, params),
                "averageDurationSeconds", nullableLong("""
                        select avg(extract(epoch from (finished_at - started_at)))
                        from ingestion_job
                        where started_at is not null
                          and finished_at is not null
                          and created_at >= cast(:fromDate as date)
                          and created_at < cast(:toDate as date) + interval '1 day'
                        """, params),
                "recent", ingestionJobs.findRecent(10)
        );
    }

    public List<Map<String, Object>> errors(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                select coalesce(error_code, event_type) as label,
                       count(*) as value
                from app_event
                where created_at >= cast(:fromDate as date)
                  and created_at < cast(:toDate as date) + interval '1 day'
                  and (success = false or event_type like '%_failed')
                group by coalesce(error_code, event_type)
                order by value desc, label
                limit 20
                """, rangeParams(from, to));
    }

    public Map<String, Object> system(LocalDate from, LocalDate to) {
        Integer db = jdbc.queryForObject("select 1", new MapSqlParameterSource(), Integer.class);
        return Map.of(
                "database", db != null && db == 1 ? "ok" : "unknown",
                "providerFailures", translationProviders(from, to).stream()
                        .mapToLong(row -> ((Number) row.getOrDefault("failures", 0)).longValue())
                        .sum(),
                "rawEventRetentionDays", 90,
                "dailyAggregateRetentionDays", 730,
                "adminAuditRetentionDays", 365
        );
    }

    public void refreshDailyAggregates(LocalDate day) {
        jdbc.update("""
                delete from app_metric_daily
                where day = :day
                """, new MapSqlParameterSource("day", day));

        saveAggregate(day, "active_users", Map.of(), count("""
                select count(distinct user_id)
                from app_event
                where user_id is not null
                  and created_at >= cast(:day as date)
                  and created_at < cast(:day as date) + interval '1 day'
                """, new MapSqlParameterSource("day", day)));
        saveAggregate(day, "translation_calls", Map.of(), countEvent(day, "translation_requested"));
        saveAggregate(day, "translation_failures", Map.of(), countEvent(day, "translation_failed"));
        saveAggregate(day, "reader_page_views", Map.of(), countEvent(day, "reader_page_viewed"));
        saveAggregate(day, "vocabulary_saves", Map.of(), countEvent(day, "vocabulary_saved"));
        saveAggregate(day, "vocabulary_reviews", Map.of(), countEvent(day, "vocabulary_reviewed"));
        saveAggregate(day, "ingestion_succeeded", Map.of(), countEvent(day, "ingestion_job_succeeded"));
        saveAggregate(day, "ingestion_failed", Map.of(), countEvent(day, "ingestion_job_failed"));

        for (Map<String, Object> row : translationOperations(day, day)) {
            saveAggregate(day, "translation_calls_by_operation", Map.of("operation", row.get("operation")), numeric(row.get("calls")));
        }
        for (Map<String, Object> row : translationLanguagePairs(day, day)) {
            saveAggregate(day, "translation_calls_by_language_pair", Map.of("pair", row.get("label")), numeric(row.get("value")));
        }
    }

    public void pruneRetention() {
        jdbc.update("""
                delete from app_event
                where created_at < now() - interval '90 days'
                """, new MapSqlParameterSource());
        jdbc.update("""
                delete from app_metric_daily
                where day < current_date - interval '730 days'
                """, new MapSqlParameterSource());
        jdbc.update("""
                delete from admin_audit_event
                where created_at < now() - interval '365 days'
                """, new MapSqlParameterSource());
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private Long nullableLong(String sql, MapSqlParameterSource params) {
        Number value = jdbc.queryForObject(sql, params, Number.class);
        return value == null ? 0L : Math.round(value.doubleValue());
    }

    private long countEvent(LocalDate day, String eventType) {
        return count("""
                select count(*)
                from app_event
                where event_type = :eventType
                  and created_at >= cast(:day as date)
                  and created_at < cast(:day as date) + interval '1 day'
                """, new MapSqlParameterSource()
                .addValue("day", day)
                .addValue("eventType", eventType));
    }

    private void saveAggregate(LocalDate day, String metric, Map<String, ?> dimensions, Number value) {
        jdbc.update("""
                insert into app_metric_daily(day, metric, dimensions_json, value)
                values(:day, :metric, cast(:dimensions as jsonb), :value)
                on conflict(day, metric, dimensions_json)
                do update set value = excluded.value
                """,
                new MapSqlParameterSource()
                        .addValue("day", day)
                        .addValue("metric", metric)
                        .addValue("dimensions", writeJson(dimensions))
                        .addValue("value", value == null ? BigDecimal.ZERO : new BigDecimal(value.toString())));
    }

    private long numeric(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String writeJson(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String topLanguagePair(MapSqlParameterSource params) {
        List<String> pairs = jdbc.query("""
                select upper(source_lang) || ' -> ' || upper(target_lang) as pair
                from app_event
                where source_lang is not null
                  and target_lang is not null
                  and created_at >= cast(:fromDate as date)
                  and created_at < cast(:toDate as date) + interval '1 day'
                group by source_lang, target_lang
                order by count(*) desc
                limit 1
                """, params, (rs, rowNum) -> rs.getString("pair"));
        return pairs.isEmpty() ? "None" : pairs.getFirst();
    }

    private double ratio(long numerator, long denominator) {
        if (denominator == 0) return 0;
        return Math.round((numerator * 1000.0 / denominator)) / 10.0;
    }

    private MapSqlParameterSource rangeParams(LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("fromDate", from)
                .addValue("toDate", to);
    }
}
