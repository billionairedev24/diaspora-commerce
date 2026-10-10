package com.afrotransact.retail.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.retail.domain.ApprovalTier;
import com.afrotransact.retail.domain.FxRate;
import com.afrotransact.retail.domain.PoHistoryEntry;
import com.afrotransact.retail.domain.PoLine;
import com.afrotransact.retail.domain.PoStatus;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.port.Page;
import com.afrotransact.retail.port.PurchaseOrderRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcPurchaseOrderRepository implements PurchaseOrderRepository {

    private static final TypeReference<List<PoLine>> LINES = new TypeReference<>() { };
    private static final TypeReference<List<PoHistoryEntry>> HISTORY = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcPurchaseOrderRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public PurchaseOrder insert(PurchaseOrder po) {
        jdbc.sql("""
                insert into purchase_order (id, number, supplier_id, supplier_code, currency, fx_rate, fx_base_currency,
                    fx_date, destination_node_id, incoterm, order_date, expected_date, lines, total_minor, status,
                    approval_tier, shipment_booked, created_by, approved_by, history, created_at, updated_at, version)
                values (:id, :number, :supplierId, :supplierCode, :currency, :fxRate, :fxBase, :fxDate, :node,
                    :incoterm, :orderDate, :expectedDate, cast(:lines as jsonb), :total, :status, :tier, :booked,
                    :createdBy, :approvedBy, cast(:history as jsonb), :createdAt, :updatedAt, 0)
                """)
                .params(params(po)).param("number", po.number()).param("supplierId", po.supplierId())
                .param("supplierCode", po.supplierCode()).param("currency", po.currency())
                .param("fxRate", po.fx().unitsPerBase()).param("fxBase", po.fx().baseCurrency())
                .param("fxDate", po.fx().asOf()).param("orderDate", po.orderDate())
                .param("createdBy", po.createdBy()).param("createdAt", Sql.ts(po.createdAt()))
                .update();
        return findById(po.id()).orElseThrow();
    }

    @Override
    public PurchaseOrder update(PurchaseOrder po) {
        int rows = jdbc.sql("""
                update purchase_order set destination_node_id = :node, incoterm = :incoterm,
                       expected_date = :expectedDate, lines = cast(:lines as jsonb), total_minor = :total,
                       status = :status, approval_tier = :tier, shipment_booked = :booked, approved_by = :approvedBy,
                       history = cast(:history as jsonb), updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .params(params(po)).param("version", po.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("retail.concurrent-update", "PO " + po.number()
                    + " was changed by someone else; reload and retry");
        }
        return findById(po.id()).orElseThrow();
    }

    @Override
    public Optional<PurchaseOrder> findById(UUID id) {
        return jdbc.sql("select * from purchase_order where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<PurchaseOrder> findByNumber(String number) {
        return jdbc.sql("select * from purchase_order where number = :n").param("n", number).query(this::map)
                .optional();
    }

    @Override
    public Page<PurchaseOrder> page(PoStatus status, UUID supplierId, int page, int size) {
        Sql.Where where = new Sql.Where().eq("status", "status", Sql.name(status))
                .eq("supplier_id", "supplierId", supplierId);
        long total = jdbc.sql("select count(*) from purchase_order" + where.sql()).params(where.params())
                .query(Long.class).single();
        List<PurchaseOrder> items = jdbc.sql("select * from purchase_order" + where.sql()
                        + " order by created_at desc limit :limit offset :offset")
                .params(where.params()).param("limit", size).param("offset", (long) page * size)
                .query(this::map).list();
        return new Page<>(items, total, page, size);
    }

    private Map<String, Object> params(PurchaseOrder po) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", po.id());
        p.put("node", po.destinationNodeId());
        p.put("incoterm", po.incoterm());
        p.put("expectedDate", po.expectedDate());
        p.put("lines", json.write(po.lines()));
        p.put("total", po.totalMinor());
        p.put("status", po.status().name());
        p.put("tier", po.approvalTier().name());
        p.put("booked", po.shipmentBooked());
        p.put("approvedBy", po.approvedBy());
        p.put("history", json.write(po.history()));
        p.put("updatedAt", Sql.ts(po.updatedAt()));
        return p;
    }

    private PurchaseOrder map(ResultSet rs, int rowNum) throws SQLException {
        String currency = rs.getString("currency");
        FxRate fx = new FxRate(currency, rs.getString("fx_base_currency"),
                rs.getBigDecimal("fx_rate").stripTrailingZeros(), Sql.date(rs, "fx_date"));
        return new PurchaseOrder(rs.getObject("id", UUID.class), rs.getString("number"),
                rs.getObject("supplier_id", UUID.class), rs.getString("supplier_code"), currency, fx,
                rs.getString("destination_node_id"), rs.getString("incoterm"), Sql.date(rs, "order_date"),
                Sql.date(rs, "expected_date"), json.list(rs.getString("lines"), LINES),
                PoStatus.valueOf(rs.getString("status")), ApprovalTier.valueOf(rs.getString("approval_tier")),
                rs.getBoolean("shipment_booked"), rs.getString("created_by"), rs.getString("approved_by"),
                json.list(rs.getString("history"), HISTORY), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
