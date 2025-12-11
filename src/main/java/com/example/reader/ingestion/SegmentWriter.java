package com.example.reader.ingestion;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Objects;

@Repository
public class SegmentWriter {
    private final NamedParameterJdbcTemplate jdbc;
    public SegmentWriter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long ensureWork(String title, String author, Integer year, String originalLang) {
        Long id = jdbc.getJdbcTemplate().query(
                "select id from work where title = ? limit 1",
                ps -> ps.setString(1, title),
                rs -> rs.next() ? rs.getLong(1) : null
        );
        if (id != null) return id;

        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();

        var params = new MapSqlParameterSource()
                .addValue("t", title)
                .addValue("a", author)
                .addValue("y", year)
                .addValue("ol", originalLang)
                .addValue("pd", true);

        jdbc.update("""
        insert into work(title, author, pub_year, original_lang, public_domain)
        values(:t,:a,:y,:ol,:pd)
        returning id
        """, params, key);

        // key.getKeys() is a Map<String,Object>, we only care about "id"
        Number idVal = (Number) Objects.requireNonNull(key.getKeys()).get("id");
        return idVal.longValue();
    }

    public long ensureEdition(long workId, String lang, String publisher,
                              String translator, Integer year,
                              String uri, String format) {

        Long id = jdbc.getJdbcTemplate().query(
                "select id from edition where work_id = ? and lang = ? and source_uri = ? limit 1",
                ps -> {
                    ps.setLong(1, workId);
                    ps.setString(2, lang);
                    ps.setString(3, uri);
                },
                rs -> rs.next() ? rs.getLong(1) : null
        );
        if (id != null) return id;

        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();

        var params = new MapSqlParameterSource()
                .addValue("w", workId)
                .addValue("l", lang)
                .addValue("p", publisher)
                .addValue("tr", translator)
                .addValue("y", year)
                .addValue("u", uri)
                .addValue("f", format);

        jdbc.update("""
        insert into edition(work_id,lang,publisher,translator,year,source_uri,format)
        values(:w,:l,:p,:tr,:y,:u,:f)
        returning id
        """, params, key);

        Number idVal = (Number) Objects.requireNonNull(key.getKeys()).get("id");
        return idVal.longValue();
    }


    public long insertSegment(long editionId, String level, int ord,
                              Long parentId, String text, String cfi) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();

        var params = new MapSqlParameterSource()
                .addValue("e", editionId)
                .addValue("lv", level)
                .addValue("o", ord)
                .addValue("pid", parentId)
                .addValue("tx", text)
                .addValue("cfi", cfi);

        jdbc.update("""
        insert into segment(edition_id, level, ord, parent_id, text, cfi)
        values(:e, CAST(:lv AS segment_level), :o, :pid, :tx, :cfi)
        returning id
        """, params, key);

        Number idVal = (Number) Objects.requireNonNull(key.getKeys()).get("id");
        return idVal.longValue();
    }

    public void insertEmbedding(long segmentId, String model, float[] vec) {
        var params = new MapSqlParameterSource()
                .addValue("id", segmentId)
                .addValue("m", model)
                .addValue("v", new com.pgvector.PGvector(vec));

        jdbc.update(
                "insert into embedding(segment_id, model, vec) values(:id, :m, :v)",
                params
        );
    }
}
