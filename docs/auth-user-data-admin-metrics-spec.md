# Authentication, User Data, and Admin Metrics Spec

Last updated: 2026-09-11

## Summary

Move Multilang Reader from browser-only user state and a static admin bearer token to database-backed users, roles, sessions, saved vocabulary, reader progress, preferences, and an admin-only dashboard with operational and product metrics.

The preferred browser authentication model is server-side session auth with an HttpOnly cookie. JavaScript should not store access tokens. CLI and automation should use separate scoped API tokens, not the browser login session.

## Original State Captured Before This Work

- Public catalog, reader, and translation APIs are open.
- Admin APIs under `/api/admin/**` are protected by a custom static bearer-token interceptor.
- `/admin/ingest` exists as a route but is not linked in the top navigation.
- Admin ingestion UI stores the admin token in `sessionStorage`.
- Vocabulary, review scheduling, last reader session, reader progress, and reader preferences are stored in browser `localStorage`.
- There are no user, role, session, preference, vocabulary, reader-progress, audit, or product-metrics tables.
- Micrometer/Prometheus metrics exist for server and provider-level monitoring, but there is no admin product dashboard.

## Goals

- Add proper login/logout with server-side session cookies.
- Add user roles, including `ADMIN`.
- Show an Admin navigation item only to authenticated users with the admin role.
- Restrict `/api/admin/**` to admin users.
- Move saved vocabulary and review state from local storage into the database.
- Move reader progress and relevant reader preferences into the database.
- Provide a safe migration/import path from existing local browser data.
- Add an admin metrics page covering usage, active users, translations, reader activity, vocabulary/review activity, ingestion, errors, and system/provider health.
- Keep CLI ingestion possible with scoped API tokens.

## Non-Goals

- OAuth/social login in the first implementation.
- Multi-tenant organization support.
- Public user profiles.
- Billing.
- Full analytics warehouse integration.
- Storing raw translation text in metrics events.

## Authentication Design

### Browser Auth

Use Spring Security with form-style JSON login endpoints and server-side sessions:

- Browser sends credentials to `POST /api/auth/login`.
- Server creates an authenticated session.
- Session cookie is `HttpOnly`, `Secure` in production, and `SameSite=Lax` or stricter.
- Frontend uses same-origin `fetch` calls; no bearer token is stored in JavaScript.
- `POST /api/auth/logout` invalidates the session.
- `GET /api/me` returns the current user and roles.

Recommended session storage:

- Use Spring Session JDBC so sessions survive app restarts and multiple app instances.
- Session cleanup should be scheduled or handled by Spring Session.

Password handling:

- Store BCrypt hashes only.
- Enforce a minimum password length for locally managed users.
- Add a startup/bootstrap mechanism for the first admin account.

Bootstrap environment:

- `MLR_BOOTSTRAP_ADMIN_EMAIL`
- `MLR_BOOTSTRAP_ADMIN_PASSWORD`
- Optional: `MLR_BOOTSTRAP_ADMIN_DISPLAY_NAME`

Bootstrap behavior:

- If no admin user exists and bootstrap env is present, create one with role `ADMIN`.
- If an admin already exists, do not overwrite the password automatically.
- Log that bootstrap was skipped or completed, but never log the password.

### CLI/API Token Auth

Keep human browser auth separate from automation auth.

Add scoped API tokens for ingestion scripts and other server-side automation:

- Tokens are generated once and shown once.
- Store only token hashes.
- Token has owner user, scopes, expiry, created time, last-used time, and optional revoked time.
- Initial scope: `admin:ingest`.

Automation may continue to use:

```http
Authorization: Bearer <api-token>
```

Spring Security should authenticate bearer API tokens separately and grant only the scopes/authorities they have.

### Authorization Rules

Initial route policy:

- Public:
  - `GET /api/catalog/**`
  - `GET /api/reader/**`
  - Public translation endpoints if the app should remain usable without login
  - Static frontend assets
