package com.afrotransact.payments.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;

import com.afrotransact.payments.port.ConnectedAccountRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcConnectedAccountRepository implements ConnectedAccountRepository {

    private final JdbcClient jdbc;

    JdbcConnectedAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(String orgId, String stripeAccountId, Instant now) {
        jdbc.sql("""
                insert into connected_account (org_id, stripe_account_id, updated_at) values (:org, :acct, :now)
                on conflict (org_id) do update set stripe_account_id = excluded.stripe_account_id,
                    updated_at = excluded.updated_at
                """).param("org", orgId).param("acct", stripeAccountId).param("now", Sql.ts(now)).update();
    }

    @Override
    public Optional<String> find(String orgId) {
        return jdbc.sql("select stripe_account_id from connected_account where org_id = :org").param("org", orgId)
                .query(String.class).optional();
    }
}
