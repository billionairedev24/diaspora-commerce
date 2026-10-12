package com.afrotransact.payments.adapter.out.persistence;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.CommissionOverride;
import com.afrotransact.payments.domain.DeliveryBand;
import com.afrotransact.payments.domain.DeliveryMode;
import com.afrotransact.payments.domain.DeliveryPricing;
import com.afrotransact.payments.domain.DeliveryTariff;
import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.RateTable;
import com.afrotransact.payments.domain.SellerDeliveryOverride;
import com.afrotransact.payments.port.FeeScheduleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcFeeScheduleRepository implements FeeScheduleRepository {

    private static final TypeReference<List<BandRow>> BANDS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcFeeScheduleRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
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
        insertPlanRates(s.id(), "COMMISSION", s.planBps());
        RateTable connection = s.connectionFee();
        if (connection != null) {
            jdbc.sql("insert into fee_schedule_connection_fee (schedule_id, default_bps) values (:id, :bps)")
                    .param("id", s.id()).param("bps", connection.defaultBps()).update();
            connection.categoryBps().forEach((category, bps) -> jdbc.sql("""
                    insert into fee_schedule_connection_fee_rate (schedule_id, category, rate_bps)
                    values (:id, :c, :bps)
                    """).param("id", s.id()).param("c", category).param("bps", bps).update());
            insertPlanRates(s.id(), "CONNECTION_FEE", connection.planBps());
        }
        DeliveryPricing d = s.deliveryPricing();
        if (d != null) {
            jdbc.sql("insert into fee_schedule_delivery (schedule_id, max_radius_miles) values (:id, :radius)")
                    .param("id", s.id()).param("radius", d.maxRadiusMiles()).update();
            insertTariff(s.id(), "CUSTOMER", "", d.customerCharge());
            insertTariff(s.id(), "SELLER", "", d.sellerFee());
            for (SellerDeliveryOverride o : d.sellerOverrides()) {
                insertTariff(s.id(), "SELLER", o.orgId(), o.sellerFee());
            }
        }
    }

    private void insertPlanRates(UUID scheduleId, String section, Map<String, Map<String, Integer>> plans) {
        plans.forEach((plan, rates) -> rates.forEach((category, bps) -> jdbc.sql("""
                insert into fee_schedule_plan_rate (schedule_id, section, plan_code, category, rate_bps)
                values (:id, :section, :plan, :c, :bps)
                """).param("id", scheduleId).param("section", section).param("plan", plan).param("c", category)
                .param("bps", bps).update()));
    }

    private void insertTariff(UUID scheduleId, String party, String orgId, DeliveryTariff t) {
        jdbc.sql("""
                insert into fee_schedule_delivery_tariff (schedule_id, party, org_id, mode, flat_per_package_minor,
                    base_minor, per_mile_minor, bands, min_minor, max_minor)
                values (:id, :party, :org, :mode, :flat, :base, :perMile, cast(:bands as jsonb), :min, :max)
                """)
                .param("id", scheduleId).param("party", party).param("org", orgId).param("mode", t.mode().name())
                .param("flat", t.flatPerPackage().minor()).param("base", t.base().minor())
                .param("perMile", t.perMileMinor())
                .param("bands", json.write(t.bands().stream().map(BandRow::of).toList()))
                .param("min", t.min().minor()).param("max", t.max() == null ? null : t.max().minor())
                .update();
    }

    /** Stored form of a distance band. */
    record BandRow(BigDecimal fromMiles, BigDecimal toMiles, long feeMinor) {

        static BandRow of(DeliveryBand b) {
            return new BandRow(b.fromMiles(), b.toMiles(), b.fee().minor());
        }
    }

    private Map<UUID, DeliveryPricing> deliveryPricing(Map<UUID, String> currencies) {
        Map<UUID, BigDecimal> radius = new HashMap<>();
        jdbc.sql("select schedule_id, max_radius_miles from fee_schedule_delivery").query(rs -> {
            radius.put(rs.getObject("schedule_id", UUID.class), rs.getBigDecimal("max_radius_miles"));
        });
        Map<UUID, DeliveryTariff> customer = new HashMap<>();
        Map<UUID, DeliveryTariff> seller = new HashMap<>();
        Map<UUID, List<SellerDeliveryOverride>> overrides = new HashMap<>();
        jdbc.sql("select * from fee_schedule_delivery_tariff order by schedule_id, party, org_id").query(rs -> {
            UUID id = rs.getObject("schedule_id", UUID.class);
            String currency = currencies.get(id);
            List<DeliveryBand> bands = json.read(rs.getString("bands"), BANDS).stream()
                    .map(b -> new DeliveryBand(b.fromMiles(), b.toMiles(), Money.of(b.feeMinor(), currency)))
                    .toList();
            long max = rs.getLong("max_minor");
            boolean noMax = rs.wasNull();
            DeliveryTariff t = new DeliveryTariff(DeliveryMode.valueOf(rs.getString("mode")),
                    Money.of(rs.getLong("flat_per_package_minor"), currency),
                    Money.of(rs.getLong("base_minor"), currency), rs.getLong("per_mile_minor"), bands,
                    Money.of(rs.getLong("min_minor"), currency), noMax ? null : Money.of(max, currency));
            String org = rs.getString("org_id");
            if ("CUSTOMER".equals(rs.getString("party"))) {
                customer.put(id, t);
            } else if (org.isEmpty()) {
                seller.put(id, t);
            } else {
                overrides.computeIfAbsent(id, k -> new ArrayList<>()).add(new SellerDeliveryOverride(org, t));
            }
        });
        Map<UUID, DeliveryPricing> out = new HashMap<>();
        radius.forEach((id, r) -> {
            String currency = currencies.get(id);
            out.put(id, new DeliveryPricing(customer.getOrDefault(id, DeliveryTariff.free(currency)),
                    seller.getOrDefault(id, DeliveryTariff.free(currency)), r, overrides.getOrDefault(id, List.of())));
        });
        return out;
    }

    @Override
    public void insertCancellation(UUID scheduleId, FeeSchedule.Cancellation c) {
        jdbc.sql("""
                insert into fee_schedule_cancellation (schedule_id, reason, cancelled_by, cancelled_at)
                values (:id, :reason, :by, :at)
                """).param("id", scheduleId).param("reason", c.reason()).param("by", c.by())
                .param("at", Sql.ts(c.at())).update();
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
        Map<UUID, FeeSchedule.Cancellation> cancellations = new HashMap<>();
        jdbc.sql("select schedule_id, reason, cancelled_by, cancelled_at from fee_schedule_cancellation").query(rs -> {
            cancellations.put(rs.getObject("schedule_id", UUID.class), new FeeSchedule.Cancellation(
                    Sql.instant(rs, "cancelled_at"), rs.getString("cancelled_by"), rs.getString("reason")));
        });
        Map<UUID, String> currencies = new HashMap<>();
        jdbc.sql("select id, currency from fee_schedule").query(rs -> {
            currencies.put(rs.getObject("id", UUID.class), rs.getString("currency"));
        });
        Map<UUID, DeliveryPricing> delivery = deliveryPricing(currencies);
        Map<UUID, Map<String, Map<String, Map<String, Integer>>>> plans = new HashMap<>();
        jdbc.sql("select schedule_id, section, plan_code, category, rate_bps from fee_schedule_plan_rate").query(rs -> {
            plans.computeIfAbsent(rs.getObject("schedule_id", UUID.class), k -> new HashMap<>())
                    .computeIfAbsent(rs.getString("section"), k -> new HashMap<>())
                    .computeIfAbsent(rs.getString("plan_code"), k -> new HashMap<>())
                    .put(rs.getString("category"), rs.getInt("rate_bps"));
        });
        Map<UUID, Map<String, Integer>> connectionRates = new HashMap<>();
        jdbc.sql("select schedule_id, category, rate_bps from fee_schedule_connection_fee_rate").query(rs -> {
            connectionRates.computeIfAbsent(rs.getObject("schedule_id", UUID.class), k -> new HashMap<>())
                    .put(rs.getString("category"), rs.getInt("rate_bps"));
        });
        Map<UUID, RateTable> connection = new HashMap<>();
        jdbc.sql("select schedule_id, default_bps from fee_schedule_connection_fee").query(rs -> {
            UUID id = rs.getObject("schedule_id", UUID.class);
            connection.put(id, new RateTable(rs.getInt("default_bps"), connectionRates.getOrDefault(id, Map.of()),
                    plans.getOrDefault(id, Map.of()).getOrDefault("CONNECTION_FEE", Map.of())));
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
                            rs.getString("created_by"), Sql.instant(rs, "created_at"), cancellations.get(id),
                            delivery.get(id), plans.getOrDefault(id, Map.of()).getOrDefault("COMMISSION", Map.of()),
                            connection.get(id));
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
