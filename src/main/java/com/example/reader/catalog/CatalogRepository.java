package com.example.reader.catalog;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.Arrays;
import java.util.List;

@Repository
public class CatalogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CatalogEntry> listBooks() {
        String sql = """
            select
                w.title as id,
                w.title,
                w.author,
                w.pub_year,
                array_agg(distinct e.lang order by e.lang) as langs
            from work w
            join edition e on e.work_id = w.id
            group by w.id, w.title, w.author, w.pub_year
            order by w.title
            """;

        return jdbc.getJdbcTemplate().query(sql, (rs, rowNum) -> {
            String id = rs.getString("id");          // using title as id for now
            String title = rs.getString("title");
            String author = rs.getString("author");
            Integer year = (Integer) rs.getObject("pub_year");

            Array arr = rs.getArray("langs");
            String[] langs = arr != null ? (String[]) arr.getArray() : new String[0];

            return new CatalogEntry(
                    id,
                    title,
                    author,
                    year,
                    Arrays.asList(langs)
            );
        });
    }
}
