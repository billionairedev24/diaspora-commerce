package com.afrotransact.inventory.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.ExpectedBatch;
import com.afrotransact.inventory.domain.InboundBox;
import com.afrotransact.inventory.domain.InboundLine;
import com.afrotransact.inventory.domain.InboundShipment;
import com.afrotransact.inventory.domain.InboundSource;
import com.afrotransact.inventory.domain.InboundStatus;
import com.afrotransact.inventory.domain.PostalAddress;
import com.afrotransact.inventory.port.InboundShipmentRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcInboundShipmentRepository implements InboundShipmentRepository {

    private static final TypeReference<List<InboundBox.Item>> ITEMS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcInboundShipmentRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public String nextFbaNumber() {
        long n = jdbc.sql("select nextval('inbound_shipment_seq')").query(Long.class).single();
        return "FBA-IN-" + String.format("%06d", n);
    }

    @Override
    public void insert(InboundShipment s) {
        jdbc.sql("""
                insert into inbound_shipment (id, number, source, external_ref, owner_org, node_id, requested_node_id,
                    assignment_reason, name, status, ship_from, carrier, tracking_number, tracking_source,
                    carrier_label_id, label_cost_minor, label_currency, label_document_url, expected_arrival,
                    receipt_count, created_by, created_at, shipped_at, closed_at, updated_at, version)
                values (:id, :number, :source, :externalRef, :owner, :node, :requestedNode, :reason, :name, :status,
                    cast(:shipFrom as jsonb), :carrier, :tracking, :trackingSource, :labelId, :labelCost,
                    :labelCurrency, :labelUrl, :eta, :receipts, :createdBy, :createdAt, :shippedAt, :closedAt,
                    :updatedAt, 0)
                """).params(params(s)).update();
        writeChildren(s);
    }

    @Override
    public InboundShipment update(InboundShipment s) {
        int rows = jdbc.sql("""
                update inbound_shipment set status = :status, carrier = :carrier, tracking_number = :tracking,
                    tracking_source = :trackingSource, carrier_label_id = :labelId, label_cost_minor = :labelCost,
                    label_currency = :labelCurrency, label_document_url = :labelUrl, receipt_count = :receipts,
                    shipped_at = :shippedAt, closed_at = :closedAt, updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """).params(params(s)).param("version", s.version()).update();
        if (rows == 0) {
            throw new ConflictException("inventory.concurrent-update", "Inbound shipment " + s.number()
                    + " changed meanwhile; reload and retry");
        }
        jdbc.sql("delete from inbound_shipment_line where shipment_id = :id").param("id", s.id()).update();
        jdbc.sql("delete from inbound_box where shipment_id = :id").param("id", s.id()).update();
        writeChildren(s);
        return findById(s.id()).orElseThrow();
    }

    @Override
    public Optional<InboundShipment> findById(UUID id) {
        return jdbc.sql("select * from inbound_shipment where id = :id").param("id", id).query(this::map).optional()
                .map(this::withChildren);
    }

    @Override
    public Optional<InboundShipment> lockById(UUID id) {
        return jdbc.sql("select * from inbound_shipment where id = :id for update").param("id", id)
                .query(this::map).optional().map(this::withChildren);
    }

    @Override
    public Optional<InboundShipment> lockByReference(String reference) {
        return jdbc.sql("""
                select * from inbound_shipment where number = :ref or external_ref = :ref
                order by (number = :ref) desc limit 1 for update
                """).param("ref", reference).query(this::map).optional().map(this::withChildren);
    }

    @Override
    public Optional<InboundShipment> lockByExternalRef(InboundSource source, String externalRef) {
        return jdbc.sql("select * from inbound_shipment where source = :source and external_ref = :ref for update")
                .param("source", source.name()).param("ref", externalRef).query(this::map).optional()
                .map(this::withChildren);
    }

    @Override
    public Page<InboundShipment> search(InboundQuery q) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> p = new HashMap<>();
        if (q.ownerOrg() != null) {
            where.append(" and s.owner_org = :owner");
            p.put("owner", q.ownerOrg());
        }
        if (q.nodeId() != null) {
            where.append(" and s.node_id = :node");
            p.put("node", q.nodeId());
        }
        if (q.status() != null) {
            where.append(" and s.status = :status");
            p.put("status", q.status().name());
        }
        if (q.source() != null) {
            where.append(" and s.source = :source");
            p.put("source", q.source().name());
        }
        if (q.sku() != null) {
            where.append(" and exists (select 1 from inbound_shipment_line l where l.shipment_id = s.id"
                    + " and l.sku = :sku)");
            p.put("sku", q.sku());
        }
        long total = jdbc.sql("select count(*) from inbound_shipment s" + where).params(p).query(Long.class).single();
        p.put("limit", q.size());
        p.put("offset", (long) q.page() * q.size());
        List<InboundShipment> items = jdbc.sql("select s.* from inbound_shipment s" + where
                        + " order by s.created_at desc, s.id limit :limit offset :offset")
                .params(p).query(this::map).list().stream().map(this::withChildren).toList();
        return new Page<>(items, total, q.page(), q.size());
    }

    private void writeChildren(InboundShipment s) {
        for (InboundLine l : s.lines()) {
            jdbc.sql("""
                    insert into inbound_shipment_line (shipment_id, line_no, sku, atin, unit_label, expected_qty,
                        received_good, received_damaged, inbound_open, case_pack, lot_no, mfg_date, exp_date)
                    values (:id, :lineNo, :sku, :atin, :label, :expected, :good, :damaged, :open, :casePack, :lotNo,
                        :mfgDate, :expDate)
                    """)
                    .param("id", s.id()).param("lineNo", l.lineNo()).param("sku", l.sku()).param("atin", l.atin())
                    .param("label", l.unitLabel()).param("expected", l.expectedQty())
                    .param("good", l.receivedGood()).param("damaged", l.receivedDamaged())
                    .param("open", l.inboundOpen()).param("casePack", l.batch().casePack())
                    .param("lotNo", l.batch().lotNo()).param("mfgDate", l.batch().mfgDate())
                    .param("expDate", l.batch().expDate()).update();
        }
        for (InboundBox b : s.boxes()) {
            jdbc.sql("""
                    insert into inbound_box (shipment_id, box_no, lpn, contents, weight_lb, length_in, width_in,
                        height_in, received_at)
                    values (:id, :boxNo, :lpn, cast(:contents as jsonb), :weight, :length, :width, :height,
                        :receivedAt)
                    """)
                    .param("id", s.id()).param("boxNo", b.boxNo()).param("lpn", b.lpn())
                    .param("contents", json.write(b.contents())).param("weight", b.weightLb())
                    .param("length", b.lengthIn()).param("width", b.widthIn()).param("height", b.heightIn())
                    .param("receivedAt", ts(b.receivedAt())).update();
        }
    }

    private InboundShipment withChildren(InboundShipment s) {
        List<InboundLine> lines = jdbc.sql("""
                select * from inbound_shipment_line where shipment_id = :id order by line_no
                """).param("id", s.id()).query((rs, n) -> new InboundLine(rs.getInt("line_no"), rs.getString("sku"),
                rs.getString("atin"), rs.getString("unit_label"), rs.getInt("expected_qty"),
                rs.getInt("received_good"), rs.getInt("received_damaged"), rs.getInt("inbound_open"),
                new ExpectedBatch(rs.getObject("case_pack", Integer.class), rs.getString("lot_no"),
                        rs.getObject("mfg_date", LocalDate.class), rs.getObject("exp_date", LocalDate.class))))
                .list();
        List<InboundBox> boxes = jdbc.sql("""
                select box_no, lpn, contents::text as contents, weight_lb, length_in, width_in, height_in, received_at
                from inbound_box where shipment_id = :id order by box_no
                """).param("id", s.id()).query((rs, n) -> new InboundBox(rs.getInt("box_no"), rs.getString("lpn"),
                json.read(rs.getString("contents"), ITEMS), rs.getBigDecimal("weight_lb"),
                rs.getBigDecimal("length_in"), rs.getBigDecimal("width_in"), rs.getBigDecimal("height_in"),
                instant(rs.getTimestamp("received_at")))).list();
        return new InboundShipment(s.id(), s.number(), s.source(), s.externalRef(), s.ownerOrg(), s.nodeId(),
                s.requestedNodeId(), s.assignmentReason(), s.name(), s.status(), s.shipFrom(), s.carrier(),
                s.trackingNumber(), s.trackingSource(), s.carrierLabelId(), s.labelCostMinor(), s.labelCurrency(),
                s.labelDocumentUrl(), s.expectedArrival(), s.receiptCount(), lines, boxes, s.createdBy(),
                s.createdAt(), s.shippedAt(), s.closedAt(), s.updatedAt(), s.version());
    }

    private Map<String, Object> params(InboundShipment s) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", s.id());
        p.put("number", s.number());
        p.put("source", s.source().name());
        p.put("externalRef", s.externalRef());
        p.put("owner", s.ownerOrg());
        p.put("node", s.nodeId());
        p.put("requestedNode", s.requestedNodeId());
        p.put("reason", s.assignmentReason());
        p.put("name", s.name());
        p.put("status", s.status().name());
        p.put("shipFrom", s.shipFrom() == null ? null : json.write(s.shipFrom()));
        p.put("carrier", s.carrier());
        p.put("tracking", s.trackingNumber());
        p.put("trackingSource", s.trackingSource());
        p.put("labelId", s.carrierLabelId());
        p.put("labelCost", s.labelCostMinor());
        p.put("labelCurrency", s.labelCurrency());
        p.put("labelUrl", s.labelDocumentUrl());
        p.put("eta", s.expectedArrival() == null ? null : Date.valueOf(s.expectedArrival()));
        p.put("receipts", s.receiptCount());
        p.put("createdBy", s.createdBy());
        p.put("createdAt", ts(s.createdAt()));
        p.put("shippedAt", ts(s.shippedAt()));
        p.put("closedAt", ts(s.closedAt()));
        p.put("updatedAt", ts(s.updatedAt()));
        return p;
    }

    private InboundShipment map(ResultSet rs, int row) throws SQLException {
        String shipFrom = rs.getString("ship_from");
        Date eta = rs.getDate("expected_arrival");
        long cost = rs.getLong("label_cost_minor");
        Long labelCost = rs.wasNull() ? null : cost;
        return new InboundShipment(rs.getObject("id", UUID.class), rs.getString("number"),
                InboundSource.valueOf(rs.getString("source")), rs.getString("external_ref"), rs.getString("owner_org"),
                rs.getString("node_id"), rs.getString("requested_node_id"), rs.getString("assignment_reason"),
                rs.getString("name"), InboundStatus.valueOf(rs.getString("status")),
                shipFrom == null ? null : json.read(shipFrom, PostalAddress.class), rs.getString("carrier"),
                rs.getString("tracking_number"), rs.getString("tracking_source"), rs.getString("carrier_label_id"),
                labelCost, rs.getString("label_currency"), rs.getString("label_document_url"),
                eta == null ? null : eta.toLocalDate(), rs.getInt("receipt_count"), List.of(), List.of(),
                rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("shipped_at")), instant(rs.getTimestamp("closed_at")),
                instant(rs.getTimestamp("updated_at")), rs.getLong("version"));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