- Authenticated user:
  - `GET /api/me`
  - `POST /api/auth/logout`
  - `/api/user/**`
- Admin:
  - `/api/admin/**`

Admin ingestion should no longer depend on `MLR_ADMIN_ENABLED` for browser access. Instead, it should depend on role-based auth. If a deployment-level kill switch is still desired, keep a separate property such as:

- `MLR_ADMIN_FEATURES_ENABLED=true`

When disabled, admin feature endpoints return `404` or `403` even for admins.

## CSRF

Because auth is cookie-based, unsafe requests need CSRF protection.

Recommended pattern:

- Backend issues a readable CSRF token cookie or exposes `GET /api/auth/csrf`.
- Frontend includes the token in `X-CSRF-TOKEN` on `POST`, `PUT`, `PATCH`, and `DELETE`.
- API-token requests are exempt from browser CSRF because they are not cookie-authenticated.

## Database Schema

Use the existing JDBC/schema-initializer style unless the project adopts a migration tool later.

### Users and Roles

`app_user`

- `id bigserial primary key`
- `email text not null unique`
- `display_name text`
- `password_hash text not null`
- `enabled boolean not null default true`
- `created_at timestamptz not null default now()`
- `updated_at timestamptz not null default now()`
- `last_login_at timestamptz`

`app_user_role`

- `user_id bigint not null references app_user(id) on delete cascade`
- `role text not null`
- primary key: `(user_id, role)`

Valid roles:

- `USER`
- `ADMIN`

### API Tokens

`api_token`

- `id bigserial primary key`
- `user_id bigint not null references app_user(id) on delete cascade`
- `name text not null`
- `token_hash text not null unique`
- `scopes text[] not null`
- `created_at timestamptz not null default now()`
- `expires_at timestamptz`
- `last_used_at timestamptz`
- `revoked_at timestamptz`

### Saved Vocabulary

`saved_vocabulary_item`

- `id uuid primary key`
- `user_id bigint not null references app_user(id) on delete cascade`
- `kind text not null` (`word`, `sentence`)
- `text text not null`
- `normalized_text text not null`
- `source_lang text not null`
- `target_lang text not null`
- `result text not null`
- `dictionary boolean not null default false`
- `work_id bigint references work(id) on delete set null`
- `book_id_text text`
- `title text`
- `author text`
- `page integer not null default 0`
- `sentence_id bigint`
- `review_status text not null`
- `review_due_at timestamptz not null`
- `review_interval_days integer not null default 0`
- `review_ease numeric not null default 2.5`
- `review_count integer not null default 0`
- `last_reviewed_at timestamptz`
- `created_at timestamptz not null default now()`
- `updated_at timestamptz not null default now()`

Unique dedupe index:

- `(user_id, kind, source_lang, target_lang, normalized_text)`

### Reader Progress

`reader_progress`

- `user_id bigint not null references app_user(id) on delete cascade`
- `work_id bigint not null references work(id) on delete cascade`
- `source_lang text not null`
- `target_lang text not null`
- `page integer not null`
- `page_size integer not null`
- `updated_at timestamptz not null default now()`
- primary key: `(user_id, work_id, source_lang, target_lang)`

### User Preferences

`user_preference`

- `user_id bigint not null references app_user(id) on delete cascade`
- `key text not null`
- `value_json jsonb not null`
- `updated_at timestamptz not null default now()`
- primary key: `(user_id, key)`

Initial preference keys:

- `reader.followAlignment`
- `reader.mobilePaneLayout`
- Optional later: `theme`

Theme may remain local-only in the first pass.

### Audit Events

`admin_audit_event`

- `id bigserial primary key`
- `actor_user_id bigint references app_user(id) on delete set null`
- `actor_email text`
- `action text not null`
- `entity_type text`
- `entity_id text`
- `ip_hash text`
- `user_agent text`
- `metadata_json jsonb not null default '{}'::jsonb`
- `created_at timestamptz not null default now()`

