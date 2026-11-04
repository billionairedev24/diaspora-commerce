package com.afrotransact.taxreturns.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.tax.LiabilityRow;
import com.afrotransact.taxreturns.domain.tax.TaxTransaction;
import com.afrotransact.taxreturns.domain.tax.TaxTransactionKind;
import com.afrotransact.taxreturns.port.TaxTransactionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTaxTransactionRepository implements TaxTransactionRepository {

    private final JdbcClient jdbc;

    JdbcTaxTransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(TaxTransaction t) {
        return jdbc.sql("""
                insert into tax_transaction (id, kind, order_id, order_number, line_id, source_ref, seller_org_id,
                                             jurisdiction, local_jurisdiction, postal_code, currency, taxable_minor,
                                             tax_minor, occurred_at, period)
                values (:id, :kind, :order, :orderNumber, :line, :source, :seller, :jurisdiction, :local, :postal,
                        :currency, :taxable, :tax, :at, :period)
                on conflict (kind, source_ref, line_id) do nothing
                """)
                .param("id", t.id()).param("kind", t.kind().name()).param("order", t.orderId())
                .param("orderNumber", t.orderNumber()).param("line", t.lineId()).param("source", t.sourceRef())
                .param("seller", t.sellerOrgId()).param("jurisdiction", t.jurisdiction())
                .param("local", t.localJurisdiction()).param("postal", t.postalCode())
                .param("currency", t.currency()).param("taxable", t.taxableMinor()).param("tax", t.taxMinor())
                .param("at", Sql.ts(t.occurredAt())).param("period", Date.valueOf(t.period()))
                .update() == 1;
    }

    @Override
    public Optional<TaxTransaction> find(TaxTransactionKind kind, String sourceRef, String lineId) {
        return jdbc.sql("""
                select * from tax_transaction where kind = :kind and source_ref = :source and line_id = :line
                """).param("kind", kind.name()).param("source", sourceRef).param("line", lineId)
                .query(JdbcTaxTransactionRepository::map).optional();
    }

    @Override
    public Optional<TaxTransaction> sale(UUID orderId, String lineId) {
        return jdbc.sql("""
                select * from tax_transaction where kind = 'SALE' and order_id = :order and line_id = :line
                """).param("order", orderId).param("line", lineId)
                .query(JdbcTaxTransactionRepository::map).optional();
    }

    @Override
    public int voidRefund(String sourceRef) {
        return jdbc.sql("update tax_transaction set voided = true where kind = 'REFUND' and source_ref = :s")
                .param("s", sourceRef).update();
    }

    @Override
    public List<TaxTransaction> byOrder(UUID orderId) {
        return jdbc.sql("select * from tax_transaction where order_id = :o and not voided order by occurred_at, line_id")
                .param("o", orderId).query(JdbcTaxTransactionRepository::map).list();
    }

    @Override
    public List<LiabilityRow> liability(YearMonth month, String sellerOrgId) {
        String sellerFilter = sellerOrgId == null ? "" : " and seller_org_id = :seller";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                select jurisdiction, currency,
                       count(distinct order_id) filter (where kind = 'SALE') as orders,
                       coalesce(sum(taxable_minor), 0) as gross,
                       coalesce(sum(taxable_minor) filter (where tax_minor <> 0), 0) as taxable,
                       coalesce(sum(tax_minor) filter (where kind = 'SALE'), 0) as collected,
                       coalesce(-sum(tax_minor) filter (where kind = 'REFUND'), 0) as refunded,
                       coalesce(sum(tax_minor), 0) as net
                from tax_transaction
                where period = :period and not voided""" + sellerFilter + """

                group by jurisdiction, currency
                order by jurisdiction, currency
                """).param("period", Date.valueOf(month.atDay(1)));
        if (sellerOrgId != null) {
            spec = spec.param("seller", sellerOrgId);
        }
        return spec.query((rs, n) -> new LiabilityRow(rs.getString("jurisdiction"), rs.getString("currency"),
                rs.getLong("orders"), rs.getLong("gross"), rs.getLong("taxable"), rs.getLong("collected"),
                rs.getLong("refunded"), rs.getLong("net"))).list();
    }

    @Override
    public List<SalesInto> salesInto(YearMonth through, int months, String currency) {
        return jdbc.sql("""
                select jurisdiction, coalesce(sum(taxable_minor), 0) as gross,
                       count(distinct order_id) filter (where kind = 'SALE') as orders
                from tax_transaction
                where period between :from and :to and currency = :cur and not voided
                group by jurisdiction
                """)
                .param("from", Date.valueOf(through.minusMonths(months - 1L).atDay(1)))
                .param("to", Date.valueOf(through.atDay(1))).param("cur", currency)
                .query((rs, n) -> new SalesInto(rs.getString("jurisdiction"), rs.getLong("gross"), rs.getLong("orders")))
                .list();
    }

    private static TaxTransaction map(ResultSet rs, int rowNum) throws SQLException {
        return new TaxTransaction(rs.getObject("id", UUID.class),
                TaxTransactionKind.valueOf(rs.getString("kind")), rs.getObject("order_id", UUID.class),
                rs.getString("order_number"), rs.getString("line_id"), rs.getString("source_ref"),
                rs.getString("seller_org_id"), rs.getString("jurisdiction"), rs.getString("local_jurisdiction"),
                rs.getString("postal_code"), rs.getString("currency"), rs.getLong("taxable_minor"),
                rs.getLong("tax_minor"), Sql.instant(rs, "occurred_at"));
    }
}
