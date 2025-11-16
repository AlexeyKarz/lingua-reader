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

    public int countSentences(long editionId) {
        var sql = """
            select count(*)
            from segment
            where edition_id = :eid and level = 'sentence'
            """;
        Integer c = jdbc.queryForObject(
                sql,
                new MapSqlParameterSource().addValue("eid", editionId),
                Integer.class
        );
        return c != null ? c : 0;
    }

    public List<String> fetchSentencePage(long editionId, int page, int size) {
        int offset = page * size;
        var sql = """
            select text
            from segment
            where edition_id = :eid and level = 'sentence'
            order by id
            offset :offset
            limit :limit
            """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("eid", editionId)
                        .addValue("offset", offset)
                        .addValue("limit", size),
                (rs, rowNum) -> rs.getString("text")
        );
    }
}
