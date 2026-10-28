package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.MembershipSettings;
import com.afrotransact.payments.port.MembershipSettingsRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcMembershipSettingsRepository implements MembershipSettingsRepository {

    private static final TypeReference<List<Integer>> INTS = new TypeReference<>() { };
    private static final String SELECT = """
            select refund_window_days, retry_days::text as retry_days, updated_by, updated_at, version
            from membership_settings where id = 1
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcMembershipSettingsRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public MembershipSettings get() {
        return jdbc.sql(SELECT).query(this::map).single();
    }

    @Override
    public MembershipSettings lock() {
        return jdbc.sql(SELECT + " for update").query(this::map).single();
    }

    @Override
    public MembershipSettings save(MembershipSettings next, UUID changeId, String reason, MembershipSettings before) {
        int updated = jdbc.sql("""
                update membership_settings set refund_window_days = :window, retry_days = cast(:retries as jsonb),
                    updated_by = :by, updated_at = :at, version = version + 1
                where id = 1 and version = :version
                """)
                .param("window", next.refundWindowDays()).param("retries", json.write(next.retryDays()))
                .param("by", next.updatedBy()).param("at", Sql.ts(next.updatedAt())).param("version", before.version())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Membership settings were changed concurrently");
        }
        jdbc.sql("""
                insert into membership_settings_change (id, changed_by, changed_at, reason, before, after)
                values (:id, :by, :at, :reason, cast(:before as jsonb), cast(:after as jsonb))
                """)
                .param("id", changeId).param("by", next.updatedBy()).param("at", Sql.ts(next.updatedAt()))
                .param("reason", reason).param("before", json.write(before)).param("after", json.write(next))
                .update();
        return get();
    }

    @Override
    public List<Change> changes(int limit) {
        return jdbc.sql("""
                select id, changed_by, changed_at, reason, before::text as before_json, after::text as after_json
                from membership_settings_change order by changed_at desc limit :n
                """)
                .param("n", limit)
                .query((rs, n) -> new Change(rs.getObject("id", UUID.class), rs.getString("changed_by"),
                        Sql.instant(rs, "changed_at"), rs.getString("reason"), rs.getString("before_json"),
                        rs.getString("after_json")))
                .list();
    }

    private MembershipSettings map(ResultSet rs, int n) throws SQLException {
        return new MembershipSettings(rs.getInt("refund_window_days"), json.read(rs.getString("retry_days"), INTS),
                rs.getString("updated_by"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
