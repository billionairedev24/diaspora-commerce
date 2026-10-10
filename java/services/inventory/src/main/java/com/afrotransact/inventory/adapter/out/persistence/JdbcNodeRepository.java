package com.afrotransact.inventory.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.NodeKind;
import com.afrotransact.inventory.domain.PostalAddress;
import com.afrotransact.inventory.port.NodeRepository;
import org.springframework.jdbc.core.RowCallbackHandler;
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

    @Override
    public Optional<PostalAddress> address(String nodeId) {
        return jdbc.sql("""
                select name, address_line1, address_line2, address_city, address_region, address_postal, country,
                       address_phone
                from node where id = :id
                """).param("id", nodeId).query((rs, n) -> new PostalAddress("AfroTransact " + rs.getString("name"),
                rs.getString("address_line1"), rs.getString("address_line2"), rs.getString("address_city"),
                rs.getString("address_region"), rs.getString("address_postal"), rs.getString("country"),
                rs.getString("address_phone"))).optional();
    }

    @Override
    public void updateAddress(String nodeId, PostalAddress a) {
        jdbc.sql("""
                update node set address_line1 = :line1, address_line2 = :line2, address_city = :city,
                       address_region = :region, address_postal = :postal, address_phone = :phone
                where id = :id
                """)
                .param("line1", a.line1()).param("line2", a.line2()).param("city", a.city())
                .param("region", a.region()).param("postal", a.postalCode()).param("phone", a.phone())
                .param("id", nodeId).update();
    }

    @Override
    public Map<String, Long> unitsHeldByNode() {
        Map<String, Long> held = new HashMap<>();
        RowCallbackHandler collect = rs -> held.put(rs.getString("node_id"), rs.getLong("units"));
        jdbc.sql("select node_id, sum(on_hand + damaged + inbound) as units from inventory_position group by node_id")
                .query(collect);
        return held;
    }

    private Node map(ResultSet rs, int row) throws SQLException {
        return new Node(rs.getString("id"), rs.getString("name"), NodeKind.valueOf(rs.getString("kind")),
                rs.getString("country"), rs.getString("timezone"), json.strings(rs.getString("zones")),
                rs.getBoolean("active"));
    }
}
