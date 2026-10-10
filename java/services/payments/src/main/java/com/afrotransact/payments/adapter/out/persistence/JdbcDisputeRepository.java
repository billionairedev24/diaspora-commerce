package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Dispute;
import com.afrotransact.payments.domain.DisputeAllocation;
import com.afrotransact.payments.domain.DisputeStatus;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.DisputeRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcDisputeRepository implements DisputeRepository {

    private static final TypeReference<List<AllocationRow>> ALLOCATIONS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcDisputeRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean insert(Dispute d) {
        return jdbc.sql("""
                insert into dispute (id, payment_id, order_id, charge_id, currency, amount_minor, fee_minor, status,
                    reason, allocations, opened_at, closed_at)
                values (:id, :pid, :orderId, :charge, :currency, :amount, :fee, :status, :reason,
                    cast(:allocations as jsonb), :openedAt, :closedAt)
                on conflict (id) do nothing
                """)
                .param("id", d.id()).param("pid", d.paymentId()).param("orderId", d.orderId())
                .param("charge", d.chargeId()).param("currency", d.amount().currency())
                .param("amount", d.amount().minor()).param("fee", d.fee().minor())
                .param("status", d.status().name()).param("reason", d.reason())
                .param("allocations", json.write(d.allocations().stream()
                        .map(a -> new AllocationRow(a.recipientId(), a.amount().minor())).toList()))
                .param("openedAt", Sql.ts(d.openedAt())).param("closedAt", Sql.ts(d.closedAt()))
                .update() == 1;
    }

    @Override
    public Optional<Dispute> lock(String id) {
        return jdbc.sql("select *, allocations::text as allocations_json from dispute where id = :id for update")
                .param("id", id).query(this::row).optional();
    }

    @Override
    public void update(Dispute d) {
        jdbc.sql("update dispute set status = :status, closed_at = :closedAt where id = :id")
                .param("status", d.status().name()).param("closedAt", Sql.ts(d.closedAt())).param("id", d.id())
                .update();
    }

    @Override
    public List<Dispute> ofPayment(UUID paymentId) {
        return jdbc.sql("select *, allocations::text as allocations_json from dispute where payment_id = :p "
                        + "order by opened_at")
                .param("p", paymentId).query(this::row).list();
    }

    private Dispute row(ResultSet rs, int n) throws SQLException {
        String currency = rs.getString("currency");
        List<DisputeAllocation> allocations = json.read(rs.getString("allocations_json"), ALLOCATIONS).stream()
                .map(a -> new DisputeAllocation(a.recipientId(), Money.of(a.amountMinor(), currency))).toList();
        return new Dispute(rs.getString("id"), rs.getObject("payment_id", UUID.class), rs.getString("order_id"),
                rs.getString("charge_id"), Money.of(rs.getLong("amount_minor"), currency),
                Money.of(rs.getLong("fee_minor"), currency), DisputeStatus.valueOf(rs.getString("status")),
                rs.getString("reason"), allocations, Sql.instant(rs, "opened_at"), Sql.instant(rs, "closed_at"));
    }

    /** Stored allocation. */
    record AllocationRow(String recipientId, long amountMinor) {
    }
}
