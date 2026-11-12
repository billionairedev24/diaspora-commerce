package com.afrotransact.identity.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.TourProgress;
import com.afrotransact.identity.domain.TourProgress.Status;
import com.afrotransact.identity.port.TourProgressRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTourProgressRepository implements TourProgressRepository {

    private static final RowMapper<TourProgress> ROW = (rs, n) -> new TourProgress(
            rs.getObject("user_id", UUID.class), rs.getString("tour_id"), rs.getInt("content_version"),
            Status.valueOf(rs.getString("status")), rs.getString("last_step"), Jdbc.instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    JdbcTourProgressRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TourProgress> findAll(UUID userId) {
        return jdbc.sql("select * from user_tour_progress where user_id = :u order by tour_id")
                .param("u", userId).query(ROW).list();
    }

    @Override
    public Optional<TourProgress> find(UUID userId, String tourId) {
        return jdbc.sql("select * from user_tour_progress where user_id = :u and tour_id = :t")
                .param("u", userId).param("t", tourId).query(ROW).optional();
    }

    @Override
    public long count(UUID userId) {
        return jdbc.sql("select count(*) from user_tour_progress where user_id = :u").param("u", userId)
                .query(Long.class).single();
    }

    @Override
    public void upsert(TourProgress p) {
        jdbc.sql("""
                insert into user_tour_progress (user_id, tour_id, content_version, status, last_step, updated_at)
                values (:u, :t, :v, :s, :step, :at)
                on conflict (user_id, tour_id) do update set content_version = excluded.content_version,
                    status = excluded.status, last_step = excluded.last_step, updated_at = excluded.updated_at
                """)
                .param("u", p.userId()).param("t", p.tourId()).param("v", p.contentVersion())
                .param("s", p.status().name()).param("step", p.lastStep()).param("at", Jdbc.ts(p.updatedAt()))
                .update();
    }

    @Override
    public boolean delete(UUID userId, String tourId) {
        return jdbc.sql("delete from user_tour_progress where user_id = :u and tour_id = :t")
                .param("u", userId).param("t", tourId).update() > 0;
    }

    @Override
    public int deleteAll(UUID userId) {
        return jdbc.sql("delete from user_tour_progress where user_id = :u").param("u", userId).update();
    }
}
