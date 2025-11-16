package com.example.reader.reader;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

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

    public List<SentenceDto> fetchSentencePage(long editionId, int page, int size) {
        int offset = page * size;
        String sql = """
            select id, text
            from segment
            where edition_id = :ed
              and level = 'sentence'
            order by id
            offset :off limit :lim
            """;

        var params = new MapSqlParameterSource()
                .addValue("ed", editionId)
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

    public Long findEditionIdByWorkTitleAndLang(String workTitle, String lang) {
        var sql = """
            select e.id
            from edition e
            join work w on e.work_id = w.id
            where w.title = :title and e.lang = :lang
            limit 1
            """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("title", workTitle)
                        .addValue("lang", lang),
                rs -> rs.next() ? rs.getLong(1) : null
        );
    }

}
