package com.afrotransact.retail.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.retail.domain.AsnLine;
import com.afrotransact.retail.domain.Carton;
import com.afrotransact.retail.domain.InboundShipment;
import com.afrotransact.retail.domain.LandedCharge;
import com.afrotransact.retail.domain.Milestone;
import com.afrotransact.retail.domain.ShipmentDocument;
import com.afrotransact.retail.domain.ShipmentStage;
import com.afrotransact.retail.domain.TransportMode;
import com.afrotransact.retail.port.Page;
import com.afrotransact.retail.port.ShipmentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcShipmentRepository implements ShipmentRepository {

    private static final TypeReference<List<AsnLine>> LINES = new TypeReference<>() { };
    private static final TypeReference<List<Carton>> CARTONS = new TypeReference<>() { };
    private static final TypeReference<List<ShipmentDocument>> DOCUMENTS = new TypeReference<>() { };
    private static final TypeReference<List<LandedCharge>> CHARGES = new TypeReference<>() { };
    private static final TypeReference<List<Milestone>> MILESTONES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcShipmentRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public InboundShipment insert(InboundShipment s) {
        jdbc.sql("""
                insert into inbound_shipment (id, reference, po_id, po_number, supplier_id, mode, carrier, tracking_ref,
                    bill_of_lading, origin_port, destination_port, destination_node_id, etd, eta, stage, lines,
                    cartons, documents, charges, milestones, note, created_at, updated_at, version)
                values (:id, :reference, :poId, :poNumber, :supplierId, :mode, :carrier, :tracking, :bl, :origin,
                    :destination, :node, :etd, :eta, :stage, cast(:lines as jsonb), cast(:cartons as jsonb),
                    cast(:documents as jsonb), cast(:charges as jsonb), cast(:milestones as jsonb), :note,
                    :createdAt, :updatedAt, 0)
                """)
                .params(params(s)).param("reference", s.reference()).param("poId", s.poId())
                .param("poNumber", s.poNumber()).param("supplierId", s.supplierId()).param("mode", s.mode().name())
                .param("node", s.destinationNodeId()).param("createdAt", Sql.ts(s.createdAt()))
                .update();
        return findById(s.id()).orElseThrow();
    }

    @Override
    public InboundShipment update(InboundShipment s) {
        int rows = jdbc.sql("""
                update inbound_shipment set carrier = :carrier, tracking_ref = :tracking, bill_of_lading = :bl,
                       origin_port = :origin, destination_port = :destination, etd = :etd, eta = :eta,
                       stage = :stage, lines = cast(:lines as jsonb), cartons = cast(:cartons as jsonb),
                       documents = cast(:documents as jsonb), charges = cast(:charges as jsonb),
                       milestones = cast(:milestones as jsonb), note = :note, updated_at = :updatedAt,
                       version = version + 1
                where id = :id and version = :version
                """)
                .params(params(s)).param("version", s.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("retail.concurrent-update", "Shipment " + s.reference() + " changed; reload");
        }
        return findById(s.id()).orElseThrow();
    }

    @Override
    public Optional<InboundShipment> findById(UUID id) {
        return jdbc.sql("select * from inbound_shipment where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<InboundShipment> findByReference(String reference) {
        return jdbc.sql("select * from inbound_shipment where reference = :r").param("r", reference)
                .query(this::map).optional();
    }

    @Override
    public List<InboundShipment> findByPo(UUID poId) {
        return jdbc.sql("select * from inbound_shipment where po_id = :po order by created_at").param("po", poId)
                .query(this::map).list();
    }

    @Override
    public Page<InboundShipment> page(ShipmentStage stage, UUID poId, int page, int size) {
        Sql.Where where = new Sql.Where().eq("stage", "stage", Sql.name(stage)).eq("po_id", "poId", poId);
        long total = jdbc.sql("select count(*) from inbound_shipment" + where.sql()).params(where.params())
                .query(Long.class).single();
        List<InboundShipment> items = jdbc.sql("select * from inbound_shipment" + where.sql()
                        + " order by eta nulls last, reference limit :limit offset :offset")
                .params(where.params()).param("limit", size).param("offset", (long) page * size)
                .query(this::map).list();
        return new Page<>(items, total, page, size);
    }

    private Map<String, Object> params(InboundShipment s) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", s.id());
        p.put("carrier", s.carrier());
        p.put("tracking", s.trackingRef());
        p.put("bl", s.billOfLading());
        p.put("origin", s.originPort());
        p.put("destination", s.destinationPort());
        p.put("etd", s.etd());
        p.put("eta", s.eta());
        p.put("stage", s.stage().name());
        p.put("lines", json.write(s.lines()));
        p.put("cartons", json.write(s.cartons()));
        p.put("documents", json.write(s.documents()));
        p.put("charges", json.write(s.charges()));
        p.put("milestones", json.write(s.milestones()));
        p.put("note", s.note());
        p.put("updatedAt", Sql.ts(s.updatedAt()));
        return p;
    }

    private InboundShipment map(ResultSet rs, int rowNum) throws SQLException {
        return new InboundShipment(rs.getObject("id", UUID.class), rs.getString("reference"),
                rs.getObject("po_id", UUID.class), rs.getString("po_number"), rs.getObject("supplier_id", UUID.class),
                TransportMode.valueOf(rs.getString("mode")), rs.getString("carrier"), rs.getString("tracking_ref"),
                rs.getString("bill_of_lading"), rs.getString("origin_port"), rs.getString("destination_port"),
                rs.getString("destination_node_id"), Sql.date(rs, "etd"), Sql.date(rs, "eta"),
                ShipmentStage.valueOf(rs.getString("stage")), json.list(rs.getString("lines"), LINES),
                json.list(rs.getString("cartons"), CARTONS), json.list(rs.getString("documents"), DOCUMENTS),
                json.list(rs.getString("charges"), CHARGES), json.list(rs.getString("milestones"), MILESTONES),
                rs.getString("note"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
