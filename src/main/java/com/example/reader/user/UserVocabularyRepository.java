package com.example.reader.user;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserVocabularyRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public UserVocabularyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SavedVocabularyItemDto> list(long userId) {
        return jdbc.query("""
                select *
                from saved_vocabulary_item
                where user_id = :userId
                order by updated_at desc, created_at desc
                """,
                new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> map(rs));
    }

    public SavedVocabularyItemDto save(long userId, SaveVocabularyRequest request) {
        ValidatedVocabulary input = validate(request);
        Optional<SavedVocabularyItemDto> existing = findExisting(
                userId,
                input.kind(),
                input.source(),
                input.target(),
                input.normalizedText()
        );

        if (existing.isPresent()) {
            return updateExisting(userId, existing.get().id(), input);
        }

        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into saved_vocabulary_item(
                    id, user_id, kind, text, normalized_text, source_lang, target_lang,
                    result, dictionary, work_id, book_id_text, title, author, page,
                    sentence_id, review_status, review_due_at, review_interval_days,
                    review_ease, review_count, created_at, updated_at
                )
                values(
                    cast(:id as uuid), :userId, :kind, :text, :normalizedText, :source, :target,
                    :result, :dictionary, :workId, :bookIdText, :title, :author, :page,
                    :sentenceId, 'new', :now, 0, 2.5, 0, :now, :now
                )
                """, params(userId, id.toString(), input).addValue("now", Timestamp.from(now)));
        return findById(userId, id.toString())
                .orElseThrow(() -> new IllegalStateException("Saved vocabulary item was not created"));
    }

    public SavedVocabularyItemDto update(long userId, String id, SaveVocabularyRequest request) {
        if (findById(userId, id).isEmpty()) {
            throw new IllegalArgumentException("Saved vocabulary item not found");
        }
        return updateExisting(userId, id, validate(request));
    }

    public Optional<SavedVocabularyItemDto> findById(long userId, String id) {
        return jdbc.query("""
                select *
                from saved_vocabulary_item
                where user_id = :userId
                  and id = cast(:id as uuid)
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("id", id),
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty());
    }

    public Optional<SavedVocabularyItemDto> findExisting(
            long userId,
            String kind,
            String source,
            String target,
            String normalizedText
    ) {
        return jdbc.query("""
                select *
                from saved_vocabulary_item
                where user_id = :userId
                  and kind = :kind
                  and source_lang = :source
                  and target_lang = :target
                  and normalized_text = :normalizedText
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("kind", kind)
                        .addValue("source", source)
                        .addValue("target", target)
                        .addValue("normalizedText", normalizedText),
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty());
    }

    public SavedVocabularyItemDto review(long userId, String id, String rawGrade) {
        SavedVocabularyItemDto item = findById(userId, id)
                .orElseThrow(() -> new IllegalArgumentException("Saved vocabulary item not found"));
        String grade = normalizeGrade(rawGrade);
        ReviewStateDto nextReview = nextReview(item.review(), grade);
        Instant now = Instant.now();
        jdbc.update("""
                update saved_vocabulary_item
                set review_status = :status,
                    review_due_at = :dueAt,
                    review_interval_days = :intervalDays,
                    review_ease = :ease,
                    review_count = :reviewCount,
                    last_reviewed_at = :lastReviewedAt,
                    updated_at = :updatedAt
                where user_id = :userId
                  and id = cast(:id as uuid)
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("id", id)
                        .addValue("status", nextReview.status())
                        .addValue("dueAt", Timestamp.from(Instant.ofEpochMilli(nextReview.dueAt())))
                        .addValue("intervalDays", nextReview.intervalDays())
                        .addValue("ease", nextReview.ease())
                        .addValue("reviewCount", nextReview.reviewCount())
                        .addValue("lastReviewedAt", Timestamp.from(Instant.ofEpochMilli(nextReview.lastReviewedAt())))
                        .addValue("updatedAt", Timestamp.from(now)));
        return findById(userId, id)
                .orElseThrow(() -> new IllegalStateException("Reviewed vocabulary item disappeared"));
    }

    public void delete(long userId, String id) {
        jdbc.update("""
                delete from saved_vocabulary_item
                where user_id = :userId
                  and id = cast(:id as uuid)
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("id", id));
    }

    public ImportOutcome importItem(long userId, SavedVocabularyItemDto item) {
        ImportedVocabulary input = validateImported(item);
        Optional<SavedVocabularyItemDto> existing = findExisting(
                userId,
                input.kind(),
                input.source(),
                input.target(),
                input.normalizedText()
        );

        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into saved_vocabulary_item(
                        id, user_id, kind, text, normalized_text, source_lang, target_lang,
                        result, dictionary, work_id, book_id_text, title, author, page,
                        sentence_id, review_status, review_due_at, review_interval_days,
                        review_ease, review_count, last_reviewed_at, created_at, updated_at
                    )
                    values(
                        cast(:id as uuid), :userId, :kind, :text, :normalizedText, :source, :target,
                        :result, :dictionary, :workId, :bookIdText, :title, :author, :page,
                        :sentenceId, :reviewStatus, :reviewDueAt, :reviewIntervalDays,
                        :reviewEase, :reviewCount, :lastReviewedAt, :createdAt, :updatedAt
                    )
                    """, importParams(userId, id.toString(), input));
            return ImportOutcome.CREATED;
        }

        SavedVocabularyItemDto current = existing.get();
        if (!shouldReplace(current, input)) {
            return ImportOutcome.SKIPPED;
        }

        jdbc.update("""
                update saved_vocabulary_item
                set text = :text,
                    normalized_text = :normalizedText,
                    result = :result,
                    dictionary = :dictionary,
                    work_id = :workId,
                    book_id_text = :bookIdText,
                    title = :title,
                    author = :author,
                    page = :page,
                    sentence_id = :sentenceId,
                    review_status = :reviewStatus,
                    review_due_at = :reviewDueAt,
                    review_interval_days = :reviewIntervalDays,
                    review_ease = :reviewEase,
                    review_count = :reviewCount,
                    last_reviewed_at = :lastReviewedAt,
                    updated_at = :updatedAt
                where user_id = :userId
                  and id = cast(:id as uuid)
                """, importParams(userId, current.id(), input));
        return ImportOutcome.UPDATED;
    }

    private SavedVocabularyItemDto updateExisting(long userId, String id, ValidatedVocabulary input) {
        jdbc.update("""
                update saved_vocabulary_item
                set text = :text,
                    result = :result,
                    dictionary = :dictionary,
                    work_id = :workId,
                    book_id_text = :bookIdText,
                    title = :title,
                    author = :author,
                    page = :page,
                    sentence_id = :sentenceId,
                    updated_at = now()
                where user_id = :userId
                  and id = cast(:id as uuid)
                """, params(userId, id, input));
        return findById(userId, id)
                .orElseThrow(() -> new IllegalStateException("Updated vocabulary item disappeared"));
    }

    private MapSqlParameterSource params(long userId, String id, ValidatedVocabulary input) {
        return new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("kind", input.kind())
                .addValue("text", input.text())
                .addValue("normalizedText", input.normalizedText())
                .addValue("source", input.source())
                .addValue("target", input.target())
                .addValue("result", input.result())
                .addValue("dictionary", input.dictionary())
                .addValue("workId", resolveExistingWorkId(input.bookId()))
                .addValue("bookIdText", input.bookId())
                .addValue("title", input.title())
                .addValue("author", input.author())
                .addValue("page", input.page())
                .addValue("sentenceId", input.sentenceId());
    }

    private MapSqlParameterSource importParams(long userId, String id, ImportedVocabulary input) {
        return new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("kind", input.kind())
                .addValue("text", input.text())
                .addValue("normalizedText", input.normalizedText())
                .addValue("source", input.source())
                .addValue("target", input.target())
                .addValue("result", input.result())
                .addValue("dictionary", input.dictionary())
                .addValue("workId", resolveExistingWorkId(input.bookId()))
                .addValue("bookIdText", input.bookId())
                .addValue("title", input.title())
                .addValue("author", input.author())
                .addValue("page", input.page())
                .addValue("sentenceId", input.sentenceId())
                .addValue("reviewStatus", input.review().status())
                .addValue("reviewDueAt", Timestamp.from(Instant.ofEpochMilli(input.review().dueAt())))
                .addValue("reviewIntervalDays", input.review().intervalDays())
                .addValue("reviewEase", input.review().ease())
                .addValue("reviewCount", input.review().reviewCount())
                .addValue("lastReviewedAt", input.review().lastReviewedAt() == null
                        ? null
                        : Timestamp.from(Instant.ofEpochMilli(input.review().lastReviewedAt())))
                .addValue("createdAt", Timestamp.from(Instant.ofEpochMilli(input.createdAt())))
                .addValue("updatedAt", Timestamp.from(Instant.ofEpochMilli(input.updatedAt())));
    }

    private ValidatedVocabulary validate(SaveVocabularyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        String kind = trimLower(request.kind());
        if (!"word".equals(kind) && !"sentence".equals(kind)) {
            throw new IllegalArgumentException("kind must be word or sentence");
        }
        String text = trimToNull(request.text());
        String source = trimLower(request.source());
        String target = trimLower(request.target());
        String result = trimToNull(request.result());
        String bookId = trimToNull(request.bookId());
        if (!StringUtils.hasText(text)) throw new IllegalArgumentException("text is required");
        if (!StringUtils.hasText(source)) throw new IllegalArgumentException("source is required");
        if (!StringUtils.hasText(target)) throw new IllegalArgumentException("target is required");
        if (!StringUtils.hasText(result)) throw new IllegalArgumentException("result is required");
        if (!StringUtils.hasText(bookId)) throw new IllegalArgumentException("bookId is required");
        int page = request.page() == null ? 0 : request.page();
        if (page < 0) throw new IllegalArgumentException("page must be >= 0");
        return new ValidatedVocabulary(
                kind,
                text,
                normalizeSavedText(text),
                source,
                target,
                result,
                request.dictionary() != null && request.dictionary(),
                bookId,
                trimToNull(request.title()),
                trimToNull(request.author()),
                page,
                request.sentenceId()
        );
    }

    private ImportedVocabulary validateImported(SavedVocabularyItemDto item) {
        if (item == null) {
            throw new IllegalArgumentException("Vocabulary item is required");
        }
        ValidatedVocabulary base = validate(new SaveVocabularyRequest(
                item.kind(),
                item.text(),
                item.source(),
                item.target(),
                item.result(),
                item.dictionary(),
                item.bookId(),
                item.title(),
                item.author(),
                item.page(),
                item.sentenceId()
        ));
        long now = System.currentTimeMillis();
        long createdAt = item.createdAt() > 0 ? item.createdAt() : now;
        long updatedAt = item.updatedAt() > 0 ? item.updatedAt() : createdAt;
        return new ImportedVocabulary(
                base.kind(),
                base.text(),
                StringUtils.hasText(item.normalizedText()) ? normalizeSavedText(item.normalizedText()) : base.normalizedText(),
                base.source(),
                base.target(),
                base.result(),
                base.dictionary(),
                base.bookId(),
                base.title(),
                base.author(),
                base.page(),
                base.sentenceId(),
                createdAt,
                updatedAt,
                validateReview(item.review(), now)
        );
    }

    private ReviewStateDto validateReview(ReviewStateDto review, long now) {
        if (review == null) {
            return new ReviewStateDto("new", now, 0, 2.5, 0, null);
        }
        String status = trimLower(review.status());
        if (!List.of("new", "learning", "review", "known").contains(status)) {
            status = "new";
        }
        long dueAt = review.dueAt() > 0 ? review.dueAt() : now;
        int intervalDays = Math.max(0, review.intervalDays());
        double ease = Math.max(1.3, review.ease() <= 0 ? 2.5 : review.ease());
        int reviewCount = Math.max(0, review.reviewCount());
        Long lastReviewedAt = review.lastReviewedAt() == null || review.lastReviewedAt() <= 0 ? null : review.lastReviewedAt();
        return new ReviewStateDto(status, dueAt, intervalDays, ease, reviewCount, lastReviewedAt);
    }

    private boolean shouldReplace(SavedVocabularyItemDto current, ImportedVocabulary input) {
        return input.updatedAt() > current.updatedAt()
                || input.review().reviewCount() > current.review().reviewCount();
    }

    private ReviewStateDto nextReview(ReviewStateDto review, String grade) {
        long now = System.currentTimeMillis();
        int currentInterval = Math.max(0, review.intervalDays());
        double ease = Math.max(1.3, review.ease() + ("again".equals(grade) ? -0.2 : "known".equals(grade) ? 0.15 : 0));
        int intervalDays = switch (grade) {
            case "again" -> 0;
            case "good" -> Math.max(1, Math.round((currentInterval == 0 ? 1 : currentInterval) * (float) ease));
            case "known" -> Math.max(7, Math.round((currentInterval == 0 ? 3 : currentInterval) * (float) (ease + 0.6)));
            default -> throw new IllegalArgumentException("grade must be again, good, or known");
        };
        long dueAt = "again".equals(grade) ? now : now + intervalDays * 24L * 60L * 60L * 1000L;
        return new ReviewStateDto(
                "again".equals(grade) ? "learning" : "known".equals(grade) ? "known" : "review",
                dueAt,
                intervalDays,
                ease,
                review.reviewCount() + 1,
                now
        );
    }

    private String normalizeGrade(String rawGrade) {
        String grade = trimLower(rawGrade);
        if (!"again".equals(grade) && !"good".equals(grade) && !"known".equals(grade)) {
            throw new IllegalArgumentException("grade must be again, good, or known");
        }
        return grade;
    }

    private SavedVocabularyItemDto map(ResultSet rs) throws java.sql.SQLException {
        Long lastReviewedAt = epochMillis(rs.getTimestamp("last_reviewed_at"));
        Long sentenceId = (Long) rs.getObject("sentence_id");
        String bookId = rs.getString("book_id_text");
        if (!StringUtils.hasText(bookId)) {
            Long workId = (Long) rs.getObject("work_id");
            bookId = workId == null ? "" : String.valueOf(workId);
        }
        return new SavedVocabularyItemDto(
                rs.getString("id"),
                rs.getString("kind"),
                rs.getString("text"),
                rs.getString("normalized_text"),
                rs.getString("source_lang"),
                rs.getString("target_lang"),
                rs.getString("result"),
                rs.getBoolean("dictionary"),
                bookId,
                rs.getString("title"),
                rs.getString("author"),
                rs.getInt("page"),
                sentenceId,
                epochMillis(rs.getTimestamp("created_at")),
                epochMillis(rs.getTimestamp("updated_at")),
                new ReviewStateDto(
                        rs.getString("review_status"),
                        epochMillis(rs.getTimestamp("review_due_at")),
                        rs.getInt("review_interval_days"),
                        ((BigDecimal) rs.getObject("review_ease")).doubleValue(),
                        rs.getInt("review_count"),
                        lastReviewedAt
                )
        );
    }

    private static String normalizeSavedText(String text) {
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String trimLower(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? "" : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long resolveExistingWorkId(String bookId) {
        Long parsed = parseLong(bookId);
        if (parsed == null) {
            return null;
        }
        List<Long> ids = jdbc.query(
                """
                select id
                from work
                where id = :id
                """,
                new MapSqlParameterSource("id", parsed),
                (rs, rowNum) -> rs.getLong("id")
        );
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static long epochMillis(Timestamp timestamp) {
        return timestamp == null ? 0 : timestamp.toInstant().toEpochMilli();
    }

    private record ValidatedVocabulary(
            String kind,
            String text,
            String normalizedText,
            String source,
            String target,
            String result,
            boolean dictionary,
            String bookId,
            String title,
            String author,
            int page,
            Long sentenceId
    ) {}

    private record ImportedVocabulary(
            String kind,
            String text,
            String normalizedText,
            String source,
            String target,
            String result,
            boolean dictionary,
            String bookId,
            String title,
            String author,
            int page,
            Long sentenceId,
            long createdAt,
            long updatedAt,
            ReviewStateDto review
    ) {}

    public enum ImportOutcome {
        CREATED,
        UPDATED,
        SKIPPED
    }
}
