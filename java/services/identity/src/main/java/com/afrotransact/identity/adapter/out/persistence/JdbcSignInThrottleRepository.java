package com.afrotransact.identity.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import com.afrotransact.identity.domain.ThrottleScope;
import com.afrotransact.identity.domain.ThrottleState;
import com.afrotransact.identity.port.SignInThrottleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSignInThrottleRepository implements SignInThrottleRepository {

    private final JdbcClient jdbc;

    JdbcSignInThrottleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ThrottleState find(ThrottleScope scope, String key, Instant now) {
        return jdbc.sql("select * from sign_in_throttle where scope = :s and key = :k")
                .param("s", scope.name()).param("k", key).query(JdbcSignInThrottleRepository::map).optional()
                .orElseGet(() -> ThrottleState.fresh(scope, key, now));
    }

    @Override
    public ThrottleState lock(ThrottleScope scope, String key, Instant now) {
        jdbc.sql("""
                insert into sign_in_throttle (scope, key, failures, window_started_at, lockouts, updated_at)
                values (:s, :k, 0, :now, 0, :now) on conflict (scope, key) do nothing
                """).param("s", scope.name()).param("k", key).param("now", Jdbc.ts(now)).update();
        return jdbc.sql("select * from sign_in_throttle where scope = :s and key = :k for update")
                .param("s", scope.name()).param("k", key).query(JdbcSignInThrottleRepository::map).single();
    }

    @Override
    public void save(ThrottleState s, Instant now) {
        jdbc.sql("""
                update sign_in_throttle set failures = :f, window_started_at = :w, locked_until = :l,
                    lockouts = :n, updated_at = :now
                where scope = :s and key = :k
                """)
                .param("f", s.failures()).param("w", Jdbc.ts(s.windowStartedAt()))
                .param("l", Jdbc.ts(s.lockedUntil())).param("n", s.lockouts()).param("now", Jdbc.ts(now))
                .param("s", s.scope().name()).param("k", s.key())
                .update();
    }

    private static ThrottleState map(ResultSet rs, int rowNum) throws SQLException {
        return new ThrottleState(ThrottleScope.valueOf(rs.getString("scope")), rs.getString("key"),
                rs.getInt("failures"), Jdbc.instant(rs, "window_started_at"), Jdbc.instant(rs, "locked_until"),
                rs.getInt("lockouts"));
    }
}
