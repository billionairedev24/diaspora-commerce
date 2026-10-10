package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.ShipFromAddress;
import com.afrotransact.seller.port.ShipFromAddressRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcShipFromAddressRepository implements ShipFromAddressRepository {

    private final JdbcClient jdbc;

    JdbcShipFromAddressRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ShipFromAddress> list(String orgId) {
        return jdbc.sql("select * from ship_from_address where org_id = :o order by is_default desc, created_at, id")
                .param("o", orgId).query(JdbcShipFromAddressRepository::map).list();
    }

    @Override
    public Optional<ShipFromAddress> find(String orgId, UUID id) {
        return jdbc.sql("select * from ship_from_address where org_id = :o and id = :id").param("o", orgId)
                .param("id", id).query(JdbcShipFromAddressRepository::map).optional();
    }

    @Override
    public void insert(ShipFromAddress a) {
        jdbc.sql("""
                insert into ship_from_address (id, org_id, label, name, company, phone, line1, line2, city, state,
                                               postal_code, country, is_default, created_at, updated_at, version)
                values (:id, :org, :label, :name, :company, :phone, :line1, :line2, :city, :state, :zip, :country,
                        :def, :created, :updated, 0)
                """)
                .param("id", a.id()).param("org", a.orgId()).param("label", a.label()).param("name", a.name())
                .param("company", a.company()).param("phone", a.phone()).param("line1", a.line1())
                .param("line2", a.line2()).param("city", a.city()).param("state", a.state())
                .param("zip", a.postalCode()).param("country", a.country()).param("def", a.isDefault())
                .param("created", Sql.ts(a.createdAt())).param("updated", Sql.ts(a.updatedAt()))
                .update();
    }

    @Override
    public boolean update(ShipFromAddress a) {
        return jdbc.sql("""
                update ship_from_address set label = :label, name = :name, company = :company, phone = :phone,
                       line1 = :line1, line2 = :line2, city = :city, state = :state, postal_code = :zip,
                       country = :country, updated_at = :updated, version = version + 1
                 where org_id = :org and id = :id and version = :version
                """)
                .param("id", a.id()).param("org", a.orgId()).param("label", a.label()).param("name", a.name())
                .param("company", a.company()).param("phone", a.phone()).param("line1", a.line1())
                .param("line2", a.line2()).param("city", a.city()).param("state", a.state())
                .param("zip", a.postalCode()).param("country", a.country())
                .param("updated", Sql.ts(a.updatedAt())).param("version", a.version())
                .update() == 1;
    }

    @Override
    public void makeDefault(String orgId, UUID id) {
        jdbc.sql("update ship_from_address set is_default = false, version = version + 1 where org_id = :o and "
                + "is_default and id <> :id").param("o", orgId).param("id", id).update();
        jdbc.sql("update ship_from_address set is_default = true, version = version + 1 where org_id = :o and "
                + "id = :id and not is_default").param("o", orgId).param("id", id).update();
    }

    @Override
    public void delete(String orgId, UUID id) {
        jdbc.sql("delete from ship_from_address where org_id = :o and id = :id").param("o", orgId).param("id", id)
                .update();
    }

    private static ShipFromAddress map(ResultSet rs, int n) throws SQLException {
        return new ShipFromAddress(rs.getObject("id", UUID.class), rs.getString("org_id"), rs.getString("label"),
                rs.getString("name"), rs.getString("company"), rs.getString("phone"), rs.getString("line1"),
                rs.getString("line2"), rs.getString("city"), rs.getString("state"), rs.getString("postal_code"),
                rs.getString("country"), rs.getBoolean("is_default"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
