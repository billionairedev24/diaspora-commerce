package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.DeliveryFeeChange;
import com.afrotransact.deliveryruns.domain.DeliveryFeeRule;
import com.afrotransact.deliveryruns.domain.FeeBand;
import com.afrotransact.deliveryruns.domain.FeeMethod;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.DeliveryFeeRuleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDeliveryFeeRuleRepository implements DeliveryFeeRuleRepository {

    /** The numbers of a rule, as stored in {@code config} (and, with the method and currency, in the audit rows). */
    private record Config(List<FeeBand> bands, Long feeMinor, Long handlingMinor, Long fallbackMinor,
                          Long freeAboveMinor) {

        static Config of(DeliveryFeeRule r) {
            return new Config(r.bands(), r.feeMinor(), r.handlingMinor(), r.fallbackMinor(), r.freeAboveMinor());
        }
    }

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcDeliveryFeeRuleRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<DeliveryFeeRule> findAll() {
        return jdbc.sql("select * from delivery_fee_rule order by method, currency").query(this::rule).list();
    }

    @Override
    public Optional<DeliveryFeeRule> find(FeeMethod method, String currency) {
        return jdbc.sql("select * from delivery_fee_rule where method = :m and currency = :c")
                .param("m", method.name()).param("c", currency).query(this::rule).optional();
    }

    @Override
    public Optional<DeliveryFeeRule> lock(FeeMethod method, String currency) {
        return jdbc.sql("select * from delivery_fee_rule where method = :m and currency = :c for update")
                .param("m", method.name()).param("c", currency).query(this::rule).optional();
    }

    @Override
    public void insert(DeliveryFeeRule r) {
        jdbc.sql("""
                insert into delivery_fee_rule (id, method, currency, config, version, updated_at, updated_by, reason)
                values (:id, :method, :currency, cast(:config as jsonb), :version, :at, :by, :reason)
                """).param("id", r.id()).param("method", r.method().name()).param("currency", r.currency())
                .param("config", json.write(Config.of(r))).param("version", r.version())
                .param("at", Sql.ts(r.updatedAt())).param("by", r.updatedBy()).param("reason", r.reason()).update();
    }

    @Override
    public void update(DeliveryFeeRule r) {
        int rows = jdbc.sql("""
                update delivery_fee_rule set config = cast(:config as jsonb), version = :version, updated_at = :at,
                    updated_by = :by, reason = :reason
                where id = :id and version = :previous
                """).param("id", r.id()).param("config", json.write(Config.of(r))).param("version", r.version())
                .param("previous", r.version() - 1).param("at", Sql.ts(r.updatedAt())).param("by", r.updatedBy())
                .param("reason", r.reason()).update();
        if (rows == 0) {
            throw new ConcurrentUpdateException("The " + r.method() + " fee rule changed; reload and retry");
        }
    }

    @Override
    public void log(DeliveryFeeChange c) {
        jdbc.sql("""
                insert into delivery_fee_rule_change (id, rule_id, method, currency, version, before, after, actor,
                                                      reason, changed_at)
                values (:id, :rule, :method, :currency, :version, cast(:before as jsonb), cast(:after as jsonb),
                        :actor, :reason, :at)
                """).param("id", c.id()).param("rule", c.after().id()).param("method", c.method().name())
                .param("currency", c.currency()).param("version", c.version())
                .param("before", c.before() == null ? null : json.write(Config.of(c.before())))
                .param("after", json.write(Config.of(c.after()))).param("actor", c.actor())
                .param("reason", c.reason()).param("at", Sql.ts(c.at())).update();
    }

    @Override
    public List<DeliveryFeeChange> changes(FeeMethod method, String currency, int limit) {
        return jdbc.sql("""
                select * from delivery_fee_rule_change
                where (cast(:method as varchar) is null or method = cast(:method as varchar))
                  and (cast(:currency as varchar) is null or currency = cast(:currency as varchar))
                order by changed_at desc, version desc limit :limit
                """).param("method", method == null ? null : method.name()).param("currency", currency)
                .param("limit", limit).query(this::change).list();
    }

    private DeliveryFeeRule rule(ResultSet rs, int n) throws SQLException {
        return build(rs.getObject("id", UUID.class), FeeMethod.valueOf(rs.getString("method")),
                rs.getString("currency"), json.read(rs.getString("config"), Config.class), rs.getLong("version"),
                rs, "updated_at", "updated_by", "reason");
    }

    private DeliveryFeeChange change(ResultSet rs, int n) throws SQLException {
        UUID ruleId = rs.getObject("rule_id", UUID.class);
        FeeMethod method = FeeMethod.valueOf(rs.getString("method"));
        String currency = rs.getString("currency");
        long version = rs.getLong("version");
        String actor = rs.getString("actor");
        String reason = rs.getString("reason");
        DeliveryFeeRule after = build(ruleId, method, currency, json.read(rs.getString("after"), Config.class),
                version, rs, "changed_at", "actor", "reason");
        Config before = json.read(rs.getString("before"), Config.class);
        return new DeliveryFeeChange(rs.getObject("id", UUID.class), method, currency, version,
                before == null ? null : build(ruleId, method, currency, before, version - 1, rs, "changed_at", null,
                        null), after, actor, reason, Sql.instant(rs, "changed_at"));
    }

    private static DeliveryFeeRule build(UUID id, FeeMethod method, String currency, Config c, long version,
                                         ResultSet rs, String atColumn, String byColumn, String reasonColumn)
            throws SQLException {
        return new DeliveryFeeRule(id, method, currency, c.bands(), orZero(c.feeMinor()), orZero(c.handlingMinor()),
                orZero(c.fallbackMinor()), c.freeAboveMinor(), version, Sql.instant(rs, atColumn),
                byColumn == null ? null : rs.getString(byColumn),
                reasonColumn == null ? null : rs.getString(reasonColumn));
    }

    private static long orZero(Long v) {
        return v == null ? 0 : v;
    }
}
