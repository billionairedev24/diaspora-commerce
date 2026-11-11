package com.afrotransact.orders.adapter.out.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.afrotransact.orders.domain.QuoteSettings;
import com.afrotransact.orders.domain.QuoteSettings.AutoAssign;
import com.afrotransact.orders.domain.QuoteSettings.Target;
import com.afrotransact.orders.port.DeliveryQuoteSettingsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** delivery_quote_settings (one row, id 1) and delivery_quote_settings_audit. */
@Repository
class JdbcDeliveryQuoteSettingsRepository implements DeliveryQuoteSettingsRepository {

    private static final String SELECT = """
            select sla_business_days, due_soon_percent, unassigned_after_hours, level2_after_hours, auto_assign,
                round_robin_cursor, level1_role, level1_user_ids, level2_role, level2_user_ids, min_amount_minor,
                max_amount_minor, validity_hours, requests_per_hour, duplicate_window_minutes, updated_by_name,
                updated_at
            from delivery_quote_settings where id = 1""";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcDeliveryQuoteSettingsRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public QuoteSettings load() {
        return jdbc.sql(SELECT).query(this::map).single();
    }

    @Override
    public QuoteSettings lock() {
        return jdbc.sql(SELECT + " for update").query(this::map).single();
    }

    @Override
    public void save(QuoteSettings s, String actorId, String actorName, String reason, QuoteSettings before,
                     Instant at) {
        jdbc.sql("""
                update delivery_quote_settings set sla_business_days = :sla, due_soon_percent = :soon,
                    unassigned_after_hours = :untaken, level2_after_hours = :l2, auto_assign = :auto,
                    level1_role = :r1, level1_user_ids = cast(:u1 as text[]), level2_role = :r2,
                    level2_user_ids = cast(:u2 as text[]), min_amount_minor = :min, max_amount_minor = :max,
                    validity_hours = :validity, requests_per_hour = :rate, duplicate_window_minutes = :dup,
                    updated_by_id = :actor, updated_by_name = :name, updated_at = :at
                where id = 1
                """)
                .param("sla", s.slaBusinessDays()).param("soon", s.dueSoonPercent())
                .param("untaken", s.unassignedAfterHours()).param("l2", s.level2AfterHours())
                .param("auto", s.autoAssign().name()).param("r1", s.level1().roleKey())
                .param("u1", s.level1().userIds().toArray(String[]::new)).param("r2", s.level2().roleKey())
                .param("u2", s.level2().userIds().toArray(String[]::new)).param("min", s.minAmountMinor())
                .param("max", s.maxAmountMinor()).param("validity", s.validityHours())
                .param("rate", s.requestsPerHour()).param("dup", s.duplicateWindowMinutes())
                .param("actor", actorId).param("name", actorName).param("at", Sql.ts(at)).update();
        jdbc.sql("""
                insert into delivery_quote_settings_audit (id, actor_id, actor_name, reason, before_json, after_json,
                    changed_at)
                values (:id, :actor, :name, :reason, cast(:before as jsonb), cast(:after as jsonb), :at)
                """)
                .param("id", UUID.randomUUID()).param("actor", actorId).param("name", actorName)
                .param("reason", reason).param("before", json.write(before)).param("after", json.write(s))
                .param("at", Sql.ts(at)).update();
    }

    @Override
    public void saveCursor(String cursor) {
        jdbc.sql("update delivery_quote_settings set round_robin_cursor = :c where id = 1").param("c", cursor)
                .update();
    }

    @Override
    public List<Change> changes(int limit) {
        return jdbc.sql("select actor_name, reason, changed_at from delivery_quote_settings_audit "
                + "order by changed_at desc limit :limit").param("limit", limit)
                .query((rs, n) -> new Change(rs.getString("actor_name"), rs.getString("reason"),
                        Sql.instant(rs, "changed_at"))).list();
    }

    private QuoteSettings map(ResultSet rs, int n) throws SQLException {
        return new QuoteSettings(rs.getInt("sla_business_days"), rs.getInt("due_soon_percent"),
                rs.getInt("unassigned_after_hours"), rs.getInt("level2_after_hours"),
                AutoAssign.valueOf(rs.getString("auto_assign")), rs.getString("round_robin_cursor"),
                new Target(rs.getString("level1_role"), array(rs, "level1_user_ids")),
                new Target(rs.getString("level2_role"), array(rs, "level2_user_ids")), rs.getLong("min_amount_minor"),
                rs.getLong("max_amount_minor"), rs.getInt("validity_hours"), rs.getInt("requests_per_hour"),
                rs.getInt("duplicate_window_minutes"), rs.getString("updated_by_name"), Sql.instant(rs, "updated_at"));
    }

    private static List<String> array(ResultSet rs, String column) throws SQLException {
        Array a = rs.getArray(column);
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }
}
