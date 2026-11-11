package com.afrotransact.bookings.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.port.ServiceAlertRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcServiceAlertRepository implements ServiceAlertRepository {

    private final JdbcClient jdbc;

    JdbcServiceAlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void open(Alert a) {
        jdbc.sql("""
                insert into service_alert (id, email, zip, city, state, lat, lon, category, locale, created_at)
                values (:id, :email, :zip, :city, :state, :lat, :lon, :category, :locale, :at)
                on conflict do nothing
                """)
                .param("id", a.id()).param("email", a.email()).param("zip", a.zip()).param("city", a.city())
                .param("state", a.state()).param("lat", a.lat()).param("lon", a.lon())
                .param("category", a.category()).param("locale", a.locale()).param("at", Sql.ts(a.createdAt()))
                .update();
    }

    @Override
    public List<Alert> openNear(double lat, double lon, int miles) {
        String near = Sql.HAVERSINE_MILES.replace("a.lat", "x.lat").replace("a.lon", "x.lon");
        return jdbc.sql("select * from service_alert x where x.notified_at is null and " + near + " <= :miles")
                .param("lat", lat).param("lon", lon).param("miles", miles)
                .query((rs, n) -> new Alert(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("zip"), rs.getString("city"), rs.getString("state"), rs.getDouble("lat"),
                        rs.getDouble("lon"), rs.getString("category"), rs.getString("locale"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public void markNotified(UUID id, Instant at) {
        jdbc.sql("update service_alert set notified_at = :at where id = :id").param("at", Sql.ts(at))
                .param("id", id).update();
    }
}
