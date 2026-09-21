package com.example.reader.auth;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Repository
public class UserRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean adminExists() {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from app_user u
                join app_user_role r on r.user_id = u.id
                where r.role = 'ADMIN'
                  and u.enabled = true
                """, new MapSqlParameterSource(), Integer.class);
        return count != null && count > 0;
    }

    public Optional<AppUser> findByEmail(String email) {
        String normalizedEmail = normalizeEmail(email);
        List<AppUser> users = jdbc.query("""
                select id, email, display_name, password_hash, enabled,
                       created_at, updated_at, last_login_at
                from app_user
                where email = :email
                """,
                new MapSqlParameterSource("email", normalizedEmail),
                (rs, rowNum) -> new AppUser(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("password_hash"),
                        rs.getBoolean("enabled"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("updated_at")),
                        toInstant(rs.getTimestamp("last_login_at")),
                        List.of()
                ));
        if (users.isEmpty()) {
            return Optional.empty();
        }
        AppUser user = users.getFirst();
        return Optional.of(withRoles(user, rolesForUser(user.id())));
    }

    public long createUser(String email, String displayName, String passwordHash, boolean enabled) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
                insert into app_user(email, display_name, password_hash, enabled)
                values(:email, :displayName, :passwordHash, :enabled)
                returning id
                """,
                new MapSqlParameterSource()
                        .addValue("email", normalizeEmail(email))
                        .addValue("displayName", trimToNull(displayName))
                        .addValue("passwordHash", passwordHash)
                        .addValue("enabled", enabled),
                key);
        Number id = (Number) Objects.requireNonNull(key.getKeys()).get("id");
        return id.longValue();
    }

    public void addRole(long userId, String role) {
        jdbc.update("""
                insert into app_user_role(user_id, role)
                values(:userId, :role)
                on conflict do nothing
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("role", normalizeRole(role)));
    }

    public void recordLogin(String email) {
        jdbc.update("""
                update app_user
                set last_login_at = now(),
                    updated_at = now()
                where email = :email
                """, new MapSqlParameterSource("email", normalizeEmail(email)));
    }

    private List<String> rolesForUser(long userId) {
        return jdbc.query("""
                select role
                from app_user_role
                where user_id = :userId
                order by role
                """,
                new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> rs.getString("role"));
    }

    private AppUser withRoles(AppUser user, List<String> roles) {
        return new AppUser(
                user.id(),
                user.email(),
                user.displayName(),
                user.passwordHash(),
                user.enabled(),
                user.createdAt(),
                user.updatedAt(),
                user.lastLoginAt(),
                roles
        );
    }

    static String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeRole(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
