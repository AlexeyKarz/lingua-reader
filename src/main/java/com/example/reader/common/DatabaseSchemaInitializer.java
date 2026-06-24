package com.example.reader.common;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaInitializer {
    private final JdbcTemplate jdbc;

    public DatabaseSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("create extension if not exists vector");

        jdbc.execute("""
                create table if not exists work (
                  id bigserial primary key,
                  title text not null unique,
                  author text,
                  description text,
                  pub_year int,
                  original_lang text,
                  public_domain boolean default true
                )
                """);

        jdbc.execute("""
                create table if not exists edition (
                  id bigserial primary key,
                  work_id bigint not null references work(id) on delete cascade,
                  lang text not null,
                  publisher text,
                  translator text,
                  year integer,
                  source_uri text,
                  format text,
                  body_start_segment_id bigint,
                  unique(work_id, lang, translator)
                )
                """);

        jdbc.execute("""
                do $$ begin
                  create type segment_level as enum ('chapter','paragraph','sentence');
                exception when duplicate_object then null; end $$
                """);

        jdbc.execute("""
                create table if not exists segment (
                  id bigserial primary key,
                  edition_id bigint not null references edition(id) on delete cascade,
                  level segment_level not null,
                  ord int not null,
                  parent_id bigint references segment(id) on delete cascade,
                  text text not null,
                  cfi text,
                  unique(edition_id, level, ord, parent_id)
                )
                """);

        jdbc.execute("""
                create table if not exists embedding (
                  segment_id bigint primary key not null references segment(id) on delete cascade,
                  model text not null,
                  vec vector(384),
                  unique(segment_id, model)
                )
                """);

        jdbc.execute("""
                create index if not exists embedding_vec_hnsw_cosine
                on embedding using hnsw (vec vector_cosine_ops)
                """);

        jdbc.execute("""
                create table if not exists alignment (
                  id bigserial primary key,
                  work_id bigint references work(id) on delete cascade,
                  edition_src_id bigint references edition(id) on delete cascade,
                  edition_tgt_id bigint references edition(id) on delete cascade,
                  left_segment_id bigint not null references segment(id) on delete cascade,
                  right_segment_id bigint not null references segment(id) on delete cascade,
                  method text not null,
                  score double precision not null,
                  created_at timestamp default now(),
                  constraint alignment_left_right_unique
                    unique (left_segment_id, right_segment_id, method)
                )
                """);
    }
}
