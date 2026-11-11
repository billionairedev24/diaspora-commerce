package com.afrotransact.content.adapter.out.persistence;

import java.sql.Date;
import java.time.LocalDate;

import com.afrotransact.content.port.PlacementStatsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPlacementStatsRepository implements PlacementStatsRepository {

    private final JdbcClient jdbc;

    JdbcPlacementStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean add(String placementId, LocalDate day, long impressions, long clicks) {
        return jdbc.sql("""
                insert into placement_stat (placement_id, day, impressions, clicks)
                select id, :day, :imp, :clicks from placement where id = :id
                on conflict (placement_id, day) do update set
                    impressions = placement_stat.impressions + excluded.impressions,
                    clicks = placement_stat.clicks + excluded.clicks
                """)
                .param("id", placementId).param("day", Date.valueOf(day)).param("imp", impressions)
                .param("clicks", clicks).update() > 0;
    }
}
