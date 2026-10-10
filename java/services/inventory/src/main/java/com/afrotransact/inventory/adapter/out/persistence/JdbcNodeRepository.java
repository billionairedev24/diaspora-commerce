package com.afrotransact.inventory.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.NodeKind;
import com.afrotransact.inventory.port.NodeRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcNodeRepository implements NodeRepository {

    private static final String SELECT = """
            select id, name, kind, country, timezone, temperature_zones::text as zones, active from node
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcNodeRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Node> findAll() {
        return jdbc.sql(SELECT + " order by id").query(this::map).list();
    }

    @Override
    public Optional<Node> findById(String id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Node insert(Node n) {
        jdbc.sql("""
                insert into node (id, name, kind, country, timezone, temperature_zones, active)
                values (:id, :name, :kind, :country, :tz, cast(:zones as jsonb), :active)
                """)
                .param("id", n.id())
                .param("name", n.name())
                .param("kind", n.kind().name())
                .param("country", n.country())
                .param("tz", n.timezone())
                .param("zones", json.write(n.temperatureZones()))
                .param("active", n.active())
                .update();
        return findById(n.id()).orElseThrow();
    }

    private Node map(ResultSet rs, int row) throws SQLException {
        return new Node(rs.getString("id"), rs.getString("name"), NodeKind.valueOf(rs.getString("kind")),
                rs.getString("country"), rs.getString("timezone"), json.strings(rs.getString("zones")),
                rs.getBoolean("active"));
    }
}