Record admin actions such as login, logout, ingestion submit, retry, generated translation submit, API token create/revoke, and user role changes.

## User Data APIs

### Current User

`GET /api/me`

Response:

```json
{
  "authenticated": true,
  "user": {
    "id": 1,
    "email": "admin@example.com",
    "displayName": "Admin",
    "roles": ["USER", "ADMIN"]
  }
}
```

Unauthenticated response:

```json
{
  "authenticated": false,
  "user": null
}
```

### Vocabulary

- `GET /api/user/vocabulary`
- `POST /api/user/vocabulary`
- `PATCH /api/user/vocabulary/{id}`
- `DELETE /api/user/vocabulary/{id}`
- `POST /api/user/vocabulary/{id}/review`
- `POST /api/user/vocabulary/import`

Import behavior:

- Accept the current localStorage shape.
- Normalize and dedupe on the same key as the DB unique index.
- Existing rows are updated only when the imported item is newer or has more review history.
- Response includes created, updated, skipped, and failed counts.

### Reader Progress and Preferences

- `GET /api/user/reader-progress`
- `PUT /api/user/reader-progress`
- `GET /api/user/preferences`
- `PUT /api/user/preferences/{key}`
- `POST /api/user/import-local-state`

The frontend should use DB state for logged-in users. For guests, localStorage can remain a fallback until the product requires login for all use.

## Frontend Design

### Auth Context

Add an `AuthProvider` near the React root:

- Fetches `/api/me` on app startup.
- Exposes `user`, `roles`, `authenticated`, `loading`, `login`, `logout`, `hasRole`.
- Handles `401` responses from authenticated endpoints.

### Navigation

Top nav should show:

- Catalog
- Reader
- Vocabulary
- Admin, only when `hasRole('ADMIN')`
- Login or user/logout control

Admin link target:

- `/admin/ingest` initially
- Later `/admin` can become a dashboard landing page with Ingestion as a tab.

### Route Protection

Frontend route guards are UX only:

- If unauthenticated user opens `/admin/ingest`, redirect to login.
- If authenticated non-admin opens `/admin/ingest`, show forbidden page.
- Server still enforces role access.

### Admin Ingestion UI

Remove:

- Admin token input.
- `sessionStorage` token persistence.
- Manual `Authorization: Bearer ...` headers for browser requests.

Add:

- Regular cookie-authenticated fetches.
- CSRF header for unsafe requests.

### Local Data Migration

On first authenticated session:

- Detect `mlr:vocabulary-items`, `mlr:last-reader-session`, and `mlr:reader-progress:*`.
- Show a dismissible import banner.
- Let the user import local data into the account.
- Store a local migration marker after successful import, for example `mlr:account-import-complete:<userId>`.
- Do not delete local data automatically.

## Admin Metrics Dashboard

Admin metrics should be available at:

- `/admin`
- `/admin/metrics`

The existing `/admin/ingest` route can remain and be linked from the admin dashboard.

### Metrics Goals

- Make it easy to see whether the app is being used.
- Show whether translations are consuming quota or failing.
- Show which books/language pairs are active.
- Show vocabulary/review engagement.
- Show ingestion status and failures.
- Show system/provider health without requiring Grafana for day-to-day checks.

### Metric Sources

Use two layers:

1. Micrometer/Prometheus for infrastructure and latency metrics.
2. Database product events and daily aggregates for the admin UI.

The admin UI should read mostly from the database, because product analytics need date ranges, user counts, and historical aggregation that are awkward to query from Prometheus alone.

### Product Event Table

`app_event`

- `id bigserial primary key`
- `event_type text not null`
- `user_id bigint references app_user(id) on delete set null`
- `anonymous_id text`
- `session_id text`
- `work_id bigint references work(id) on delete set null`
- `source_lang text`
- `target_lang text`
- `operation text`
- `provider text`
- `success boolean`
- `latency_ms integer`
- `status_code integer`
- `error_code text`
- `text_length integer`
- `cache_hit boolean`
- `metadata_json jsonb not null default '{}'::jsonb`
- `created_at timestamptz not null default now()`

