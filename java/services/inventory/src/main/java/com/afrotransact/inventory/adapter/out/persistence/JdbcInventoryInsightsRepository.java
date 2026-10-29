package com.afrotransact.inventory.adapter.out.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.inventory.domain.AgeBucket;
import com.afrotransact.inventory.domain.ExpiryBucket;
import com.afrotransact.inventory.port.InventoryInsightsRepository;
import com.afrotransact.inventory.port.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL read models. Lot ages and days to expiry use each node's local date ({@code node.timezone}); held lots are
 * QUARANTINE, DAMAGED and EXPIRED (WRITTEN_OFF lots hold no units).
 */
@Repository
class JdbcInventoryInsightsRepository implements InventoryInsightsRepository {

    private static final String NODE_TODAY = "(now() at time zone n.timezone)::date";
    private static final int LOW_STOCK = 10;

    private final JdbcClient jdbc;

    JdbcInventoryInsightsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page<FbaStockRow> fbaStock(FbaQuery q) {
        StringBuilder where = new StringBuilder(" where p.owner_org = :owner and n.kind = 'FULFILMENT_CENTRE'");
        Map<String, Object> p = new HashMap<>();
        p.put("owner", q.ownerOrg());
        if (q.nodeId() != null) {
            where.append(" and p.node_id = :node");
            p.put("node", q.nodeId());
        }
        if (q.sku() != null) {
            where.append(" and p.sku = :sku");
            p.put("sku", q.sku());
        }
        if (q.strandedOnly()) {
            where.append(" and p.on_hand > 0 and not exists (select 1 from fba_offer_link o where o.owner_org = "
                    + "p.owner_org and o.sku = p.sku and o.live)");
        }
        String from = " from inventory_position p join node n on n.id = p.node_id" + where;
        long total = jdbc.sql("select count(*)" + from).params(p).query(Long.class).single();
        p.put("limit", q.size());
        p.put("offset", (long) q.page() * q.size());
        String age = "(" + NODE_TODAY + " - (l.received_at at time zone n.timezone)::date)";
        List<FbaStockRow> rows = jdbc.sql("select p.sku, p.node_id, p.owner_org, p.on_hand, p.reserved, p.inbound,"
                        + " p.damaged,"
                        + " (select coalesce(sum(qty_on_hand), 0) from lot l where l.sku = p.sku and l.node_id ="
                        + " p.node_id and l.status = 'QUARANTINE') as quarantined,"
                        + " (select coalesce(sum(qty_on_hand), 0) from lot l where l.sku = p.sku and l.node_id ="
                        + " p.node_id and l.status = 'DAMAGED') as damaged_lots,"
                        + " (select coalesce(sum(qty_on_hand), 0) from lot l where l.sku = p.sku and l.node_id ="
                        + " p.node_id and l.status = 'EXPIRED') as expired,"
                        + " (select coalesce(sum(l.qty_on_hand) filter (where " + age + " <= 90), 0)"
                        + " || ',' || coalesce(sum(l.qty_on_hand) filter (where " + age + " between 91 and 180), 0)"
                        + " || ',' || coalesce(sum(l.qty_on_hand) filter (where " + age + " between 181 and 270), 0)"
                        + " || ',' || coalesce(sum(l.qty_on_hand) filter (where " + age + " between 271 and 365), 0)"
                        + " || ',' || coalesce(sum(l.qty_on_hand) filter (where " + age + " > 365), 0)"
                        + " from lot l where l.sku = p.sku and l.node_id = p.node_id and l.qty_on_hand > 0"
                        + " and l.status <> 'WRITTEN_OFF') as ageing,"
                        + " (select min(l.received_at) from lot l where l.sku = p.sku and l.node_id = p.node_id"
                        + " and l.qty_on_hand > 0 and l.status <> 'WRITTEN_OFF') as oldest,"
                        + " d.unit_cubic_feet,"
                        + " (select count(*) from fba_offer_link o where o.owner_org = p.owner_org and o.sku = p.sku"
                        + " and o.status <> 'WITHDRAWN') as open_offers,"
                        + " (select count(*) from fba_offer_link o where o.owner_org = p.owner_org and o.sku = p.sku"
                        + " and o.live) as live_offers,"
                        + " coalesce((select o.atin from fba_offer_link o where o.owner_org = p.owner_org and o.sku ="
                        + " p.sku and o.atin is not null order by o.updated_at desc limit 1),"
                        + " (select il.atin from inbound_shipment_line il join inbound_shipment s on s.id ="
                        + " il.shipment_id where s.owner_org = p.owner_org and il.sku = p.sku and il.atin is not null"
                        + " order by s.created_at desc limit 1)) as atin,"
                        + " (select il.unit_label from inbound_shipment_line il join inbound_shipment s on s.id ="
                        + " il.shipment_id where s.owner_org = p.owner_org and il.sku = p.sku and il.unit_label is not"
                        + " null order by s.created_at desc limit 1) as unit_label"
                        + from.replace(" where ", " left join sku_dimension d on d.sku = p.sku where ")
                        + " order by p.sku, p.node_id limit :limit offset :offset")
                .params(p).query(this::fbaRow).list();
        return new Page<>(rows, total, q.page(), q.size());
    }

