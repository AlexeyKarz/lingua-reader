package com.example.reader.catalog;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@DependsOn("databaseSchemaInitializer")
public class CatalogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void ensureDescriptionColumn() {
        jdbc.getJdbcTemplate().execute("alter table work add column if not exists description text");
    }

    public List<CatalogEntry> listBooks() {
        String sql = """
            with latest_align as (
                select a.work_id, a.edition_src_id, a.edition_tgt_id
                from alignment a
                join (
                    select work_id, max(created_at) as max_created
                    from alignment
                    group by work_id
                ) ma on ma.work_id = a.work_id and ma.max_created = a.created_at
            )
            select
                w.id as work_id,
                w.title,
                w.author,
                w.description,
                w.pub_year,
                w.original_lang,
                (
                  select array_agg(lo.lang order by lo.ord_key, lo.lang)
                  from (
                    select
                      e2.lang as lang,
                      min(
                        case
                          when la2.edition_src_id = e2.id then 0
                          when la2.edition_tgt_id = e2.id then 1
                          when w.original_lang = e2.lang then 0
                          else 2
                        end
                      ) as ord_key
                    from edition e2
                    left join latest_align la2 on la2.work_id = w.id
                    where e2.work_id = w.id
                    group by e2.lang
                  ) lo
                ) as langs
            from work w
            group by w.id, w.title, w.author, w.description, w.pub_year, w.original_lang
            order by w.title
            """;

        return jdbc.getJdbcTemplate().query(sql, (rs, rowNum) -> {
            long workId = rs.getLong("work_id");
            String title = rs.getString("title");
            String author = rs.getString("author");
            String description = rs.getString("description");
            Integer year = (Integer) rs.getObject("pub_year");

            Array arr = rs.getArray("langs");
            String[] langs = arr != null ? (String[]) arr.getArray() : new String[0];

            return new CatalogEntry(
                    workId,
                    title,
                    author,
                    description,
                    year,
                    Arrays.asList(langs),
                    rs.getString("original_lang"),
                    editionMeta(workId)
            );
        });
    }

    private Map<String, CatalogEntry.EditionMeta> editionMeta(long workId) {
        return jdbc.query(
                """
                select distinct on (lang)
                       lang,
                       translator,
                       format,
                       source_uri
                from edition
                where work_id = :workId
                order by lang, id desc
                """,
                new MapSqlParameterSource("workId", workId),
                rs -> {
                    Map<String, CatalogEntry.EditionMeta> meta = new LinkedHashMap<>();
                    while (rs.next()) {
                        String lang = rs.getString("lang");
                        String format = rs.getString("format");
                        String sourceUri = rs.getString("source_uri");
                        meta.put(lang, new CatalogEntry.EditionMeta(
                                rs.getString("translator"),
                                format,
                                isAiGenerated(format, sourceUri)
                        ));
                    }
                    return meta;
                }
        );
    }

    private boolean isAiGenerated(String format, String sourceUri) {
        return (format != null && format.equalsIgnoreCase("ai-literary"))
                || (sourceUri != null && sourceUri.startsWith("ai-literary://"));
    }
}
