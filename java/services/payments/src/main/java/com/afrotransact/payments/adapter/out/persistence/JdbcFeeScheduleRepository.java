package com.afrotransact.payments.adapter.out.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.CommissionOverride;
import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.FeeScheduleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcFeeScheduleRepository implements FeeScheduleRepository {

    private final JdbcClient jdbc;

    JdbcFeeScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(FeeSchedule s) {
        jdbc.sql("""
                insert into fee_schedule (id, version, effective_from, currency, default_commission_bps, card_pct_bps,
                    card_fixed_minor, fba_per_unit_minor, reverse_commission_on_refund, note, created_by, created_at)
                values (:id, :version, :from, :currency, :defaultBps, :cardBps, :cardFixed, :fba, :reverse, :note,
                    :by, :at)
                """)
                .param("id", s.id()).param("version", s.version()).param("from", Sql.ts(s.effectiveFrom()))
                .param("currency", s.currency()).param("defaultBps", s.defaultCommissionBps())
                .param("cardBps", s.cardPctBps()).param("cardFixed", s.cardFixed().minor())
                .param("fba", s.fbaPerUnit().minor()).param("reverse", s.reverseCommissionOnRefund())
                .param("note", s.note()).param("by", s.createdBy()).param("at", Sql.ts(s.createdAt()))
                .update();
        s.categoryBps().forEach((category, bps) -> jdbc.sql("""
                insert into fee_schedule_rate (schedule_id, category, commission_bps) values (:id, :c, :bps)
                """).param("id", s.id()).param("c", category).param("bps", bps).update());
        for (CommissionOverride o : s.overrides()) {
            jdbc.sql("""
                    insert into fee_schedule_override (schedule_id, org_id, category, commission_bps)
                    values (:id, :org, :c, :bps)
                    """).param("id", s.id()).param("org", o.orgId()).param("c", o.category())
                    .param("bps", o.commissionBps()).update();
        }
    }

    @Override
    public List<FeeSchedule> all() {
        Map<UUID, Map<String, Integer>> rates = new HashMap<>();
        jdbc.sql("select schedule_id, category, commission_bps from fee_schedule_rate").query(rs -> {
            rates.computeIfAbsent(rs.getObject("schedule_id", UUID.class), k -> new HashMap<>())
                    .put(rs.getString("category"), rs.getInt("commission_bps"));
        });
        Map<UUID, List<CommissionOverride>> overrides = new HashMap<>();
        jdbc.sql("select schedule_id, org_id, category, commission_bps from fee_schedule_override").query(rs -> {
            overrides.computeIfAbsent(rs.getObject("schedule_id", UUID.class), k -> new ArrayList<>())
                    .add(new CommissionOverride(rs.getString("org_id"), rs.getString("category"),
                            rs.getInt("commission_bps")));
        });
        return jdbc.sql("select * from fee_schedule order by version")
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String currency = rs.getString("currency");
                    return new FeeSchedule(id, rs.getInt("version"), Sql.instant(rs, "effective_from"), currency,
                            rates.getOrDefault(id, Map.of()), rs.getInt("default_commission_bps"),
                            overrides.getOrDefault(id, List.of()), rs.getInt("card_pct_bps"),
                            Money.of(rs.getLong("card_fixed_minor"), currency),
                            Money.of(rs.getLong("fba_per_unit_minor"), currency),
                            rs.getBoolean("reverse_commission_on_refund"), rs.getString("note"),
                            rs.getString("created_by"), Sql.instant(rs, "created_at"));
                })
                .list();
    }

    @Override
    public Optional<FeeSchedule> findById(UUID id) {
        return all().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    @Override
    public Optional<FeeSchedule> latest() {
        List<FeeSchedule> all = all();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
    }
}
