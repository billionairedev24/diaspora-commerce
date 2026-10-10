package com.afrotransact.content.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.placement.Slot;
import com.afrotransact.content.domain.placement.SlotKey;
import com.afrotransact.content.port.SlotRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSlotRepository implements SlotRepository {

    private static final String SELECT = """
            select key, name, description, max_items, size_spec, payload_schema::text as schema from slot
            """;

    private final JdbcClient jdbc;

    JdbcSlotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Slot> findAll() {
        return jdbc.sql(SELECT + " order by sort_order, key").query(JdbcSlotRepository::map).list();
    }

    @Override
    public Optional<Slot> find(SlotKey key) {
        return jdbc.sql(SELECT + " where key = :key").param("key", key.key()).query(JdbcSlotRepository::map).optional();
    }

    private static Slot map(ResultSet rs, int row) throws SQLException {
        return new Slot(SlotKey.fromKey(rs.getString("key")), rs.getString("name"), rs.getString("description"),
                rs.getInt("max_items"), rs.getString("size_spec"), rs.getString("schema"));
    }
}
