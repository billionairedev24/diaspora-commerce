package com.afrotransact.inventory.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.CostComponents;
import com.afrotransact.inventory.domain.ExchangeRate;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotCost;
import com.afrotransact.inventory.domain.LotStatus;
import com.afrotransact.inventory.domain.MarkdownFlag;
import com.afrotransact.inventory.domain.RateSource;
import com.afrotransact.inventory.domain.StockLevels;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLotRepository implements LotRepository {

    private static final String SELECT = """
            select id, sku, lot_no, node_id, owner_org, bin, qty_on_hand, qty_reserved, mfg_date, exp_date,
                   received_at, unit_landed_cost_minor, currency, fx_rate_to_usd, fx_rate_date, fx_rate_source,
                   unit_cost_usd_minor, cost_goods_minor, cost_freight_minor, cost_duty_minor, cost_other_minor,
                   status, reason, markdown_flag, receipt_ref, version
            from lot
            """;
    private static final String FEFO = " order by exp_date nulls last, received_at, lot_no";

    private final JdbcClient jdbc;

    JdbcLotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Lot l) {
        jdbc.sql("""
                insert into lot (id, sku, lot_no, node_id, owner_org, bin, qty_on_hand, qty_reserved, mfg_date,
                                 exp_date, received_at, unit_landed_cost_minor, currency, fx_rate_to_usd,
                                 fx_rate_date, fx_rate_source, unit_cost_usd_minor, cost_goods_minor,
                                 cost_freight_minor, cost_duty_minor, cost_other_minor, status, reason,
                                 markdown_flag, receipt_ref, version, updated_at)
                values (:id, :sku, :lotNo, :node, :owner, :bin, :onHand, :reserved, :mfg, :exp, :receivedAt, :cost,
                        :currency, :rate, :rateDate, :rateSource, :costUsd, :goods, :freight, :duty, :other,
                        :status, :reason, :markdown, :receiptRef, 0, :receivedAt)
                """)
                .params(params(l))
                .update();
    }

    @Override
    public void update(Lot l) {
        Map<String, Object> params = params(l);
        params.put("version", l.version());
        params.put("now", Timestamp.from(Instant.now()));
        int rows = jdbc.sql("""
                update lot set bin = :bin, qty_on_hand = :onHand, qty_reserved = :reserved, status = :status,
                       reason = :reason, markdown_flag = :markdown, version = version + 1, updated_at = :now
                where id = :id and version = :version
                """)
                .params(params)
                .update();
        if (rows == 0) {
            throw new ConflictException("inventory.concurrent-update", "Lot " + l.lotNo() + " changed concurrently");
        }
    }

    @Override
    public Optional<Lot> findById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(JdbcLotRepository::map).optional();
    }

    @Override
    public Optional<Lot> lockById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(JdbcLotRepository::map)
                .optional();
    }

    @Override
    public List<Lot> lockByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " where id in (:ids) order by id for update").param("ids", List.copyOf(ids))
                .query(JdbcLotRepository::map).list();
    }

    @Override
    public List<Lot> lockAllocatable(String sku, String nodeId) {
        return jdbc.sql(SELECT + """
                 where sku = :sku and node_id = :node and status = 'SELLABLE' and qty_on_hand > qty_reserved
                """ + FEFO + " for update")
                .param("sku", sku).param("node", nodeId)
                .query(JdbcLotRepository::map).list();
    }

    @Override
    public Optional<Lot> lockByKey(String sku, String lotNo, String nodeId) {
        return jdbc.sql(SELECT + " where sku = :sku and lot_no = :lotNo and node_id = :node for update")
                .param("sku", sku).param("lotNo", lotNo).param("node", nodeId)
                .query(JdbcLotRepository::map).optional();
    }

    @Override
    public Page<Lot> search(LotQuery q) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (q.sku() != null) {
            where.append(" and sku = :sku");
            params.put("sku", q.sku());
        }
        if (q.nodeId() != null) {
            where.append(" and node_id = :node");
            params.put("node", q.nodeId());
        }
        if (q.ownerOrg() != null) {
            where.append(" and owner_org = :owner");
            params.put("owner", q.ownerOrg());
        }
        if (q.status() != null) {
            where.append(" and status = :status");
            params.put("status", q.status().name());
        }
        if (q.lotNo() != null) {
            where.append(" and lot_no = :lotNo");
            params.put("lotNo", q.lotNo());
        }
        long total = jdbc.sql("select count(*) from lot" + where).params(params).query(Long.class).single();
        params.put("limit", q.size());
        params.put("offset", (long) q.page() * q.size());
        List<Lot> items = jdbc.sql(SELECT + where + " order by sku, node_id, exp_date nulls last, received_at, lot_no"
                        + " limit :limit offset :offset")
                .params(params).query(JdbcLotRepository::map).list();
        return new Page<>(items, total, q.page(), q.size());
    }

    @Override
    public List<Lot> findForCount(String nodeId, String sku, String binPrefix, String ownerOrg, int limit) {
        StringBuilder where = new StringBuilder(" where node_id = :node and qty_on_hand > 0 and status <> 'WRITTEN_OFF'");
        Map<String, Object> params = new HashMap<>();
        params.put("node", nodeId);
        params.put("limit", limit);
        if (sku != null) {
            where.append(" and sku = :sku");
            params.put("sku", sku);
        }
        if (binPrefix != null) {
            where.append(" and bin like :bin");
            params.put("bin", binPrefix.replace("%", "").replace("_", "\\_") + "%");
        }
        if (ownerOrg != null) {
            where.append(" and owner_org = :owner");
            params.put("owner", ownerOrg);
        }
        return jdbc.sql(SELECT + where + " order by bin nulls last, sku, exp_date nulls last, lot_no limit :limit")
                .params(params).query(JdbcLotRepository::map).list();
    }

    @Override
    public List<Lot> findMarkdown(String ownerOrg, MarkdownFlag minimum) {
        List<String> flags = minimum == MarkdownFlag.WITHIN_30 ? List.of("WITHIN_30") : List.of("WITHIN_30", "WITHIN_60");
        Map<String, Object> params = new HashMap<>();
        params.put("flags", flags);
        String where = " where status = 'SELLABLE' and qty_on_hand > 0 and markdown_flag in (:flags)";
        if (ownerOrg != null) {
            where += " and owner_org = :owner";
            params.put("owner", ownerOrg);
        }
        return jdbc.sql(SELECT + where + " order by exp_date, sku, node_id").params(params)
                .query(JdbcLotRepository::map).list();
    }

    @Override
    public List<UUID> findExpiredIds(String nodeId, LocalDate today) {
        return jdbc.sql("""
                select id from lot
                where node_id = :node and status in ('SELLABLE', 'QUARANTINE', 'DAMAGED') and exp_date <= :today
                order by sku, exp_date
                """)
                .param("node", nodeId).param("today", Date.valueOf(today))
                .query(UUID.class).list();
    }

    @Override
    public int refreshMarkdownFlags(String nodeId, LocalDate today) {
        return jdbc.sql("""
                update lot set markdown_flag = case
                        when exp_date is null then 'NONE'
                        when exp_date - cast(:today as date) <= 30 then 'WITHIN_30'
                        when exp_date - cast(:today as date) <= 60 then 'WITHIN_60'
                        else 'NONE' end
                where node_id = :node and status = 'SELLABLE'
                  and markdown_flag is distinct from (case
                        when exp_date is null then 'NONE'
                        when exp_date - cast(:today as date) <= 30 then 'WITHIN_30'
                        when exp_date - cast(:today as date) <= 60 then 'WITHIN_60'
                        else 'NONE' end)
                """)
                .param("node", nodeId).param("today", Date.valueOf(today))
                .update();
    }

    @Override
    public Map<String, StockLevels> levelsByPosition() {
        Map<String, StockLevels> out = new LinkedHashMap<>();
        jdbc.sql("""
                select sku, node_id,
                       coalesce(sum(qty_on_hand) filter (where status = 'SELLABLE'), 0) as on_hand,
                       coalesce(sum(qty_reserved) filter (where status = 'SELLABLE'), 0) as reserved,
                       coalesce(sum(qty_on_hand) filter (where status in ('QUARANTINE', 'DAMAGED', 'EXPIRED')), 0)
                           as held
                from lot group by sku, node_id
                """)
                .query((ResultSet rs) -> {
                    out.put(rs.getString("sku") + "|" + rs.getString("node_id"),
                            new StockLevels(rs.getInt("on_hand"), rs.getInt("reserved"), rs.getInt("held"), 0));
                });
        return out;
    }

    private static Map<String, Object> params(Lot l) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", l.id());
        m.put("sku", l.sku());
        m.put("lotNo", l.lotNo());
        m.put("node", l.nodeId());
        m.put("owner", l.ownerOrg());
        m.put("bin", l.bin());
        m.put("onHand", l.qtyOnHand());
        m.put("reserved", l.qtyReserved());
        m.put("mfg", l.mfgDate() == null ? null : Date.valueOf(l.mfgDate()));
        m.put("exp", l.expDate() == null ? null : Date.valueOf(l.expDate()));
        m.put("receivedAt", Timestamp.from(l.receivedAt()));
        LotCost c = l.cost();
        m.put("cost", c.unitMinor());
        m.put("currency", c.currency());
        m.put("rate", c.rateToUsd());
        m.put("rateDate", Date.valueOf(c.rateDate()));
        m.put("rateSource", c.rateSource().name());
        m.put("costUsd", c.unitUsdMinor());
        m.put("goods", c.components().goodsMinor());
        m.put("freight", c.components().freightMinor());
        m.put("duty", c.components().dutyMinor());
        m.put("other", c.components().otherMinor());
        m.put("status", l.status().name());
        m.put("reason", l.reason());
        m.put("markdown", l.markdownFlag().name());
        m.put("receiptRef", l.receiptRef());
        return m;
    }

    static Lot map(ResultSet rs, int row) throws SQLException {
        Date mfg = rs.getDate("mfg_date");
        Date exp = rs.getDate("exp_date");
        LotCost cost = new LotCost(rs.getLong("unit_landed_cost_minor"), rs.getString("currency"),
                ExchangeRate.tidy(rs.getBigDecimal("fx_rate_to_usd")), rs.getDate("fx_rate_date").toLocalDate(),
                RateSource.valueOf(rs.getString("fx_rate_source")), rs.getLong("unit_cost_usd_minor"),
                new CostComponents(rs.getLong("cost_goods_minor"), rs.getLong("cost_freight_minor"),
                        rs.getLong("cost_duty_minor"), rs.getLong("cost_other_minor")));
        return new Lot(rs.getObject("id", UUID.class), rs.getString("sku"), rs.getString("lot_no"),
                rs.getString("node_id"), rs.getString("owner_org"), rs.getString("bin"), rs.getInt("qty_on_hand"),
                rs.getInt("qty_reserved"), mfg == null ? null : mfg.toLocalDate(), exp == null ? null : exp.toLocalDate(),
                rs.getTimestamp("received_at").toInstant(), cost, LotStatus.valueOf(rs.getString("status")), rs.getString("reason"),
                MarkdownFlag.valueOf(rs.getString("markdown_flag")), rs.getString("receipt_ref"),
                rs.getLong("version"));
    }
}
