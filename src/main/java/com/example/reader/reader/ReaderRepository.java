package com.example.reader.reader;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.sql.Types;

@Repository
public class ReaderRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReaderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Small holder for IDs
    public record EditionIds(long workId, long srcEditionId, long tgtEditionId) {}

    public EditionIds findEditionIds(String workTitle, String srcLang, String tgtLang) {
        String sql = """
            select w.id as work_id,
                   max(case when e.lang = :src then e.id end) as src_id,
                   max(case when e.lang = :tgt then e.id end) as tgt_id
            from work w
            join edition e on e.work_id = w.id
            where w.title = :title
            group by w.id
            """;

        var params = new MapSqlParameterSource()
                .addValue("title", workTitle)
                .addValue("src", srcLang)
                .addValue("tgt", tgtLang);

        return jdbc.query(sql, params, rs -> {
            if (!rs.next()) return null;
            long workId = rs.getLong("work_id");
            long srcId  = rs.getLong("src_id");
            long tgtId  = rs.getLong("tgt_id");
            return new EditionIds(workId, srcId, tgtId);
        });
    }

    public int countSentences(long editionId) {
        String sql = """
            select count(*)
            from segment
            where edition_id = :ed
              and level = 'sentence'
            """;
        Integer total = jdbc.queryForObject(
                sql,
                new MapSqlParameterSource().addValue("ed", editionId),
                Integer.class
        );
        return total != null ? total : 0;
    }

    public Long findBodyStartSegmentId(long editionId) {
        String sql = "select body_start_segment_id from edition where id = :ed";
        return jdbc.queryForObject(
                sql,
                new MapSqlParameterSource().addValue("ed", editionId),
                Long.class
        );
    }


    public List<SentenceDto> fetchSentencePage(long editionId, Long bodyStartId, int page, int size) {
        int offset = page * size;

        String sql = """
        select id, text
        from segment
        where edition_id = :ed
          and level = 'sentence'
          and (:bodyStart is null or id >= :bodyStart)
        order by id
        offset :off limit :lim
        """;

        var params = new MapSqlParameterSource()
                .addValue("ed", editionId)
                .addValue("bodyStart", bodyStartId, Types.BIGINT)
                .addValue("off", offset)
                .addValue("lim", size);

        return jdbc.query(sql, params, (rs, rowNum) ->
                new SentenceDto(rs.getLong("id"), rs.getString("text")));
    }

    public List<AlignmentLinkDto> fetchAlignmentsForPage(
            long workId,
            long srcEditionId,
            long tgtEditionId,
            String method,
            List<Long> leftIds,
            List<Long> rightIds
    ) {
        if (leftIds.isEmpty() || rightIds.isEmpty()) {
            return List.of();
        }

        String sql = """
            select a.left_segment_id, a.right_segment_id, a.score
            from alignment a
            where a.work_id = :wid
              and a.edition_src_id = :srcEd
              and a.edition_tgt_id = :tgtEd
              and a.method = :method
              and a.left_segment_id = any(:leftIds)
              and a.right_segment_id = any(:rightIds)
            """;

        var params = new MapSqlParameterSource()
                .addValue("wid", workId)
                .addValue("srcEd", srcEditionId)
                .addValue("tgtEd", tgtEditionId)
                .addValue("method", method)
                .addValue("leftIds", leftIds.toArray(Long[]::new))
                .addValue("rightIds", rightIds.toArray(Long[]::new));

        return jdbc.query(sql, params, (rs, rowNum) ->
                new AlignmentLinkDto(
                        rs.getLong("left_segment_id"),
                        rs.getLong("right_segment_id"),
                        rs.getDouble("score")
                )
        );
    }

    public List<AlignmentLinkDto> fetchAlignmentsForLeftIds(
            long workId,
            long srcEditionId,
            long tgtEditionId,
            String method,
            List<Long> leftIds
    ) {
        if (leftIds.isEmpty()) return List.of();

        String sql = """
        select a.left_segment_id, a.right_segment_id, a.score
        from alignment a
        where a.work_id = :wid
          and a.edition_src_id = :srcEd
          and a.edition_tgt_id = :tgtEd
          and a.method = :method
          and a.left_segment_id = any(:leftIds)
        """;

        var params = new MapSqlParameterSource()
                .addValue("wid", workId)
                .addValue("srcEd", srcEditionId)
                .addValue("tgtEd", tgtEditionId)
                .addValue("method", method)
                .addValue("leftIds", leftIds.toArray(Long[]::new));

        return jdbc.query(sql, params, (rs, rowNum) ->
                new AlignmentLinkDto(
                        rs.getLong("left_segment_id"),
                        rs.getLong("right_segment_id"),
                        rs.getDouble("score")
                )
        );
    }

    public List<AlignmentLinkDto> fetchAlignmentsForLeftIdsAnyMethod(
            long workId,
            long srcEditionId,
            long tgtEditionId,
            List<Long> leftIds
    ) {
        if (leftIds.isEmpty()) return List.of();

        String sql = """
        select a.left_segment_id, a.right_segment_id, a.score
        from alignment a
        where a.work_id = :wid
          and a.edition_src_id = :srcEd
          and a.edition_tgt_id = :tgtEd
          and a.left_segment_id = any(:leftIds)
        """;

        var params = new MapSqlParameterSource()
                .addValue("wid", workId)
                .addValue("srcEd", srcEditionId)
                .addValue("tgtEd", tgtEditionId)
                .addValue("leftIds", leftIds.toArray(Long[]::new));

        return jdbc.query(sql, params, (rs, rowNum) ->
                new AlignmentLinkDto(
                        rs.getLong("left_segment_id"),
                        rs.getLong("right_segment_id"),
                        rs.getDouble("score")
                )
        );
    }


    public List<SentenceDto> fetchSentencesByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();

        String sql = """
        select id, text
        from segment
        where id = any(:ids)
        order by id
        """;

        var params = new MapSqlParameterSource()
                .addValue("ids", ids.toArray(Long[]::new));

        return jdbc.query(sql, params, (rs, rowNum) ->
                new SentenceDto(rs.getLong("id"), rs.getString("text")));
    }

    public List<SentenceDto> fetchSentencesInRange(long editionId, long fromId, long toId) {
        String sql = """
        select id, text
        from segment
        where edition_id = :ed
          and level = 'sentence'
          and id between :fromId and :toId
        order by id
        """;

        var params = new MapSqlParameterSource()
                .addValue("ed", editionId)
                .addValue("fromId", fromId)
                .addValue("toId", toId);

        return jdbc.query(sql, params,
                (rs, rowNum) -> new SentenceDto(rs.getLong("id"), rs.getString("text")));
    }

    public List<SentenceDto> fetchFrontMatter(long editionId, Long bodyStartId) {
        // If bodyStartId is null, we don't know where body starts – return empty for now
        if (bodyStartId == null) {
            return List.of();
        }

        String sql = """
        select id, text
        from segment
        where edition_id = :ed
          and level = 'sentence'
          and id < :bodyStart
        order by id
        """;

        var params = new MapSqlParameterSource()
                .addValue("ed", editionId)
                .addValue("bodyStart", bodyStartId, Types.BIGINT);

        return jdbc.query(sql, params,
                (rs, rowNum) -> new SentenceDto(rs.getLong("id"), rs.getString("text")));
    }
}