    private FbaStockRow fbaRow(ResultSet rs, int n) throws SQLException {
        Map<AgeBucket, Integer> ageing = new EnumMap<>(AgeBucket.class);
        String raw = rs.getString("ageing");
        String[] parts = raw == null ? new String[0] : raw.split(",");
        AgeBucket[] buckets = AgeBucket.values();
        for (int i = 0; i < buckets.length; i++) {
            ageing.put(buckets[i], i < parts.length ? Integer.parseInt(parts[i]) : 0);
        }
        int onHand = rs.getInt("on_hand");
        int reserved = rs.getInt("reserved");
        int held = rs.getInt("damaged");
        BigDecimal unit = rs.getBigDecimal("unit_cubic_feet");
        Timestamp oldest = rs.getTimestamp("oldest");
        return new FbaStockRow(rs.getString("sku"), rs.getString("node_id"), rs.getString("owner_org"),
                rs.getString("atin"), rs.getString("unit_label"), onHand, reserved, onHand - reserved,
                rs.getInt("inbound"), held, rs.getInt("quarantined"), rs.getInt("damaged_lots"),
                rs.getInt("expired"), ageing, oldest == null ? null : oldest.toInstant(), unit,
                rs.getInt("open_offers"), rs.getInt("live_offers"));
    }

    @Override
    public Overview overview(String nodeId, String ownerOrg, int alertLimit) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder lotWhere = new StringBuilder(" where l.qty_on_hand > 0");
        StringBuilder posWhere = new StringBuilder(" where 1 = 1");
        if (nodeId != null) {
            lotWhere.append(" and l.node_id = :node");
            posWhere.append(" and p.node_id = :node");
            p.put("node", nodeId);
        }
        if (ownerOrg != null) {
            lotWhere.append(" and l.owner_org = :owner");
            posWhere.append(" and p.owner_org = :owner");
            p.put("owner", ownerOrg);
        }
        List<StatusValue> byStatus = jdbc.sql("""
                select l.status, l.currency, sum(l.qty_on_hand) as units, count(*) as lots,
                       sum(l.qty_on_hand::bigint * l.unit_landed_cost_minor) as value,
                       sum(l.qty_on_hand::bigint * l.unit_cost_usd_minor) as value_usd
                from lot l""" + lotWhere + " group by l.status, l.currency order by l.status, l.currency")
                .params(p).query((rs, n) -> new StatusValue(rs.getString("status"), rs.getString("currency"),
                        rs.getLong("units"), rs.getLong("lots"), rs.getLong("value"), rs.getLong("value_usd"))).list();

        String daysLeft = "(l.exp_date - " + NODE_TODAY + ")";
        String bucket = "case when l.exp_date is null then 'NO_EXPIRY' when " + daysLeft + " <= 0 then 'EXPIRED'"
                + " when " + daysLeft + " <= 30 then 'D0_30' when " + daysLeft + " <= 60 then 'D31_60'"
                + " when " + daysLeft + " <= 90 then 'D61_90' when " + daysLeft + " <= 180 then 'D91_180'"
                + " else 'D181_PLUS' end";
        Map<ExpiryBucket, ExpiryCount> counts = new EnumMap<>(ExpiryBucket.class);
        for (ExpiryBucket b : ExpiryBucket.values()) {
            counts.put(b, new ExpiryCount(b, 0, 0));
        }
        jdbc.sql("select " + bucket + " as bucket, sum(l.qty_on_hand) as units, count(*) as lots"
                        + " from lot l join node n on n.id = l.node_id" + lotWhere
                        + " and l.status <> 'WRITTEN_OFF' group by 1")
                .params(p).query((rs, n) -> new ExpiryCount(ExpiryBucket.valueOf(rs.getString("bucket")),
                        rs.getLong("units"), rs.getLong("lots"))).list()
                .forEach(c -> counts.put(c.bucket(), c));

