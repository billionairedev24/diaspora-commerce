package com.afrotransact.retail.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.retail.domain.FxRate;
import com.afrotransact.retail.domain.InvoiceLine;
import com.afrotransact.retail.domain.InvoiceStatus;
import com.afrotransact.retail.domain.MatchResult;
import com.afrotransact.retail.domain.SupplierInvoice;
import com.afrotransact.retail.port.InvoiceRepository;
import com.afrotransact.retail.port.Page;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcInvoiceRepository implements InvoiceRepository {

    private static final TypeReference<List<InvoiceLine>> LINES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcInvoiceRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public SupplierInvoice insert(SupplierInvoice i) {
        try {
            jdbc.sql("""
                    insert into supplier_invoice (id, supplier_id, po_id, po_number, invoice_number, invoice_date,
                        currency, fx_rate, fx_base_currency, fx_date, lines, total_minor, status, match,
                        resolution_note, resolved_by, journal_entry_id, received_by, created_at, updated_at, version)
                    values (:id, :supplierId, :poId, :poNumber, :number, :invoiceDate, :currency, :fxRate, :fxBase,
                        :fxDate, cast(:lines as jsonb), :total, :status, cast(:match as jsonb), :note, :resolvedBy,
                        :journal, :receivedBy, :createdAt, :updatedAt, 0)
                    """)
                    .params(params(i)).param("supplierId", i.supplierId()).param("poId", i.poId())
                    .param("poNumber", i.poNumber()).param("number", i.invoiceNumber())
                    .param("invoiceDate", i.invoiceDate()).param("currency", i.currency())
                    .param("fxRate", i.fx().unitsPerBase()).param("fxBase", i.fx().baseCurrency())
                    .param("fxDate", i.fx().asOf()).param("lines", json.write(i.lines()))
                    .param("total", i.totalMinor()).param("receivedBy", i.receivedBy())
                    .param("createdAt", Sql.ts(i.createdAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("retail.invoice-duplicate", "Invoice " + i.invoiceNumber()
                    + " of this supplier is already registered");
        }
        return findById(i.id()).orElseThrow();
    }

    @Override
    public SupplierInvoice update(SupplierInvoice i) {
        int rows = jdbc.sql("""
                update supplier_invoice set status = :status, match = cast(:match as jsonb),
                       resolution_note = :note, resolved_by = :resolvedBy, journal_entry_id = :journal,
                       updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .params(params(i)).param("version", i.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("retail.concurrent-update", "Invoice " + i.invoiceNumber()
                    + " changed; reload");
        }
        return findById(i.id()).orElseThrow();
    }

    @Override
    public Optional<SupplierInvoice> findById(UUID id) {
        return jdbc.sql("select * from supplier_invoice where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<SupplierInvoice> findBySupplierAndNumber(UUID supplierId, String invoiceNumber) {
        return jdbc.sql("select * from supplier_invoice where supplier_id = :s and invoice_number = :n")
                .param("s", supplierId).param("n", invoiceNumber).query(this::map).optional();
    }

    @Override
    public List<SupplierInvoice> findByPo(UUID poId) {
        return jdbc.sql("select * from supplier_invoice where po_id = :po order by created_at").param("po", poId)
                .query(this::map).list();
    }

    @Override
    public Page<SupplierInvoice> page(InvoiceStatus status, UUID poId, int page, int size) {
        Sql.Where where = new Sql.Where().eq("status", "status", Sql.name(status)).eq("po_id", "poId", poId);
        long total = jdbc.sql("select count(*) from supplier_invoice" + where.sql()).params(where.params())
                .query(Long.class).single();
        List<SupplierInvoice> items = jdbc.sql("select * from supplier_invoice" + where.sql()
                        + " order by created_at desc limit :limit offset :offset")
                .params(where.params()).param("limit", size).param("offset", (long) page * size)
                .query(this::map).list();
        return new Page<>(items, total, page, size);
    }

    private Map<String, Object> params(SupplierInvoice i) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", i.id());
        p.put("status", i.status().name());
        p.put("match", json.write(i.match()));
        p.put("note", i.resolutionNote());
        p.put("resolvedBy", i.resolvedBy());
        p.put("journal", i.journalEntryId());
        p.put("updatedAt", Sql.ts(i.updatedAt()));
        return p;
    }

    private SupplierInvoice map(ResultSet rs, int rowNum) throws SQLException {
        String currency = rs.getString("currency");
        FxRate fx = new FxRate(currency, rs.getString("fx_base_currency"),
                rs.getBigDecimal("fx_rate").stripTrailingZeros(), Sql.date(rs, "fx_date"));
        return new SupplierInvoice(rs.getObject("id", UUID.class), rs.getObject("supplier_id", UUID.class),
                rs.getObject("po_id", UUID.class), rs.getString("po_number"), rs.getString("invoice_number"),
                Sql.date(rs, "invoice_date"), currency, fx, json.list(rs.getString("lines"), LINES),
                InvoiceStatus.valueOf(rs.getString("status")), json.read(rs.getString("match"), MatchResult.class),
                rs.getString("resolution_note"), rs.getString("resolved_by"),
                rs.getObject("journal_entry_id", UUID.class), rs.getString("received_by"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