Indexes:

- `(created_at desc)`
- `(event_type, created_at desc)`
- `(user_id, created_at desc)`
- `(work_id, created_at desc)`
- `(source_lang, target_lang, created_at desc)`

Privacy rules:

- Do not store raw selected text in `app_event`.
- Store text length and aggregate operation metadata.
- If anonymous tracking is needed, use a random anonymous id, not raw IP.
- If IP-derived abuse metrics are needed, store a rotating salted hash only.

### Daily Aggregates

`app_metric_daily`

- `day date not null`
- `metric text not null`
- `dimensions_json jsonb not null default '{}'::jsonb`
- `value numeric not null`
- primary key: `(day, metric, dimensions_json)`

Daily aggregate jobs should compute stable dashboard numbers from `app_event`:

- Daily active users.
- Translation calls by operation/provider/language pair.
- Reader page views by work/language pair.
- Vocabulary saves and reviews.
- Ingestion success/failure counts.
- Error counts.

Retention:

- Raw `app_event`: 90 days by default.
- Daily aggregates: 2 years by default.
- Admin audit events: 1 year or longer if useful.

### Events To Record

Usage:

- `session_started`
- `login_succeeded`
- `login_failed`
- `logout`
- `catalog_viewed`

Reader:

- `reader_page_viewed`
- `reader_pair_opened`
- `reader_sentence_selected`
- `reader_alignment_followed`

Translation:

- `translation_requested`
- `translation_succeeded`
- `translation_failed`
- Fields: `operation`, `provider`, `source_lang`, `target_lang`, `text_length`, `cache_hit`, `latency_ms`.

Vocabulary:

- `vocabulary_saved`
- `vocabulary_reviewed`
- `vocabulary_deleted`
- `vocabulary_imported`

Ingestion:

- `ingestion_job_submitted`
- `ingestion_job_succeeded`
- `ingestion_job_failed`
- `ingestion_job_retried`
- `generated_literary_job_submitted`
- `generated_literary_job_succeeded`
- `generated_literary_job_failed`

Admin:

- `admin_page_viewed`
- `api_token_created`
- `api_token_revoked`
- `user_role_changed`

### Admin Metrics API

All endpoints require `ADMIN`.

Overview:

- `GET /api/admin/metrics/overview?from=YYYY-MM-DD&to=YYYY-MM-DD`

Returns:

- registered users
- daily active users
- weekly active users
- translation calls today
- translation failures today
- cache hit rate
- ingestion jobs running/failed
- p95 translation latency
- top active language pair

Usage:

- `GET /api/admin/metrics/usage/daily?from=&to=`
- `GET /api/admin/metrics/users?from=&to=`

Translations:

- `GET /api/admin/metrics/translations/daily?from=&to=`
- `GET /api/admin/metrics/translations/providers?from=&to=`
- `GET /api/admin/metrics/translations/language-pairs?from=&to=`

Reader/books:

- `GET /api/admin/metrics/books?from=&to=`
- `GET /api/admin/metrics/reader/language-pairs?from=&to=`

Vocabulary:

- `GET /api/admin/metrics/vocabulary?from=&to=`
- `GET /api/admin/metrics/reviews?from=&to=`

Ingestion:

- Existing ingestion history endpoints remain.
- Add `GET /api/admin/metrics/ingestion?from=&to=`.

Errors:

- `GET /api/admin/metrics/errors?from=&to=`

### Admin Dashboard UI

First version layout:

1. Overview cards
   - Active users today
   - Active users last 7 days
   - Translation calls today
   - Translation failure rate
   - Cache hit rate
   - Running/failed ingestion jobs

2. Usage trends
   - Daily active users chart
   - Reader page views chart
   - Vocabulary saves/reviews chart