        Map<String, List<CurrencyValue>> paidIn = new LinkedHashMap<>();
        jdbc.sql("""
                select l.node_id, l.currency, sum(l.qty_on_hand) as units,
                       sum(l.qty_on_hand::bigint * l.unit_landed_cost_minor) as value,
                       sum(l.qty_on_hand::bigint * l.unit_cost_usd_minor) as value_usd
                from lot l
                where l.qty_on_hand > 0 and (cast(:owner as varchar) is null or l.owner_org = :owner)
                group by l.node_id, l.currency order by l.node_id, l.currency
                """).param("owner", ownerOrg).query((ResultSet rs) -> {
                    paidIn.computeIfAbsent(rs.getString("node_id"), k -> new ArrayList<>())
                            .add(new CurrencyValue(rs.getString("currency"), rs.getLong("units"), rs.getLong("value"),
                                    rs.getLong("value_usd")));
                });

        List<NodeFigures> nodes = jdbc.sql("""
                select n.id, n.name, n.kind,
                       (select count(*) from lot l where l.node_id = n.id and l.qty_on_hand > 0
                            and (cast(:owner as varchar) is null or l.owner_org = :owner)) as lots,
                       (select coalesce(sum(l.qty_on_hand), 0) from lot l where l.node_id = n.id
                            and (cast(:owner as varchar) is null or l.owner_org = :owner)) as units,
                       (select coalesce(sum(l.qty_on_hand), 0) from lot l where l.node_id = n.id
                            and l.status = 'SELLABLE' and (cast(:owner as varchar) is null or l.owner_org = :owner))
                            as sellable,
                       (select coalesce(sum(l.qty_on_hand), 0) from lot l where l.node_id = n.id
                            and l.status in ('QUARANTINE', 'DAMAGED', 'EXPIRED')
                            and (cast(:owner as varchar) is null or l.owner_org = :owner)) as blocked,
                       (select coalesce(sum(l.qty_on_hand::bigint * l.unit_cost_usd_minor), 0) from lot l
                            where l.node_id = n.id and (cast(:owner as varchar) is null or l.owner_org = :owner))
                            as value,
                       (select coalesce(sum(p.reserved), 0) from inventory_position p where p.node_id = n.id
                            and (cast(:owner as varchar) is null or p.owner_org = :owner)) as reserved,
                       (select coalesce(sum(p.inbound), 0) from inventory_position p where p.node_id = n.id
                            and (cast(:owner as varchar) is null or p.owner_org = :owner)) as inbound
                from node n
                where n.active and (cast(:node as varchar) is null or n.id = :node)
                order by n.id
                """).param("owner", ownerOrg).param("node", nodeId)
                .query((rs, n) -> new NodeFigures(rs.getString("id"), rs.getString("name"), rs.getString("kind"),
                        rs.getLong("lots"), rs.getLong("units"), rs.getLong("sellable"), rs.getLong("blocked"),
                        rs.getLong("value"), "USD", rs.getLong("reserved"), rs.getLong("inbound"),
                        paidIn.getOrDefault(rs.getString("id"), List.of()))).list();

        StringBuilder inboundWhere = new StringBuilder(" where s.status not in ('CLOSED', 'CANCELLED')");
        if (nodeId != null) {
            inboundWhere.append(" and s.node_id = :node");
        }
        if (ownerOrg != null) {
            inboundWhere.append(" and s.owner_org = :owner");
        }
        InboundFigures inbound = jdbc.sql("""
                select count(distinct s.id) as shipments, coalesce(sum(l.expected_qty), 0) as expected,
                       coalesce(sum(l.received_good + l.received_damaged), 0) as received,
                       count(distinct s.id) filter (where l.received_damaged > 0
                           or l.received_good + l.received_damaged > l.expected_qty) as discrepancies
                from inbound_shipment s left join inbound_shipment_line l on l.shipment_id = s.id""" + inboundWhere)
                .params(p).query((rs, n) -> new InboundFigures(rs.getLong("shipments"), rs.getLong("expected"),
                        rs.getLong("received"), rs.getLong("discrepancies"))).single();

        ReservationFigures reservations = jdbc.sql("""
                select count(distinct r.id) as held, coalesce(sum(rl.qty), 0) as units
                from reservation r join reservation_line rl on rl.reservation_id = r.id and rl.status = 'HELD'
                where r.status = 'HELD' and (cast(:node as varchar) is null or rl.node_id = :node)
                  and (cast(:owner as varchar) is null or rl.stock_owner_org = :owner)
                """).param("node", nodeId).param("owner", ownerOrg)
                .query((rs, n) -> new ReservationFigures(rs.getLong("held"), rs.getLong("units"))).single();

