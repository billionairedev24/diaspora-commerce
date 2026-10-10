package com.afrotransact.identity.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.FederatedIdentity;
import com.afrotransact.identity.port.FederatedIdentityRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcFederatedIdentityRepository implements FederatedIdentityRepository {

    private final JdbcClient jdbc;

    JdbcFederatedIdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<FederatedIdentity> find(String provider, String subject) {
        return jdbc.sql("select * from federated_identity where provider = :p and subject = :s")
                .param("p", provider).param("s", subject)
                .query(JdbcFederatedIdentityRepository::map).optional();
    }

    @Override
    public List<FederatedIdentity> forUser(UUID userId) {
        return jdbc.sql("select * from federated_identity where user_id = :u order by provider")
                .param("u", userId).query(JdbcFederatedIdentityRepository::map).list();
    }

    @Override
    public void link(FederatedIdentity f) {
        jdbc.sql("""
                insert into federated_identity (provider, subject, user_id, email, linked_at)
                values (:p, :s, :u, :e, :at)
                on conflict (user_id, provider) do update set subject = excluded.subject, email = excluded.email,
                    linked_at = excluded.linked_at
                """)
                .param("p", f.provider()).param("s", f.subject()).param("u", f.userId()).param("e", f.email())
                .param("at", Jdbc.ts(f.linkedAt()))
                .update();
    }

    @Override
    public boolean unlink(UUID userId, String provider) {
        return jdbc.sql("delete from federated_identity where user_id = :u and provider = :p")
                .param("u", userId).param("p", provider).update() == 1;
    }

    private static FederatedIdentity map(ResultSet rs, int n) throws SQLException {
        return new FederatedIdentity(rs.getString("provider"), rs.getString("subject"),
                rs.getObject("user_id", UUID.class), rs.getString("email"), Jdbc.instant(rs, "linked_at"));
    }
}
