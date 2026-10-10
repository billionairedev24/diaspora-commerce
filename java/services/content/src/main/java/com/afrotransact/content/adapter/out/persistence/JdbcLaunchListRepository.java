package com.afrotransact.content.adapter.out.persistence;

import java.util.UUID;

import com.afrotransact.content.domain.EmailAddress;
import com.afrotransact.content.domain.newsletter.LaunchAudience;
import com.afrotransact.content.domain.newsletter.LaunchListEntry;
import com.afrotransact.content.port.LaunchListRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLaunchListRepository implements LaunchListRepository {

    private final JdbcClient jdbc;

    JdbcLaunchListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LaunchListEntry upsert(LaunchListEntry e) {
        return jdbc.sql("""
                insert into launch_list_entry (id, email, country, audience, shopping_updates, created_at, updated_at)
                values (:id, :email, :country, :audience, :updates, :now, :now)
                on conflict (email, country, audience)
                do update set shopping_updates = excluded.shopping_updates, updated_at = excluded.updated_at
                returning id, email, country, audience, shopping_updates, created_at
                """)
                .param("id", e.id())
                .param("email", e.email().value())
                .param("country", e.country())
                .param("audience", e.audience().name())
                .param("updates", e.shoppingUpdates())
                .param("now", Timestamps.of(e.createdAt()))
                .query((rs, n) -> new LaunchListEntry(rs.getObject("id", UUID.class),
                        new EmailAddress(rs.getString("email")), rs.getString("country"),
                        LaunchAudience.valueOf(rs.getString("audience")), rs.getBoolean("shopping_updates"),
                        Timestamps.instant(rs, "created_at")))
                .single();
    }
}
