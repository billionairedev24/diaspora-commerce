package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.payments.domain.PaymentMethodType;
import com.afrotransact.payments.domain.SavedPaymentMethod;
import com.afrotransact.payments.port.WalletRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcWalletRepository implements WalletRepository {

    private final JdbcClient jdbc;

    JdbcWalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertCustomer(String customerId, String stripeCustomerId, Instant now) {
        return jdbc.sql("""
                insert into stripe_customer (customer_id, stripe_customer_id, created_at) values (:c, :s, :now)
                on conflict (customer_id) do nothing
                """).param("c", customerId).param("s", stripeCustomerId).param("now", Sql.ts(now)).update() == 1;
    }

    @Override
    public Optional<StripeCustomer> findCustomer(String customerId) {
        return jdbc.sql("select * from stripe_customer where customer_id = :c").param("c", customerId)
                .query(JdbcWalletRepository::customer).optional();
    }

    @Override
    public Optional<StripeCustomer> lockCustomer(String customerId) {
        return jdbc.sql("select * from stripe_customer where customer_id = :c for update").param("c", customerId)
                .query(JdbcWalletRepository::customer).optional();
    }

    @Override
    public Optional<StripeCustomer> findByStripeCustomer(String stripeCustomerId) {
        return jdbc.sql("select * from stripe_customer where stripe_customer_id = :s").param("s", stripeCustomerId)
                .query(JdbcWalletRepository::customer).optional();
    }

    @Override
    public void setDefault(String customerId, String paymentMethodId) {
        jdbc.sql("update stripe_customer set default_payment_method_id = :pm where customer_id = :c")
                .param("pm", paymentMethodId).param("c", customerId).update();
    }

    @Override
    public void upsertMethod(SavedPaymentMethod m) {
        jdbc.sql("""
                insert into customer_payment_method (id, customer_id, type, brand, last4, exp_month, exp_year,
                    bank_name, account_type, wallet, verified, created_at)
                values (:id, :c, :type, :brand, :last4, :em, :ey, :bank, :acct, :wallet, :verified, :at)
                on conflict (id) do update set brand = excluded.brand, last4 = excluded.last4,
                    exp_month = excluded.exp_month, exp_year = excluded.exp_year, bank_name = excluded.bank_name,
                    account_type = excluded.account_type,
                    wallet = coalesce(excluded.wallet, customer_payment_method.wallet),
                    verified = customer_payment_method.verified or excluded.verified, detached_at = null
                """)
                .param("id", m.id()).param("c", m.customerId()).param("type", m.type().name())
                .param("brand", m.brand()).param("last4", m.last4()).param("em", m.expMonth())
                .param("ey", m.expYear()).param("bank", m.bankName()).param("acct", m.accountType())
                .param("wallet", m.wallet()).param("verified", m.verified()).param("at", Sql.ts(m.createdAt()))
                .update();
    }

    @Override
    public List<SavedPaymentMethod> methods(String customerId) {
        return jdbc.sql("select * from customer_payment_method where customer_id = :c and detached_at is null "
                        + "order by created_at, id")
                .param("c", customerId).query(JdbcWalletRepository::method).list();
    }

    @Override
    public Optional<SavedPaymentMethod> findMethod(String paymentMethodId) {
        return jdbc.sql("select * from customer_payment_method where id = :id and detached_at is null")
                .param("id", paymentMethodId).query(JdbcWalletRepository::method).optional();
    }

    @Override
    public void markDetached(String paymentMethodId, Instant now) {
        jdbc.sql("update customer_payment_method set detached_at = :now where id = :id and detached_at is null")
                .param("now", Sql.ts(now)).param("id", paymentMethodId).update();
    }

    private static StripeCustomer customer(ResultSet rs, int n) throws SQLException {
        return new StripeCustomer(rs.getString("customer_id"), rs.getString("stripe_customer_id"),
                rs.getString("default_payment_method_id"));
    }

    private static SavedPaymentMethod method(ResultSet rs, int n) throws SQLException {
        return new SavedPaymentMethod(rs.getString("id"), rs.getString("customer_id"),
                PaymentMethodType.valueOf(rs.getString("type")), rs.getString("brand"), rs.getString("last4"),
                rs.getObject("exp_month", Integer.class), rs.getObject("exp_year", Integer.class),
                rs.getString("bank_name"), rs.getString("account_type"), rs.getString("wallet"),
                rs.getBoolean("verified"),
                Sql.instant(rs, "created_at"));
    }
}
