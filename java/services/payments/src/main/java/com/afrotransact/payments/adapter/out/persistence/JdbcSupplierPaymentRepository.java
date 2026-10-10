package com.afrotransact.payments.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.SupplierPayment;
import com.afrotransact.payments.domain.SupplierPaymentStatus;
import com.afrotransact.payments.port.SupplierPaymentRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcSupplierPaymentRepository implements SupplierPaymentRepository {

    private static final TypeReference<List<Map<String, Object>>> INVOICES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcSupplierPaymentRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean insert(SupplierPayment p) {
        return jdbc.sql("""
                insert into supplier_payment (id, po_id, po_number, supplier_id, supplier_code, currency, amount_minor,
                    invoices, status, due_on, payable_journal_id, payment_journal_id, payment_reference, hold_reason,
                    paid_by, source_event_id, scheduled_at, paid_at, updated_at, version)
                values (:id, :po, :poNumber, :supplier, :supplierCode, :currency, :amount, cast(:invoices as jsonb),
                    :status, :due, :payableJournal, :paymentJournal, :reference, :holdReason, :paidBy, :event,
                    :scheduled, :paid, :updated, 0)
                on conflict (po_id) do nothing
                """)
                .param("id", p.id()).param("po", p.poId()).param("poNumber", p.poNumber())
                .param("supplier", p.supplierId()).param("supplierCode", p.supplierCode())
                .param("currency", p.amount().currency()).param("amount", p.amount().minor())
                .param("invoices", json.write(invoices(p))).param("status", p.status().name())
                .param("due", Date.valueOf(p.dueOn())).param("payableJournal", p.payableJournal())
                .param("paymentJournal", p.paymentJournal()).param("reference", p.paymentReference())
                .param("holdReason", p.holdReason()).param("paidBy", p.paidBy()).param("event", p.sourceEventId())
                .param("scheduled", Sql.ts(p.scheduledAt())).param("paid", Sql.ts(p.paidAt()))
                .param("updated", Sql.ts(p.updatedAt()))
                .update() == 1;
    }

    @Override
    public Optional<SupplierPayment> find(UUID id) {
        return jdbc.sql("select * from supplier_payment where id = :id").param("id", id).query(this::row).optional();
    }

    @Override
    public Optional<SupplierPayment> lock(UUID id) {
        return jdbc.sql("select * from supplier_payment where id = :id for update").param("id", id)
                .query(this::row).optional();
    }

    @Override
    public SupplierPayment update(SupplierPayment p) {
        int updated = jdbc.sql("""
                update supplier_payment set status = :status, payment_journal_id = :paymentJournal,
                    payment_reference = :reference, hold_reason = :holdReason, paid_by = :paidBy, paid_at = :paid,
                    updated_at = :updated, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", p.status().name()).param("paymentJournal", p.paymentJournal())
                .param("reference", p.paymentReference()).param("holdReason", p.holdReason())
                .param("paidBy", p.paidBy()).param("paid", Sql.ts(p.paidAt())).param("updated", Sql.ts(p.updatedAt()))
                .param("id", p.id()).param("version", p.version())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Supplier payment " + p.id() + " was changed concurrently");
        }
        return find(p.id()).orElseThrow();
    }

    @Override
    public List<SupplierPayment> list(Optional<SupplierPaymentStatus> status, Optional<UUID> supplierId,
                                      LocalDate dueBy, int limit) {
        StringBuilder sql = new StringBuilder("select * from supplier_payment where true");
        status.ifPresent(s -> sql.append(" and status = :status"));
        supplierId.ifPresent(s -> sql.append(" and supplier_id = :supplier"));
        if (dueBy != null) {
            sql.append(" and due_on <= :dueBy");
        }
        sql.append(" order by due_on desc, scheduled_at desc, id limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (status.isPresent()) {
            spec = spec.param("status", status.get().name());
        }
        if (supplierId.isPresent()) {
            spec = spec.param("supplier", supplierId.get());
        }
        if (dueBy != null) {
            spec = spec.param("dueBy", Date.valueOf(dueBy));
        }
        return spec.query(this::row).list();
    }

    private static List<Map<String, Object>> invoices(SupplierPayment p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SupplierPayment.Invoice i : p.invoices()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("invoiceId", i.invoiceId() == null ? null : i.invoiceId().toString());
            m.put("invoiceNumber", i.invoiceNumber());
            m.put("currency", i.amount().currency());
            m.put("amountMinor", i.amount().minor());
            out.add(m);
        }
        return out;
    }

    private SupplierPayment row(ResultSet rs, int n) throws SQLException {
        String currency = rs.getString("currency");
        List<SupplierPayment.Invoice> invoices = new ArrayList<>();
        for (Map<String, Object> m : json.read(rs.getString("invoices"), INVOICES)) {
            Object id = m.get("invoiceId");
            Object amount = m.get("amountMinor");
            Object cur = m.get("currency");
            invoices.add(new SupplierPayment.Invoice(id == null ? null : UUID.fromString(id.toString()),
                    m.get("invoiceNumber") == null ? null : m.get("invoiceNumber").toString(),
                    Money.of(amount instanceof Number num ? num.longValue() : 0L,
                            cur == null ? currency : cur.toString())));
        }
        return new SupplierPayment(rs.getObject("id", UUID.class), rs.getObject("po_id", UUID.class),
                rs.getString("po_number"), rs.getObject("supplier_id", UUID.class), rs.getString("supplier_code"),
                Money.of(rs.getLong("amount_minor"), currency), invoices,
                SupplierPaymentStatus.valueOf(rs.getString("status")), rs.getObject("due_on", LocalDate.class),
                rs.getObject("payable_journal_id", UUID.class), rs.getObject("payment_journal_id", UUID.class),
                rs.getString("payment_reference"), rs.getString("hold_reason"), rs.getString("paid_by"),
                rs.getObject("source_event_id", UUID.class), Sql.instant(rs, "scheduled_at"),
                Sql.instant(rs, "paid_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
