package com.afrotransact.seller.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;

import com.afrotransact.seller.port.BillingCustomerRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcBillingCustomerRepository implements BillingCustomerRepository {

    private final JdbcClient jdbc;

    JdbcBillingCustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> find(String orgId) {
        return jdbc.sql("select stripe_customer_id from billing_customer where org_id = :o").param("o", orgId)
                .query(String.class).optional();
    }

    @Override
    public String save(String orgId, String stripeCustomerId, Instant at) {
        jdbc.sql("insert into billing_customer (org_id, stripe_customer_id, created_at) values (:o, :c, :at) "
                + "on conflict (org_id) do nothing").param("o", orgId).param("c", stripeCustomerId)
                .param("at", Sql.ts(at)).update();
        return find(orgId).orElse(stripeCustomerId);
    }
}