        return new Overview(byStatus, List.copyOf(counts.values()), nodes, inbound, reservations,
                alerts(lotWhere.toString(), posWhere.toString(), inboundWhere.toString(), p, alertLimit));
    }

    private List<Alert> alerts(String lotWhere, String posWhere, String inboundWhere, Map<String, Object> p,
                               int limit) {
        Map<String, Object> params = new HashMap<>(p);
        params.put("limit", limit);
        List<Alert> alerts = new ArrayList<>();
        // Expired lots still holding units, then sellable lots expiring within 30 days (each up to the limit).
        for (String condition : List.of("l.status = 'EXPIRED'", "l.status = 'SELLABLE' and l.exp_date - "
                + NODE_TODAY + " <= 30")) {
            alerts.addAll(jdbc.sql("select l.id, l.sku, l.node_id, l.lot_no, l.qty_on_hand, l.exp_date, l.status,"
                            + " (l.exp_date - " + NODE_TODAY + ") as days_left"
                            + " from lot l join node n on n.id = l.node_id" + lotWhere
                            + " and l.exp_date is not null and " + condition
                            + " order by l.exp_date, l.sku limit :limit")
                    .params(params).query(this::lotAlert).list());
        }
        alerts.addAll(jdbc.sql("select p.sku, p.node_id, p.on_hand - p.reserved as available, p.inbound"
                        + " from inventory_position p" + posWhere + " and p.on_hand - p.reserved <= " + LOW_STOCK
                        + " and (p.on_hand > 0 or p.reserved > 0) order by p.on_hand - p.reserved, p.sku limit :limit")
                .params(params).query((rs, n) -> new Alert("LOW_STOCK", "LOW", rs.getString("sku"),
                        rs.getString("node_id"), null, null, null, rs.getLong("available"), null,
                        rs.getString("sku") + ": " + rs.getLong("available") + " available at "
                                + rs.getString("node_id") + (rs.getLong("inbound") > 0 ? " · " + rs.getLong("inbound")
                                + " inbound" : " · nothing inbound"))).list());
        alerts.addAll(jdbc.sql("""
                select s.id, s.number, s.node_id, sum(greatest(l.received_good + l.received_damaged - l.expected_qty, 0)
                    + l.received_damaged) as qty
                from inbound_shipment s join inbound_shipment_line l on l.shipment_id = s.id""" + inboundWhere + """
                 group by s.id, s.number, s.node_id
                having sum(greatest(l.received_good + l.received_damaged - l.expected_qty, 0) + l.received_damaged) > 0
                order by s.number limit :limit
                """).params(params).query((rs, n) -> new Alert("INBOUND_DISCREPANCY", "MEDIUM", null,
                rs.getString("node_id"), null, null, rs.getObject("id", UUID.class), rs.getLong("qty"), null,
                rs.getString("number") + ": " + rs.getLong("qty") + " units over or damaged at receiving")).list());
        return alerts;
    }

    private Alert lotAlert(ResultSet rs, int n) throws SQLException {
        boolean expired = "EXPIRED".equals(rs.getString("status")) || rs.getInt("days_left") <= 0;
        Date exp = rs.getDate("exp_date");
        long qty = rs.getLong("qty_on_hand");
        String lotNo = rs.getString("lot_no");
        String node = rs.getString("node_id");
        return new Alert(expired ? "LOT_EXPIRED" : "LOT_EXPIRING", expired ? "HIGH" : "MEDIUM", rs.getString("sku"),
                node, rs.getObject("id", UUID.class), lotNo, null, qty, exp.toLocalDate(), expired
                ? qty + " units of " + rs.getString("sku") + " expired (" + lotNo + ", " + node
                + "): blocked from sale, write off"
                : lotNo + " expires in " + rs.getInt("days_left") + " days (" + qty + " units at " + node
                + "): picked first, plan a markdown");
    }

    @Override
    public List<LotConsumer> consumersOfLot(UUID lotId) {
        return jdbc.sql("""
                select r.order_id, r.id, rl.line_no, rl.group_key, rl.status as line_status, r.status, a.qty,
                       r.updated_at
                from reservation_allocation a
                join reservation_line rl on rl.reservation_id = a.reservation_id and rl.line_no = a.line_no
                join reservation r on r.id = a.reservation_id
                where a.lot_id = :lot
                order by r.updated_at desc, r.order_id, rl.line_no
                """).param("lot", lotId).query((rs, n) -> new LotConsumer(rs.getString("order_id"),
                rs.getObject("id", UUID.class), rs.getInt("line_no"), rs.getString("group_key"),
                rs.getString("line_status"), rs.getString("status"), rs.getInt("qty"),
                rs.getTimestamp("updated_at").toInstant())).list();
    }
}
