package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.WaitlistEntry;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.port.PreorderWaitlistRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPreorderWaitlistRepository implements PreorderWaitlistRepository {

    private final JdbcClient jdbc;

    JdbcPreorderWaitlistRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WaitlistEntry insert(WaitlistEntry e) {
        try {
            jdbc.sql("""
                    insert into preorder_waitlist (id, country, region, city, email, source, active, unsubscribe_token,
                        created_at, updated_at, version)
                    values (:id, :country, :region, :city, :email, :source, :active, :token, :createdAt, :updatedAt, 0)
                    """)
                    .param("id", e.id()).param("country", e.area().country()).param("region", e.area().region())
                    .param("city", e.area().city()).param("email", e.email()).param("source", e.source())
                    .param("active", e.active()).param("token", e.unsubscribeToken())
                    .param("createdAt", Sql.ts(e.createdAt())).param("updatedAt", Sql.ts(e.updatedAt())).update();
        } catch (DuplicateKeyException ex) {
            throw new DuplicateEntryException(e.email() + " is already on the waitlist of " + e.area(), ex);
        }
        return byId(e.id());
    }

    @Override
    public WaitlistEntry update(WaitlistEntry e) {
        int rows = jdbc.sql("""
                update preorder_waitlist set source = :source, active = :active, updated_at = :updatedAt,
                    version = version + 1
                where id = :id and version = :version
                """).param("source", e.source()).param("active", e.active()).param("updatedAt", Sql.ts(e.updatedAt()))
                .param("id", e.id()).param("version", e.version()).update();
        if (rows == 0) {
            throw new ConcurrentUpdateException("Waitlist entry changed; retry");
        }
        return byId(e.id());
    }

    @Override
    public Optional<WaitlistEntry> find(String email, AreaKey area) {
        return jdbc.sql("""
                select * from preorder_waitlist where email = :email and country = :country and region = :region
                    and lower(coalesce(city, '')) = lower(coalesce(cast(:city as varchar), ''))
                """).param("email", email).param("country", area.country()).param("region", area.region())
                .param("city", area.city()).query(JdbcPreorderWaitlistRepository::map).optional();
    }

    @Override
    public Optional<WaitlistEntry> findByUnsubscribeToken(String token) {
        return jdbc.sql("select * from preorder_waitlist where unsubscribe_token = :t").param("t", token)
                .query(JdbcPreorderWaitlistRepository::map).optional();
    }

    @Override
    public List<WaitlistEntry> findActive(String country) {
        return jdbc.sql("select * from preorder_waitlist where active and country = :c order by email, id")
                .param("c", country).query(JdbcPreorderWaitlistRepository::map).list();
    }

    private WaitlistEntry byId(UUID id) {
        return jdbc.sql("select * from preorder_waitlist where id = :id").param("id", id)
                .query(JdbcPreorderWaitlistRepository::map).single();
    }

    private static WaitlistEntry map(ResultSet rs, int rowNum) throws SQLException {
        return new WaitlistEntry(rs.getObject("id", UUID.class),
                new AreaKey(rs.getString("country"), rs.getString("region"), rs.getString("city")),
                rs.getString("email"), rs.getString("source"), rs.getBoolean("active"),
                rs.getString("unsubscribe_token"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
