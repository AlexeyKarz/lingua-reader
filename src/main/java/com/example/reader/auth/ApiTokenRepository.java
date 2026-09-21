package com.example.reader.auth;

import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class ApiTokenRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ApiTokenRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ApiToken create(long userId, String name, String tokenHash, List<String> scopes, Instant expiresAt) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into api_token(user_id, name, token_hash, scopes, expires_at)
                    values(?, ?, ?, ?, ?)
                    returning id
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, name);
            statement.setString(3, tokenHash);
            Array scopeArray = connection.createArrayOf("text", scopes.toArray(String[]::new));
            statement.setArray(4, scopeArray);
            if (expiresAt == null) {
                statement.setTimestamp(5, null);
            } else {
                statement.setTimestamp(5, Timestamp.from(expiresAt));
            }
            return statement;
        };
        jdbc.getJdbcTemplate().update(creator, key);
        Number id = (Number) Objects.requireNonNull(key.getKeys()).get("id");
        return findById(userId, id.longValue())
                .orElseThrow(() -> new IllegalStateException("API token was not created"));
    }

    public List<ApiToken> listForUser(long userId) {
        return jdbc.query("""
                select id, user_id, name, token_hash, scopes, created_at, expires_at, last_used_at, revoked_at
                from api_token
                where user_id = :userId
                order by created_at desc, id desc
                """,
                new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> map(rs));
    }

    public Optional<ApiToken> findValidByHash(String tokenHash) {
        return jdbc.query("""
                select id, user_id, name, token_hash, scopes, created_at, expires_at, last_used_at, revoked_at
                from api_token
                where token_hash = :tokenHash
                  and revoked_at is null
                  and (expires_at is null or expires_at > now())
                """,
                new MapSqlParameterSource("tokenHash", tokenHash),
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty());
    }

    public Optional<ApiToken> findById(long userId, long id) {
        return jdbc.query("""
                select id, user_id, name, token_hash, scopes, created_at, expires_at, last_used_at, revoked_at
                from api_token
                where user_id = :userId
                  and id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("id", id),
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty());
    }

    public void markUsed(long id) {
        jdbc.update("""
                update api_token
                set last_used_at = now()
                where id = :id
                """, new MapSqlParameterSource("id", id));
    }

    public void revoke(long userId, long id) {
        jdbc.update("""
                update api_token
                set revoked_at = coalesce(revoked_at, now())
                where user_id = :userId
                  and id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("id", id));
    }

    private ApiToken map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiToken(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("name"),
                rs.getString("token_hash"),
                scopes(rs.getArray("scopes")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("last_used_at")),
                instant(rs.getTimestamp("revoked_at"))
        );
    }

    private List<String> scopes(Array array) throws java.sql.SQLException {
        if (array == null) return List.of();
        return Arrays.asList((String[]) array.getArray());
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
