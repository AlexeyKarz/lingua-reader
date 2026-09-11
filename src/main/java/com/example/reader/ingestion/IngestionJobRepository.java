package com.example.reader.ingestion;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
              description text,
              publisher text,
              translator text,
              original_lang varchar(16),
              body_only boolean,
              replace_existing boolean,
              max_source_chars integer,
              chunk_max_chars integer,
              context_chars integer,
              generation_model text,
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
        jdbc.getJdbcTemplate().execute("""
            alter table ingestion_job
              add column if not exists description text
            """);
        jdbc.getJdbcTemplate().execute("""
            alter table ingestion_job
              add column if not exists body_only boolean
            """);
        jdbc.getJdbcTemplate().execute("""
            alter table ingestion_job
              add column if not exists replace_existing boolean
            """);
        jdbc.getJdbcTemplate().execute("""
            alter table ingestion_job
              add column if not exists max_source_chars integer
            """);
        jdbc.getJdbcTemplate().execute("""
            alter table ingestion_job
              add column if not exists chunk_max_chars integer
            """);
        jdbc.getJdbcTemplate().execute("""
            alter table ingestion_job
              add column if not exists context_chars integer
            """);
        jdbc.getJdbcTemplate().execute("""
            alter table ingestion_job
              add column if not exists generation_model text
            """);
    }

    public long create(IngestionRequest request) {
        return create(request, null);
    }

    public long createGenerated(GenerateLiteraryEditionRequest request) {
        return create(request.toJobRequest(), request);
    }

    private long create(IngestionRequest request, GenerateLiteraryEditionRequest generationRequest) {
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
                .addValue("description", request.description())
                .addValue("publisher", request.publisher())
                .addValue("translator", request.translator())
                .addValue("originalLang", request.originalLang())
                .addValue("bodyOnly", generationRequest == null ? null : generationRequest.bodyOnly())
                .addValue("replaceExisting", generationRequest == null ? null : generationRequest.replaceExisting())
                .addValue("maxSourceChars", generationRequest == null ? null : generationRequest.maxSourceChars())
                .addValue("chunkMaxChars", generationRequest == null ? null : generationRequest.chunkMaxChars())
                .addValue("contextChars", generationRequest == null ? null : generationRequest.contextChars())
                .addValue("model", generationRequest == null ? null : generationRequest.model());

        jdbc.update("""
            insert into ingestion_job(
                status, step, message, work_title, lang, file_spec, pub_year,
                author, description, publisher, translator, original_lang,
                body_only, replace_existing, max_source_chars, chunk_max_chars, context_chars,
                generation_model
            )
            values(
                :status, :step, :message, :workTitle, :lang, :fileSpec, :year,
                :author, :description, :publisher, :translator, :originalLang,
                :bodyOnly, :replaceExisting, :maxSourceChars, :chunkMaxChars, :contextChars,
                :model
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
                   pub_year, author, description, publisher, translator, original_lang,
                   body_only, replace_existing, max_source_chars, chunk_max_chars, context_chars,
                   generation_model,
                   work_id, edition_id, chapters, paragraphs, sentences,
                   created_at, started_at, finished_at
            from ingestion_job
            where id = :id
            """,
            new MapSqlParameterSource("id", id),
            rs -> {
                if (!rs.next()) return null;
                return mapJob(rs);
            }
        );
    }

    public List<IngestionJob> findRecent(int limit) {
        return jdbc.query("""
            select id, status, step, message, error, work_title, lang, file_spec,
                   pub_year, author, description, publisher, translator, original_lang,
                   body_only, replace_existing, max_source_chars, chunk_max_chars, context_chars,
                   generation_model,
                   work_id, edition_id, chapters, paragraphs, sentences,
                   created_at, started_at, finished_at
            from ingestion_job
            order by created_at desc, id desc
            limit :limit
            """,
            new MapSqlParameterSource("limit", limit),
            (rs, rowNum) -> mapJob(rs)
        );
    }

    private IngestionJob mapJob(java.sql.ResultSet rs) throws java.sql.SQLException {
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
                rs.getString("description"),
                rs.getString("publisher"),
                rs.getString("translator"),
                rs.getString("original_lang"),
                (Boolean) rs.getObject("body_only"),
                (Boolean) rs.getObject("replace_existing"),
                (Integer) rs.getObject("max_source_chars"),
                (Integer) rs.getObject("chunk_max_chars"),
                (Integer) rs.getObject("context_chars"),
                rs.getString("generation_model"),
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

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
