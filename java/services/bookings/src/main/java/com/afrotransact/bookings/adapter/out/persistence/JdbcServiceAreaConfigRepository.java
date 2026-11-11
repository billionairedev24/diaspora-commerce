package com.afrotransact.bookings.adapter.out.persistence;

import java.util.List;

import com.afrotransact.bookings.domain.AreaPolicy;
import com.afrotransact.bookings.domain.ServiceZone;
import com.afrotransact.bookings.port.ServiceAreaConfigRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcServiceAreaConfigRepository implements ServiceAreaConfigRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcServiceAreaConfigRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<ServiceZone> zones() {
        return jdbc.sql("select key, name, cities::text as cities from service_zone order by name")
                .query((rs, n) -> new ServiceZone(rs.getString("key"), rs.getString("name"),
                        json.zoneCities(rs.getString("cities"))))
                .list();
    }

    @Override
    public void saveZone(ServiceZone z) {
        jdbc.sql("""
                insert into service_zone (key, name, cities, updated_at) values (:key, :name, cast(:cities as jsonb), now())
                on conflict (key) do update set name = :name, cities = cast(:cities as jsonb), updated_at = now()
                """).param("key", z.key()).param("name", z.name()).param("cities", json.write(z.cities())).update();
    }

    @Override
    public boolean deleteZone(String key) {
        return jdbc.sql("delete from service_zone where key = :key").param("key", key).update() > 0;
    }

    @Override
    public AreaPolicy policy() {
        return jdbc.sql("select policy::text from service_area_policy where id = 1").query(String.class).optional()
                .map(s -> json.read(s, AreaPolicy.class)).orElseGet(AreaPolicy::defaults);
    }

    @Override
    public void savePolicy(AreaPolicy p) {
        jdbc.sql("""
                insert into service_area_policy (id, policy, updated_at) values (1, cast(:p as jsonb), now())
                on conflict (id) do update set policy = cast(:p as jsonb), updated_at = now()
                """).param("p", json.write(p)).update();
    }

    @Override
    public void audit(String actor, String subject, String reason, Object change) {
        jdbc.sql("insert into service_area_audit (actor, subject, reason, change) values (:a, :s, :r, cast(:c as jsonb))")
                .param("a", actor).param("s", subject).param("r", reason).param("c", json.write(change)).update();
    }

    @Override
    public List<AuditEntry> auditTrail(int limit) {
        return jdbc.sql("select at, actor, subject, reason from service_area_audit order by id desc limit :n")
                .param("n", limit).query((rs, n) -> new AuditEntry(rs.getTimestamp("at").toInstant().toString(),
                        rs.getString("actor"), rs.getString("subject"), rs.getString("reason"))).list();
    }
}
