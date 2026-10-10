package com.afrotransact.retail.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.retail.domain.Discrepancy;
import com.afrotransact.retail.domain.GoodsReceipt;
import com.afrotransact.retail.domain.LandedCharge;
import com.afrotransact.retail.domain.LandedCost;
import com.afrotransact.retail.domain.PostedLot;
import com.afrotransact.retail.domain.ReceiptLine;
import com.afrotransact.retail.domain.ReceiptStatus;
import com.afrotransact.retail.port.Page;
import com.afrotransact.retail.port.ReceiptRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcReceiptRepository implements ReceiptRepository {

    private static final TypeReference<List<ReceiptLine>> LINES = new TypeReference<>() { };
    private static final TypeReference<List<LandedCharge>> CHARGES = new TypeReference<>() { };
    private static final TypeReference<List<Discrepancy>> DISCREPANCIES = new TypeReference<>() { };
    private static final TypeReference<List<PostedLot>> LOTS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcReceiptRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public GoodsReceipt insert(GoodsReceipt r) {
        try {
            jdbc.sql("""
                    insert into goods_receipt (id, number, po_id, po_number, shipment_id, shipment_ref, node_id, dock,
                        status, lines, charges, over_approved_by, discrepancies, landed_cost, posted_lots,
                        journal_entry_id, opened_by, opened_at, completed_by, completed_at, updated_at, version)
                    values (:id, :number, :poId, :poNumber, :shipmentId, :shipmentRef, :node, :dock, :status,
                        cast(:lines as jsonb), cast(:charges as jsonb), :overBy, cast(:discrepancies as jsonb),
                        cast(:landedCost as jsonb), cast(:lots as jsonb), :journal, :openedBy, :openedAt, :completedBy,
                        :completedAt, :updatedAt, 0)
                    """)
                    .params(params(r)).param("number", r.number()).param("poId", r.poId())
                    .param("poNumber", r.poNumber()).param("shipmentId", r.shipmentId())
                    .param("shipmentRef", r.shipmentRef()).param("node", r.nodeId()).param("dock", r.dock())
                    .param("openedBy", r.openedBy()).param("openedAt", Sql.ts(r.openedAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("retail.receipt-exists", "A receipt is already open for shipment "
                    + r.shipmentRef());
        }
        return findById(r.id()).orElseThrow();
    }

    @Override
    public GoodsReceipt update(GoodsReceipt r) {
        int rows = jdbc.sql("""
                update goods_receipt set status = :status, lines = cast(:lines as jsonb),
                       charges = cast(:charges as jsonb), over_approved_by = :overBy,
                       discrepancies = cast(:discrepancies as jsonb), landed_cost = cast(:landedCost as jsonb),
                       posted_lots = cast(:lots as jsonb), journal_entry_id = :journal, completed_by = :completedBy,
                       completed_at = :completedAt, updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .params(params(r)).param("version", r.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("retail.concurrent-update", "Receipt " + r.number()
                    + " was changed by someone else; reload and retry");
        }
        return findById(r.id()).orElseThrow();
    }

    @Override
    public Optional<GoodsReceipt> findById(UUID id) {
        return jdbc.sql("select * from goods_receipt where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<GoodsReceipt> findByNumber(String number) {
        return jdbc.sql("select * from goods_receipt where number = :n").param("n", number).query(this::map)
                .optional();
    }

    @Override
    public Optional<GoodsReceipt> findActiveByShipment(UUID shipmentId) {
        return jdbc.sql("select * from goods_receipt where shipment_id = :s and status <> 'COMPLETED'")
                .param("s", shipmentId).query(this::map).optional();
    }

    @Override
    public List<GoodsReceipt> findByPo(UUID poId) {
        return jdbc.sql("select * from goods_receipt where po_id = :po order by opened_at").param("po", poId)
                .query(this::map).list();
    }

    @Override
    public Page<GoodsReceipt> page(ReceiptStatus status, UUID poId, int page, int size) {
        Sql.Where where = new Sql.Where().eq("status", "status", Sql.name(status)).eq("po_id", "poId", poId);
        long total = jdbc.sql("select count(*) from goods_receipt" + where.sql()).params(where.params())
                .query(Long.class).single();
        List<GoodsReceipt> items = jdbc.sql("select * from goods_receipt" + where.sql()
                        + " order by opened_at desc limit :limit offset :offset")
                .params(where.params()).param("limit", size).param("offset", (long) page * size)
                .query(this::map).list();
        return new Page<>(items, total, page, size);
    }

    private Map<String, Object> params(GoodsReceipt r) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", r.id());
        p.put("status", r.status().name());
        p.put("lines", json.write(r.lines()));
        p.put("charges", json.write(r.charges()));
        p.put("overBy", r.overApprovedBy());
        p.put("discrepancies", json.write(r.discrepancies()));
        p.put("landedCost", r.landedCost() == null ? null : json.write(r.landedCost()));
        p.put("lots", json.write(r.postedLots()));
        p.put("journal", r.journalEntryId());
        p.put("completedBy", r.completedBy());
        p.put("completedAt", Sql.ts(r.completedAt()));
        p.put("updatedAt", Sql.ts(r.updatedAt()));
        return p;
    }

    private GoodsReceipt map(ResultSet rs, int rowNum) throws SQLException {
        return new GoodsReceipt(rs.getObject("id", UUID.class), rs.getString("number"),
                rs.getObject("po_id", UUID.class), rs.getString("po_number"), rs.getObject("shipment_id", UUID.class),
                rs.getString("shipment_ref"), rs.getString("node_id"), rs.getString("dock"),
                ReceiptStatus.valueOf(rs.getString("status")), json.list(rs.getString("lines"), LINES),
                json.list(rs.getString("charges"), CHARGES), rs.getString("over_approved_by"),
                json.list(rs.getString("discrepancies"), DISCREPANCIES),
                json.read(rs.getString("landed_cost"), LandedCost.class), json.list(rs.getString("posted_lots"), LOTS),
                rs.getObject("journal_entry_id", UUID.class), rs.getString("opened_by"),
                Sql.instant(rs, "opened_at"), rs.getString("completed_by"), Sql.instant(rs, "completed_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