3. Translation health
   - Calls by operation: word, sentence, literary, explain
   - Calls by provider
   - Top language pairs
   - p50/p95 latency
   - Error count and latest error classes

4. Content activity
   - Top books opened
   - Top language pairs read
   - Saved vocabulary by language pair

5. Ingestion
   - Recent jobs
   - Failed jobs
   - Average duration
   - Button/link to `/admin/ingest`

6. System health
   - App uptime if available
   - DB connectivity status
   - Provider availability summary
   - Link to Grafana/Prometheus if configured

### Definitions

Active user:

- A logged-in user with at least one meaningful event during the period.
- Meaningful events include reader page views, translation requests, vocabulary saves/reviews, ingestion actions, and admin page views.

Anonymous usage:

- If public reading remains available, anonymous usage can be counted separately with a random anonymous id stored locally.
- Do not merge anonymous id into user identity unless the user explicitly imports local data.

Translation call:

- A request reaching the translation service layer.
- Track cache hits separately.
- Track provider calls separately so cached calls do not inflate provider usage.

Cache hit rate:

- `translation cache hits / translation requests`

Provider failure rate:

- `provider failures / provider calls`

## Security Requirements

- Never store passwords in plaintext.
- Never store session tokens or admin bearer tokens in browser storage.
- Admin role must be enforced on the backend.
- CSRF protection must apply to cookie-authenticated unsafe methods.
- Product metrics must not store raw selected text.
- Admin audit events should record sensitive administrative actions.
- API tokens must be revocable and stored hashed.
- Login failure responses must not reveal whether an email exists.
- Add rate limiting for login attempts.

## Testing Requirements

Backend:

- User bootstrap creates first admin only when appropriate.
- Password login succeeds/fails correctly.
- `/api/me` returns expected guest/user/admin shapes.
- Non-admin cannot access `/api/admin/**`.
- Admin can access `/api/admin/**`.
- API token with `admin:ingest` can call ingestion endpoints.
- CSRF rejects unsafe cookie-authenticated requests without token.
- Vocabulary CRUD is scoped to current user.
- Reader progress is scoped to current user.
- Metrics endpoints require admin.
- Product events do not persist raw selected text.

Frontend:

- Guest nav does not show Admin.
- Admin nav shows Admin.
- Non-admin route guard blocks admin page.
- Login/logout flows work.
- Admin ingestion no longer asks for admin token.
- Vocabulary page uses API data for authenticated users.
- Reader save writes vocabulary through API when authenticated.
- Local import banner appears when old local data exists.
- Metrics dashboard renders empty/loading/error states.

E2E:

- Admin logs in, sees Admin nav, opens ingestion.
- Non-admin logs in and cannot open admin.
- User saves a lookup, logs out/in, saved vocabulary persists.
- Admin metrics overview loads.

## Migration Plan

1. Add Spring Security, users, roles, sessions, bootstrap admin, `/api/me`.
2. Replace custom admin interceptor with Spring Security authorization.
3. Add login/logout UI and auth context.
4. Add admin-only nav link and route guard.
5. Remove admin token entry from admin ingestion UI.
6. Add user vocabulary and reader progress tables/endpoints.
7. Switch Vocabulary and Reader save/progress flows to API for authenticated users.
8. Add localStorage import flow.
9. Add product event capture service and metrics tables.
10. Add admin metrics APIs.
11. Add `/admin` metrics dashboard and link to `/admin/ingest`.
12. Add scoped API tokens for CLI ingestion and update scripts/docs.

## Open Decisions

- Should public reading remain available to guests, or should all reader/translation usage require login?
- Should public translation endpoints stay open with rate limits, or become authenticated-only?
- Should theme become a database preference or remain local?
- Should admin metrics include anonymous guest activity?
- What retention period is acceptable for audit events?
- Should first admin bootstrap be environment-only, or should there be a one-time setup screen?
