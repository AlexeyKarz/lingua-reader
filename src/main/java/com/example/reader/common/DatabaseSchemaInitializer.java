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
                  speaker text,
                  unique(edition_id, level, ord, parent_id)
                )
                """);

        jdbc.execute("alter table segment add column if not exists speaker text");

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

        jdbc.execute("""
                create table if not exists dictionary_entry (
                  id bigserial primary key,
                  entry_key text not null unique,
                  source_lang text not null,
                  target_lang text not null,
                  surface text not null,
                  normalized text not null,
                  lemma text,
                  part_of_speech text,
                  gender text,
                  base_surface text,
                  base_normalized text,
                  form_tags_json jsonb not null default '[]'::jsonb,
                  definitions_json jsonb not null default '[]'::jsonb,
                  examples_json jsonb not null default '[]'::jsonb,
                  translations_json jsonb not null default '[]'::jsonb,
                  source_name text,
                  source_url text,
                  created_at timestamp default now(),
                  last_accessed_at timestamp default now(),
                  hit_count int not null default 0
                )
                """);

        jdbc.execute("alter table dictionary_entry add column if not exists entry_key text");
        jdbc.execute("alter table dictionary_entry add column if not exists gender text");
        jdbc.execute("alter table dictionary_entry add column if not exists base_surface text");
        jdbc.execute("alter table dictionary_entry add column if not exists base_normalized text");
        jdbc.execute("""
                alter table dictionary_entry
                add column if not exists form_tags_json jsonb not null default '[]'::jsonb
                """);
        jdbc.execute("""
                update dictionary_entry
                set entry_key = source_lang || '|' || target_lang || '|' || normalized || '|' ||
                                coalesce(part_of_speech, '') || '|' || coalesce(lemma, '') || '|' || id
                where entry_key is null
                """);
        jdbc.execute("alter table dictionary_entry alter column entry_key set not null");
        jdbc.execute("""
                do $$ begin
                  alter table dictionary_entry drop constraint dictionary_entry_source_lang_target_lang_normalized_key;
                exception when undefined_object then null; end $$
                """);
        jdbc.execute("""
                create unique index if not exists dictionary_entry_entry_key_idx
                on dictionary_entry (entry_key)
                """);

        jdbc.execute("""
                create index if not exists dictionary_entry_lookup_idx
                on dictionary_entry (source_lang, normalized)
                """);

        jdbc.execute("""
                create table if not exists translation_cache (
                  id bigserial primary key,
                  cache_key text not null unique,
                  operation text not null,
                  source_lang text not null,
                  target_lang text not null,
                  normalized_text text not null,
                  provider text not null,
                  response_text text not null,
                  created_at timestamp default now(),
                  last_accessed_at timestamp default now(),
                  hit_count int not null default 0
                )
                """);

        jdbc.execute("""
                create index if not exists translation_cache_lookup_idx
                on translation_cache (operation, source_lang, target_lang, normalized_text)
                """);

        jdbc.execute("""
                create table if not exists app_user (
                  id bigserial primary key,
                  email text not null unique,
                  display_name text,
                  password_hash text not null,
                  enabled boolean not null default true,
                  created_at timestamptz not null default now(),
                  updated_at timestamptz not null default now(),
                  last_login_at timestamptz
                )
                """);

        jdbc.execute("""
                create table if not exists app_user_role (
                  user_id bigint not null references app_user(id) on delete cascade,
                  role text not null,
                  primary key(user_id, role)
                )
                """);

        jdbc.execute("""
                create index if not exists app_user_role_role_idx
                on app_user_role(role)
                """);

        jdbc.execute("""
                create table if not exists api_token (
                  id bigserial primary key,
                  user_id bigint not null references app_user(id) on delete cascade,
                  name text not null,
                  token_hash text not null unique,
                  scopes text[] not null,
                  created_at timestamptz not null default now(),
                  expires_at timestamptz,
                  last_used_at timestamptz,
                  revoked_at timestamptz
                )
                """);

        jdbc.execute("""
                create index if not exists api_token_user_created_idx
                on api_token(user_id, created_at desc)
                """);

        jdbc.execute("""
                create table if not exists spring_session (
                  primary_id char(36) not null,
                  session_id char(36) not null,
                  creation_time bigint not null,
                  last_access_time bigint not null,
                  max_inactive_interval integer not null,
                  expiry_time bigint not null,
                  principal_name varchar(100),
                  constraint spring_session_pk primary key(primary_id)
                )
                """);

        jdbc.execute("""
                create unique index if not exists spring_session_ix1
                on spring_session(session_id)
                """);
        jdbc.execute("""
                create index if not exists spring_session_ix2
                on spring_session(expiry_time)
                """);
        jdbc.execute("""
                create index if not exists spring_session_ix3
                on spring_session(principal_name)
                """);

        jdbc.execute("""
                create table if not exists spring_session_attributes (
                  session_primary_id char(36) not null,
                  attribute_name varchar(200) not null,
                  attribute_bytes bytea not null,
                  constraint spring_session_attributes_pk primary key(session_primary_id, attribute_name),
                  constraint spring_session_attributes_fk foreign key(session_primary_id)
                    references spring_session(primary_id) on delete cascade
                )
                """);

        jdbc.execute("""
                create table if not exists saved_vocabulary_item (
                  id uuid primary key,
                  user_id bigint not null references app_user(id) on delete cascade,
                  kind text not null,
                  text text not null,
                  normalized_text text not null,
                  source_lang text not null,
                  target_lang text not null,
                  result text not null,
                  dictionary boolean not null default false,
                  work_id bigint references work(id) on delete set null,
                  book_id_text text,
                  title text,
                  author text,
                  page integer not null default 0,
                  sentence_id bigint,
                  review_status text not null,
                  review_due_at timestamptz not null,
                  review_interval_days integer not null default 0,
                  review_ease numeric not null default 2.5,
                  review_count integer not null default 0,
                  last_reviewed_at timestamptz,
                  created_at timestamptz not null default now(),
                  updated_at timestamptz not null default now(),
                  constraint saved_vocabulary_item_dedupe
                    unique(user_id, kind, source_lang, target_lang, normalized_text)
                )
                """);

        jdbc.execute("""
                create index if not exists saved_vocabulary_user_due_idx
                on saved_vocabulary_item(user_id, review_due_at, updated_at desc)
                """);

        jdbc.execute("""
                create table if not exists reader_progress (
                  user_id bigint not null references app_user(id) on delete cascade,
                  work_id bigint references work(id) on delete cascade,
                  book_id_text text not null,
                  source_lang text not null,
                  target_lang text not null,
                  page integer not null,
                  page_size integer not null,
                  updated_at timestamptz not null default now(),
                  primary key(user_id, book_id_text, source_lang, target_lang)
                )
                """);

        jdbc.execute("""
                create index if not exists reader_progress_user_updated_idx
                on reader_progress(user_id, updated_at desc)
                """);

        jdbc.execute("""
                create table if not exists user_preference (
                  user_id bigint not null references app_user(id) on delete cascade,
                  key text not null,
                  value_json jsonb not null,
                  updated_at timestamptz not null default now(),
                  primary key(user_id, key)
                )
                """);

        jdbc.execute("""
                create table if not exists app_event (
                  id bigserial primary key,
                  event_type text not null,
                  user_id bigint references app_user(id) on delete set null,
                  anonymous_id text,
                  session_id text,
                  work_id bigint references work(id) on delete set null,
                  source_lang text,
                  target_lang text,
                  operation text,
                  provider text,
                  success boolean,
                  latency_ms integer,
                  status_code integer,
                  error_code text,
                  text_length integer,
                  cache_hit boolean,
                  metadata_json jsonb not null default '{}'::jsonb,
                  created_at timestamptz not null default now()
                )
                """);

        jdbc.execute("""
                create index if not exists app_event_created_at_idx
                on app_event(created_at desc)
                """);
        jdbc.execute("""
                create index if not exists app_event_type_created_idx
                on app_event(event_type, created_at desc)
                """);
        jdbc.execute("""
                create index if not exists app_event_user_created_idx
                on app_event(user_id, created_at desc)
                """);
        jdbc.execute("""
                create index if not exists app_event_work_created_idx
                on app_event(work_id, created_at desc)
                """);
        jdbc.execute("""
                create index if not exists app_event_lang_pair_created_idx
                on app_event(source_lang, target_lang, created_at desc)
                """);

        jdbc.execute("""
                create table if not exists admin_audit_event (
                  id bigserial primary key,
                  actor_user_id bigint references app_user(id) on delete set null,
                  actor_email text,
                  action text not null,
                  entity_type text,
                  entity_id text,
                  ip_hash text,
                  user_agent text,
                  metadata_json jsonb not null default '{}'::jsonb,
                  created_at timestamptz not null default now()
                )
                """);

        jdbc.execute("""
                create index if not exists admin_audit_event_created_idx
                on admin_audit_event(created_at desc)
                """);

        jdbc.execute("""
                create index if not exists admin_audit_event_actor_created_idx
                on admin_audit_event(actor_user_id, created_at desc)
                """);

        jdbc.execute("""
                create table if not exists app_metric_daily (
                  day date not null,
                  metric text not null,
                  dimensions_json jsonb not null default '{}'::jsonb,
                  value numeric not null,
                  primary key(day, metric, dimensions_json)
                )
                """);
    }
}
