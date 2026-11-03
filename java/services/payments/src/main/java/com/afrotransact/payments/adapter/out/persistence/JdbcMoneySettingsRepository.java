package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.MoneySettings;
import com.afrotransact.payments.port.MoneySettingsRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcMoneySettingsRepository implements MoneySettingsRepository {

    private static final TypeReference<List<Long>> LONGS = new TypeReference<>() { };
    private static final String SELECT = """
            select gift_card_min_minor, gift_card_max_minor, gift_card_presets::text as presets,
                   gift_card_terms::text as terms, credit_approval_limit_minor, updated_by, updated_at, version
            from money_settings where id = 1
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcMoneySettingsRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public MoneySettings get() {
        return jdbc.sql(SELECT).query(this::map).single();
    }

    @Override
    public MoneySettings lock() {
        return jdbc.sql(SELECT + " for update").query(this::map).single();
    }

    @Override
    public MoneySettings save(MoneySettings next, UUID changeId, String reason, MoneySettings before) {
        int updated = jdbc.sql("""
                update money_settings set gift_card_min_minor = :min, gift_card_max_minor = :max,
                    gift_card_presets = cast(:presets as jsonb), gift_card_terms = cast(:terms as jsonb),
                    credit_approval_limit_minor = :limit, updated_by = :by, updated_at = :at, version = version + 1
                where id = 1 and version = :version
                """)
                .param("min", next.giftCardMinMinor()).param("max", next.giftCardMaxMinor())
                .param("presets", json.write(next.giftCardPresets())).param("terms", json.write(next.giftCardTerms()))
                .param("limit", next.creditApprovalLimitMinor()).param("by", next.updatedBy())
                .param("at", Sql.ts(next.updatedAt())).param("version", before.version()).update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Money settings were changed concurrently");
        }
        jdbc.sql("""
                insert into money_settings_change (id, changed_by, changed_at, reason, before, after)
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
                from money_settings_change order by changed_at desc limit :n
                """)
                .param("n", limit)
                .query((rs, n) -> new Change(rs.getObject("id", UUID.class), rs.getString("changed_by"),
                        Sql.instant(rs, "changed_at"), rs.getString("reason"), rs.getString("before_json"),
                        rs.getString("after_json")))
                .list();
    }

    private MoneySettings map(ResultSet rs, int n) throws SQLException {
        return new MoneySettings(rs.getLong("gift_card_min_minor"), rs.getLong("gift_card_max_minor"),
                json.read(rs.getString("presets"), LONGS), json.stringMap(rs.getString("terms")),
                rs.getLong("credit_approval_limit_minor"), rs.getString("updated_by"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
