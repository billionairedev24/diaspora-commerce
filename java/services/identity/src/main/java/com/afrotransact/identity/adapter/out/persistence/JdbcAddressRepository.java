package com.afrotransact.identity.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.PostalAddress;
import com.afrotransact.identity.port.AddressRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAddressRepository implements AddressRepository {

    private final JdbcClient jdbc;

    JdbcAddressRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PostalAddress> findByUser(UUID userId) {
        return jdbc.sql("select * from customer_address where user_id = :u").param("u", userId)
                .query(JdbcAddressRepository::map).list();
    }

    @Override
    public Optional<PostalAddress> findById(UUID userId, UUID addressId) {
        return jdbc.sql("select * from customer_address where user_id = :u and id = :id")
                .param("u", userId).param("id", addressId).query(JdbcAddressRepository::map).optional();
    }

    @Override
    public void insert(PostalAddress a) {
        jdbc.sql("""
                insert into customer_address (id, user_id, label, full_name, phone, line1, line2, city, state,
                    postal_code, country, latitude, longitude, instructions, verified, is_default, created_at, updated_at)
                values (:id, :user, :label, :name, :phone, :line1, :line2, :city, :state, :zip, :country, :lat, :lon,
                    :instructions, :verified, :isDefault, :created, :updated)
                """)
                .param("id", a.id()).param("user", a.userId()).param("label", a.label()).param("name", a.fullName())
                .param("phone", a.phone()).param("line1", a.line1()).param("line2", a.line2()).param("city", a.city())
                .param("state", a.state()).param("zip", a.postalCode()).param("country", a.country())
                .param("lat", a.latitude()).param("lon", a.longitude()).param("instructions", a.instructions())
                .param("verified", a.verified()).param("isDefault", a.isDefault())
                .param("created", Jdbc.ts(a.createdAt())).param("updated", Jdbc.ts(a.updatedAt()))
                .update();
    }

    @Override
    public void update(PostalAddress a) {
        jdbc.sql("""
                update customer_address set label = :label, full_name = :name, phone = :phone, line1 = :line1,
                    line2 = :line2, city = :city, state = :state, postal_code = :zip, country = :country,
                    latitude = :lat, longitude = :lon, instructions = :instructions, verified = :verified,
                    updated_at = :updated
                where id = :id and user_id = :user
                """)
                .param("id", a.id()).param("user", a.userId()).param("label", a.label()).param("name", a.fullName())
                .param("phone", a.phone()).param("line1", a.line1()).param("line2", a.line2()).param("city", a.city())
                .param("state", a.state()).param("zip", a.postalCode()).param("country", a.country())
                .param("lat", a.latitude()).param("lon", a.longitude()).param("instructions", a.instructions())
                .param("verified", a.verified()).param("updated", Jdbc.ts(a.updatedAt()))
                .update();
    }

    @Override
    public void delete(UUID userId, UUID addressId) {
        jdbc.sql("delete from customer_address where user_id = :u and id = :id")
                .param("u", userId).param("id", addressId).update();
    }

    @Override
    public void setDefault(UUID userId, UUID addressId) {
        jdbc.sql("update customer_address set is_default = false where user_id = :u and is_default")
                .param("u", userId).update();
        jdbc.sql("update customer_address set is_default = true where user_id = :u and id = :id")
                .param("u", userId).param("id", addressId).update();
    }

    @Override
    public int count(UUID userId) {
        return jdbc.sql("select count(*) from customer_address where user_id = :u").param("u", userId)
                .query(Integer.class).single();
    }

    private static PostalAddress map(ResultSet rs, int rowNum) throws SQLException {
        return new PostalAddress(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("label"), rs.getString("full_name"), rs.getString("phone"), rs.getString("line1"),
                rs.getString("line2"), rs.getString("city"), rs.getString("state"), rs.getString("postal_code"),
                rs.getString("country"), rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class),
                rs.getString("instructions"), rs.getBoolean("verified"), rs.getBoolean("is_default"),
                Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"));
    }
}
