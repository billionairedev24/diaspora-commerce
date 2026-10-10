package com.afrotransact.seller.adapter.out.persistence;

import com.afrotransact.seller.domain.LaunchListEntry;
import com.afrotransact.seller.port.LaunchListRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLaunchListRepository implements LaunchListRepository {

    private final JdbcClient jdbc;

    JdbcLaunchListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean upsert(LaunchListEntry e) {
        Boolean inserted = jdbc.sql("""
                insert into launch_list (id, country, email, shopping_updates, created_at, updated_at)
                values (:id, :country, :email, :updates, :at, :at)
                on conflict (country, email) do update set shopping_updates = excluded.shopping_updates,
                                                           updated_at = excluded.updated_at
                returning (xmax = 0) as inserted
                """)
                .param("id", e.id()).param("country", e.country()).param("email", e.email())
                .param("updates", e.shoppingUpdates()).param("at", Sql.ts(e.createdAt()))
                .query(Boolean.class).single();
        return Boolean.TRUE.equals(inserted);
    }
}
