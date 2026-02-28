package com.example.reader.ingestion;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

@Repository
public class IngestionJobRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public IngestionJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.getJdbcTemplate().execute("""
            create table if not exists ingestion_job (
              id bigserial primary key,
              status varchar(32) not null,
              step varchar(128),
              message text,
              error text,
              work_title text not null,
              lang varchar(16) not null,
              file_spec text not null,
              pub_year integer,
              author text,
              publisher text,
              translator text,
              original_lang varchar(16),
              work_id bigint,
              edition_id bigint,
              chapters integer not null default 0,
              paragraphs integer not null default 0,
              sentences integer not null default 0,
              created_at timestamptz not null default now(),
              started_at timestamptz,
              finished_at timestamptz
            )
            """);
        jdbc.getJdbcTemplate().execute("""
            create index if not exists idx_ingestion_job_created_at
              on ingestion_job(created_at desc)
            """);
        jdbc.getJdbcTemplate().execute("""
            create index if not exists idx_ingestion_job_status
              on ingestion_job(status)
            """);
    }

    public long create(IngestionRequest request) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", "PENDING")
                .addValue("step", "queued")
                .addValue("message", "Job queued")
                .addValue("workTitle", request.workTitle())
                .addValue("lang", request.lang())
                .addValue("fileSpec", request.fileSpec())
                .addValue("year", request.year())
                .addValue("author", request.author())
                .addValue("publisher", request.publisher())
                .addValue("translator", request.translator())
                .addValue("originalLang", request.originalLang());

        jdbc.update("""
            insert into ingestion_job(
                status, step, message, work_title, lang, file_spec, pub_year,
                author, publisher, translator, original_lang
            )
            values(
                :status, :step, :message, :workTitle, :lang, :fileSpec, :year,
                :author, :publisher, :translator, :originalLang
            )
            returning id
            """, params, key);

        Number idVal = (Number) Objects.requireNonNull(key.getKeys()).get("id");
        return idVal.longValue();
    }

    public void markRunning(long jobId) {
        jdbc.update("""
            update ingestion_job
            set status = 'RUNNING',
                step = 'started',
                message = 'Job started',
                started_at = now(),
                error = null
            where id = :id
            """, new MapSqlParameterSource("id", jobId));
    }

    public void updateProgress(long jobId, String step, String message, int chapters, int paragraphs, int sentences) {
        jdbc.update("""
            update ingestion_job
            set step = :step,
                message = :message,
                chapters = :chapters,
                paragraphs = :paragraphs,
                sentences = :sentences
            where id = :id
            """, new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("step", step)
                .addValue("message", message)
                .addValue("chapters", chapters)
                .addValue("paragraphs", paragraphs)
                .addValue("sentences", sentences));
    }

    public void markSucceeded(long jobId, IngestionResult result) {
        jdbc.update("""
            update ingestion_job
            set status = 'SUCCEEDED',
                step = 'done',
                message = 'Ingestion completed',
                error = null,
                work_id = :workId,
                edition_id = :editionId,
                chapters = :chapters,
                paragraphs = :paragraphs,
                sentences = :sentences,
                finished_at = now()
            where id = :id
            """, new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("workId", result.workId())
                .addValue("editionId", result.editionId())
                .addValue("chapters", result.chapters())
                .addValue("paragraphs", result.paragraphs())
                .addValue("sentences", result.sentences()));
    }

    public void markFailed(long jobId, String errorText) {
        jdbc.update("""
            update ingestion_job
            set status = 'FAILED',
                step = 'failed',
                message = 'Ingestion failed',
                error = :error,
                finished_at = now()
            where id = :id
            """, new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("error", errorText));
    }

    public IngestionJob findById(long id) {
        return jdbc.query("""
            select id, status, step, message, error, work_title, lang, file_spec,
                   pub_year, author, publisher, translator, original_lang,
                   work_id, edition_id, chapters, paragraphs, sentences,
                   created_at, started_at, finished_at
            from ingestion_job
            where id = :id
            """,
            new MapSqlParameterSource("id", id),
            rs -> {
                if (!rs.next()) return null;
                return new IngestionJob(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getString("step"),
                        rs.getString("message"),
                        rs.getString("error"),
                        rs.getString("work_title"),
                        rs.getString("lang"),
                        rs.getString("file_spec"),
                        (Integer) rs.getObject("pub_year"),
                        rs.getString("author"),
                        rs.getString("publisher"),
                        rs.getString("translator"),
                        rs.getString("original_lang"),
                        (Long) rs.getObject("work_id"),
                        (Long) rs.getObject("edition_id"),
                        rs.getInt("chapters"),
                        rs.getInt("paragraphs"),
                        rs.getInt("sentences"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("started_at")),
                        toInstant(rs.getTimestamp("finished_at"))
                );
            }
        );
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
