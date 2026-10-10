package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.PaymentMethod;
import com.afrotransact.seller.domain.PaymentMethodKind;
import com.afrotransact.seller.port.PaymentMethodRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPaymentMethodRepository implements PaymentMethodRepository {

    private final JdbcClient jdbc;

    JdbcPaymentMethodRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(PaymentMethod m) {
        jdbc.sql("""
                insert into payment_method (id, org_id, stripe_payment_method_id, kind, brand, last4, exp_month,
                                            exp_year, bank_name, account_type, verified, receives_payouts, is_default,
                                            created_at)
                values (:id, :org, :pm, :kind, :brand, :last4, :expMonth, :expYear, :bank, :type, :verified,
                        :payouts, false, :at)
                on conflict (org_id, stripe_payment_method_id) do nothing
                """)
                .param("id", m.id()).param("org", m.orgId()).param("pm", m.stripePaymentMethodId())
                .param("kind", m.kind().name()).param("brand", m.brand()).param("last4", m.last4())
                .param("expMonth", m.expMonth()).param("expYear", m.expYear()).param("bank", m.bankName())
                .param("type", m.accountType()).param("verified", m.verified()).param("payouts", m.receivesPayouts())
                .param("at", Sql.ts(m.createdAt()))
                .update();
    }

    @Override
    public List<PaymentMethod> list(String orgId) {
        return jdbc.sql("select * from payment_method where org_id = :o order by is_default desc, created_at")
                .param("o", orgId).query(JdbcPaymentMethodRepository::map).list();
    }

    @Override
    public Optional<PaymentMethod> find(String orgId, UUID id) {
        return jdbc.sql("select * from payment_method where org_id = :o and id = :id").param("o", orgId)
                .param("id", id).query(JdbcPaymentMethodRepository::map).optional();
    }

    @Override
    public void makeDefault(String orgId, UUID id) {
        jdbc.sql("update payment_method set is_default = false where org_id = :o and is_default and id <> :id")
                .param("o", orgId).param("id", id).update();
        jdbc.sql("update payment_method set is_default = true where org_id = :o and id = :id").param("o", orgId)
                .param("id", id).update();
    }

    @Override
    public void delete(String orgId, UUID id) {
        jdbc.sql("delete from payment_method where org_id = :o and id = :id").param("o", orgId).param("id", id)
                .update();
    }

    private static PaymentMethod map(ResultSet rs, int n) throws SQLException {
        return new PaymentMethod(rs.getObject("id", UUID.class), rs.getString("org_id"),
                rs.getString("stripe_payment_method_id"), PaymentMethodKind.valueOf(rs.getString("kind")),
                rs.getString("brand"), rs.getString("last4"), Sql.integer(rs, "exp_month"),
                Sql.integer(rs, "exp_year"), rs.getString("bank_name"), rs.getString("account_type"),
                rs.getBoolean("verified"), rs.getBoolean("receives_payouts"), rs.getBoolean("is_default"),
                Sql.instant(rs, "created_at"));
    }
}
