package com.example.reader.ingestion;

import com.example.reader.translate.AdminTranslationContext;
import com.example.reader.translate.TranslateService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
public class LiteraryEditionGenerationService {
    private final JdbcTemplate jdbc;
    private final SegmentWriter writer;
    private final EpubExtractor extractor;
    private final TranslateService translateService;

    public LiteraryEditionGenerationService(
            JdbcTemplate jdbc,
            SegmentWriter writer,
            EpubExtractor extractor,
            TranslateService translateService
    ) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.extractor = extractor;
        this.translateService = translateService;
    }

    public IngestionResult generate(GenerateLiteraryEditionRequest rawRequest, IngestionProgressSink sink) throws Exception {
        GenerateLiteraryEditionRequest request = rawRequest.withDefaults();
        request.validate();

        publish(sink, "resolve_source", "Resolving source edition", 0, 0, 0);
        SourceEdition source = sourceEdition(request.workTitle(), request.sourceLang());
        List<SourceParagraph> paragraphs = sourceParagraphs(source.editionId(), source.bodyStartSegmentId(), request.bodyOnly());
        Long effectiveBodyStart = request.bodyOnly() ? source.bodyStartSegmentId() : null;
        if (paragraphs.isEmpty()) {
            throw new IllegalArgumentException("No source paragraphs found for " + request.workTitle() + " [" + request.sourceLang() + "]");
        }

        int sourceChars = paragraphs.stream().mapToInt(p -> p.text().length()).sum();
        if (sourceChars > request.maxSourceChars()) {
            throw new IllegalArgumentException(
                    "Source text is too large for AI literary generation: " + sourceChars + " > " + request.maxSourceChars()
            );
        }

        int estimatedChunks = paragraphs.stream()
                .mapToInt(p -> chunkText(p.text(), request.chunkMaxChars()).size())
                .sum();
        publish(
                sink,
                "estimate",
                "Generating " + estimatedChunks + " translation request chunks from " + sourceChars + " source characters",
                0,
                0,
                0
        );

        String sourceUri = "ai-literary://" + request.sourceLang() + "/" + request.targetLang() + "/" + source.workId();
        long editionId = ensureGeneratedEdition(
                source.workId(),
                request.targetLang(),
                request.publisher(),
                request.translator(),
                request.year(),
                sourceUri,
                request.replaceExisting()
        );

        int chapters = 0;
        int translatedParagraphs = 0;
        int sentences = 0;
        Long currentSourceChapterId = null;
        Long generatedChapterId = null;
        Long firstSentenceId = null;
        String previousTranslatedParagraph = "";

        for (SourceParagraph paragraph : paragraphs) {
            if (currentSourceChapterId == null || !currentSourceChapterId.equals(paragraph.chapterId())) {
                generatedChapterId = writer.insertSegment(
                        editionId,
                        "chapter",
                        paragraph.chapterOrd(),
                        null,
                        paragraph.chapterTitle(),
                        "ai-literary:chapter:" + paragraph.chapterId()
                );
                currentSourceChapterId = paragraph.chapterId();
                chapters++;
            }

            List<String> translatedChunks = new ArrayList<>();
            List<String> chunks = chunkText(paragraph.text(), request.chunkMaxChars());
            for (int i = 0; i < chunks.size(); i++) {
                String context = generationContext(
                        request,
                        source,
                        paragraph,
                        translatedParagraphs + 1,
                        paragraphs.size(),
                        i + 1,
                        chunks.size(),
                        previousTranslatedParagraph
                );
                String chunk = chunks.get(i);
                translatedChunks.add(AdminTranslationContext.call(
                        () -> translateService.literary(chunk, request.sourceLang(), request.targetLang(), context, request.model())
                ));
            }

            String translatedParagraph = String.join(" ", translatedChunks).trim();
            if (translatedParagraph.isBlank()) {
                throw new IllegalStateException("AI literary generation returned empty text for paragraph " + paragraph.paragraphOrd());
            }
            long paragraphId = writer.insertSegment(
                    editionId,
                    "paragraph",
                    paragraph.paragraphOrd(),
                    generatedChapterId,
                    translatedParagraph,
                    "ai-literary:paragraph:" + paragraph.paragraphId(),
                    paragraph.speaker()
            );

            List<Long> generatedSentenceIds = new ArrayList<>();
            for (EpubExtractor.Sent sentence : extractor.splitSentences(request.targetLang(), translatedParagraph)) {
                long sentenceId = writer.insertSegment(
                        editionId,
                        "sentence",
                        sentence.ord(),
                        paragraphId,
                        sentence.text(),
                        "ai-literary:sentence:" + paragraph.paragraphId() + ":" + sentence.ord(),
                        paragraph.speaker()
                );
                if (firstSentenceId == null) {
                    firstSentenceId = sentenceId;
                }
                generatedSentenceIds.add(sentenceId);
                sentences++;
            }
            insertGeneratedAlignments(
                    source.workId(),
                    source.editionId(),
                    editionId,
                    request.sourceLang(),
                    request.targetLang(),
                    paragraph.paragraphId(),
                    generatedSentenceIds,
                    effectiveBodyStart
            );

            previousTranslatedParagraph = translatedParagraph;
            translatedParagraphs++;
            if (translatedParagraphs % 10 == 0 || translatedParagraphs == paragraphs.size()) {
                publish(
                        sink,
                        "generate_literary",
                        "Generated paragraph " + translatedParagraphs + " / " + paragraphs.size(),
                        chapters,
                        translatedParagraphs,
                        sentences
                );
            }
        }

        if (firstSentenceId != null) {
            jdbc.update("update edition set body_start_segment_id = ? where id = ?", firstSentenceId, editionId);
        }

        publish(sink, "done", "Generated literary edition", chapters, translatedParagraphs, sentences);
        return new IngestionResult(source.workId(), editionId, chapters, translatedParagraphs, sentences);
    }

    private SourceEdition sourceEdition(String workTitle, String sourceLang) {
        List<SourceEdition> rows = jdbc.query(
                """
                select w.id as work_id, w.title, w.author, e.id as edition_id, e.body_start_segment_id
                from work w
                join edition e on e.work_id = w.id
                where w.title = ?
                  and e.lang = ?
                order by e.id desc
                limit 1
                """,
                (rs, rowNum) -> new SourceEdition(
                        rs.getLong("work_id"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getLong("edition_id"),
                        (Long) rs.getObject("body_start_segment_id")
                ),
                workTitle,
                sourceLang
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Source edition not found for " + workTitle + " [" + sourceLang + "]");
        }
        return rows.getFirst();
    }

    private List<SourceParagraph> sourceParagraphs(long editionId, Long bodyStartSegmentId, boolean bodyOnly) {
        Long effectiveBodyStart = bodyOnly ? bodyStartSegmentId : null;
        if (effectiveBodyStart == null) {
            return jdbc.query(
                    """
                    select ch.id as chapter_id,
                           ch.ord as chapter_ord,
                           ch.text as chapter_title,
                           p.id as paragraph_id,
                           p.ord as paragraph_ord,
                           p.text as paragraph_text,
                           p.speaker
                    from segment p
                    left join segment ch on ch.id = p.parent_id and ch.level = 'chapter'
                    where p.edition_id = ?
                      and p.level = 'paragraph'
                    order by coalesce(ch.ord, 0), p.ord, p.id
                    """,
                    this::mapSourceParagraph,
                    editionId
            );
        }

        return jdbc.query(
                """
                select ch.id as chapter_id,
                       ch.ord as chapter_ord,
                       ch.text as chapter_title,
                       p.id as paragraph_id,
                       p.ord as paragraph_ord,
                       p.text as paragraph_text,
                       p.speaker
                from segment p
                left join segment ch on ch.id = p.parent_id and ch.level = 'chapter'
                where p.edition_id = ?
                  and p.level = 'paragraph'
                  and exists (
                    select 1
                    from segment s
                    where s.parent_id = p.id
                      and s.level = 'sentence'
                      and s.id >= ?
                  )
                order by coalesce(ch.ord, 0), p.ord, p.id
                """,
                this::mapSourceParagraph,
                editionId,
                effectiveBodyStart
        );
    }

    private SourceParagraph mapSourceParagraph(ResultSet rs, int rowNum) throws SQLException {
        return new SourceParagraph(
                rs.getLong("chapter_id"),
                rs.getInt("chapter_ord"),
                rs.getString("chapter_title") == null ? "Chapter " + rs.getInt("chapter_ord") : rs.getString("chapter_title"),
                rs.getLong("paragraph_id"),
                rs.getInt("paragraph_ord"),
                rs.getString("paragraph_text"),
                rs.getString("speaker")
        );
    }

    private List<Long> sourceSentenceIds(long paragraphId, Long bodyStartSegmentId) {
        if (bodyStartSegmentId == null) {
            return jdbc.query(
                    """
                    select id
                    from segment
                    where parent_id = ?
                      and level = 'sentence'
                    order by id
                    """,
                    (rs, rowNum) -> rs.getLong("id"),
                    paragraphId
            );
        }

        return jdbc.query(
                """
                select id
                from segment
                where parent_id = ?
                  and level = 'sentence'
                  and id >= ?
                order by id
                """,
                (rs, rowNum) -> rs.getLong("id"),
                paragraphId,
                bodyStartSegmentId
        );
    }

    private void insertGeneratedAlignments(
            long workId,
            long sourceEditionId,
            long generatedEditionId,
            String sourceLang,
            String targetLang,
            long sourceParagraphId,
            List<Long> generatedSentenceIds,
            Long bodyStartSegmentId
    ) {
        List<Long> sourceSentenceIds = sourceSentenceIds(sourceParagraphId, bodyStartSegmentId);
        if (sourceSentenceIds.isEmpty() || generatedSentenceIds.isEmpty()) {
            return;
        }

        String method = "ai-literary-v1-" + sourceLang + "-" + targetLang;
        int sourceLast = sourceSentenceIds.size() - 1;
        int targetLast = generatedSentenceIds.size() - 1;
        for (int i = 0; i < sourceSentenceIds.size(); i++) {
            int targetIndex = sourceLast == 0 ? 0 : (int) Math.round((double) i * targetLast / sourceLast);
            jdbc.update(
                    """
                    insert into alignment(
                      work_id, edition_src_id, edition_tgt_id, left_segment_id, right_segment_id, method, score
                    )
                    values (?, ?, ?, ?, ?, ?, ?)
                    on conflict on constraint alignment_left_right_unique do nothing
                    """,
                    workId,
                    sourceEditionId,
                    generatedEditionId,
                    sourceSentenceIds.get(i),
                    generatedSentenceIds.get(targetIndex),
                    method,
                    1.0
            );
        }
    }

    private long ensureGeneratedEdition(
            long workId,
            String lang,
            String publisher,
            String translator,
            Integer year,
            String sourceUri,
            boolean replaceExisting
    ) {
        List<Long> existing = jdbc.query(
                """
                select id
                from edition
                where work_id = ?
                  and lang = ?
                  and translator = ?
                order by id desc
                limit 1
                """,
                (rs, rowNum) -> rs.getLong("id"),
                workId,
                lang,
                translator
        );

        if (!existing.isEmpty()) {
            long editionId = existing.getFirst();
            if (!replaceExisting) {
                Integer count = jdbc.queryForObject(
                        "select count(*) from segment where edition_id = ?",
                        Integer.class,
                        editionId
                );
                if (count != null && count > 0) {
                    throw new IllegalArgumentException("Generated edition already has content; enable replaceExisting to regenerate it");
                }
            }
            deleteEditionContent(workId, editionId);
            jdbc.update(
                    """
                    update edition
                    set publisher = coalesce(?, publisher),
                        year = coalesce(?, year),
                        source_uri = ?,
                        format = 'ai-literary'
                    where id = ?
                    """,
                    publisher,
                    year,
                    sourceUri,
                    editionId
            );
            return editionId;
        }

        return writer.ensureEdition(workId, lang, publisher, translator, year, sourceUri, "ai-literary");
    }

    private void deleteEditionContent(long workId, long editionId) {
        jdbc.update(
                """
                delete from alignment
                where work_id = ?
                  and (edition_src_id = ? or edition_tgt_id = ?)
                """,
                workId,
                editionId,
                editionId
        );
        jdbc.update(
                """
                delete from embedding
                where segment_id in (select id from segment where edition_id = ?)
                """,
                editionId
        );
        jdbc.update("delete from segment where edition_id = ?", editionId);
        jdbc.update("update edition set body_start_segment_id = null where id = ?", editionId);
    }

    private String generationContext(
            GenerateLiteraryEditionRequest request,
            SourceEdition source,
            SourceParagraph paragraph,
            int paragraphIndex,
            int paragraphTotal,
            int chunkIndex,
            int chunkTotal,
            String previousTranslatedParagraph
    ) {
        List<String> parts = new ArrayList<>();
        parts.add("Work: " + source.title());
        if (source.author() != null && !source.author().isBlank()) {
            parts.add("Author: " + source.author());
        }
        parts.add("Chapter: " + paragraph.chapterTitle());
        if (paragraph.speaker() != null && !paragraph.speaker().isBlank()) {
            parts.add("Speaker: " + paragraph.speaker());
        }
        parts.add("Paragraph: " + paragraphIndex + "/" + paragraphTotal);
        if (chunkTotal > 1) {
            parts.add("Paragraph chunk: " + chunkIndex + "/" + chunkTotal);
        }
        if (request.contextChars() > 0 && previousTranslatedParagraph != null && !previousTranslatedParagraph.isBlank()) {
            String previous = previousTranslatedParagraph.length() <= request.contextChars()
                    ? previousTranslatedParagraph
                    : previousTranslatedParagraph.substring(previousTranslatedParagraph.length() - request.contextChars());
            parts.add("Previous translated paragraph: " + previous);
        }
        return String.join(" | ", parts);
    }

    private List<String> chunkText(String text, int maxChars) {
        String cleaned = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (cleaned.isBlank()) {
            return List.of();
        }
        if (cleaned.length() <= maxChars) {
            return List.of(cleaned);
        }

        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentLength = 0;
        for (String sentence : cleaned.split("(?<=[.!?;:…])\\s+")) {
            if (sentence.length() > maxChars) {
                if (!current.isEmpty()) {
                    chunks.add(String.join(" ", current));
                    current.clear();
                    currentLength = 0;
                }
                chunks.addAll(chunkLongSentence(sentence, maxChars));
                continue;
            }

            int added = sentence.length() + (current.isEmpty() ? 0 : 1);
            if (!current.isEmpty() && currentLength + added > maxChars) {
                chunks.add(String.join(" ", current));
                current.clear();
                currentLength = 0;
            }
            current.add(sentence);
            currentLength += sentence.length() + (current.size() == 1 ? 0 : 1);
        }

        if (!current.isEmpty()) {
            chunks.add(String.join(" ", current));
        }
        return chunks;
    }

    private List<String> chunkLongSentence(String sentence, int maxChars) {
        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentLength = 0;
        for (String word : sentence.split("\\s+")) {
            int added = word.length() + (current.isEmpty() ? 0 : 1);
            if (!current.isEmpty() && currentLength + added > maxChars) {
                chunks.add(String.join(" ", current));
                current.clear();
                currentLength = 0;
            }
            current.add(word);
            currentLength += word.length() + (current.size() == 1 ? 0 : 1);
        }
        if (!current.isEmpty()) {
            chunks.add(String.join(" ", current));
        }
        return chunks;
    }

    private void publish(IngestionProgressSink sink, String step, String message, int chapters, int paragraphs, int sentences) {
        if (sink != null) {
            sink.update(step, message, chapters, paragraphs, sentences);
        }
    }

    private record SourceEdition(long workId, String title, String author, long editionId, Long bodyStartSegmentId) {
    }

    private record SourceParagraph(
            long chapterId,
            int chapterOrd,
            String chapterTitle,
            long paragraphId,
            int paragraphOrd,
            String text,
            String speaker
    ) {
    }
}
