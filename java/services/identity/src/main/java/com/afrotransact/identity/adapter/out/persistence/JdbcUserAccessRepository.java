package com.afrotransact.identity.adapter.out.persistence;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.identity.port.UserAccessRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUserAccessRepository implements UserAccessRepository {

    private final JdbcClient jdbc;

    JdbcUserAccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> roleKeys(UUID userId) {
        return new LinkedHashSet<>(jdbc.sql("select role_key from user_role where user_id = :u order by role_key")
                .param("u", userId).query(String.class).list());
    }

    @Override
    public boolean grantRole(UUID userId, String roleKey, String grantedBy) {
        return jdbc.sql("""
                insert into user_role (user_id, role_key, granted_by) values (:u, :r, :by) on conflict do nothing
                """).param("u", userId).param("r", roleKey).param("by", grantedBy).update() == 1;
    }

    @Override
    public boolean revokeRole(UUID userId, String roleKey) {
        return jdbc.sql("delete from user_role where user_id = :u and role_key = :r")
                .param("u", userId).param("r", roleKey).update() == 1;
    }

    @Override
    public Map<String, Set<String>> roleMarkets(UUID userId) {
        Map<String, Set<String>> out = new TreeMap<>();
        jdbc.sql("select role_key, market from user_role_market where user_id = :u order by role_key, market")
                .param("u", userId)
                .query(rs -> {
                    out.computeIfAbsent(rs.getString("role_key"), k -> new TreeSet<>()).add(rs.getString("market"));
                });
        return out;
    }

    @Override
    public boolean addRoleMarket(UUID userId, String roleKey, String market, String grantedBy) {
        return jdbc.sql("""
                insert into user_role_market (user_id, role_key, market, granted_by) values (:u, :r, :m, :by)
                on conflict do nothing
                """).param("u", userId).param("r", roleKey).param("m", market).param("by", grantedBy).update() == 1;
    }

    @Override
    public boolean removeRoleMarket(UUID userId, String roleKey, String market) {
        return jdbc.sql("delete from user_role_market where user_id = :u and role_key = :r and market = :m")
                .param("u", userId).param("r", roleKey).param("m", market).update() == 1;
    }

    @Override
    public Set<String> directGrants(UUID userId) {
        return new LinkedHashSet<>(jdbc.sql(
                "select permission_key from user_permission where user_id = :u order by permission_key")
                .param("u", userId).query(String.class).list());
    }

    @Override
    public boolean grantPermission(UUID userId, String permissionKey, String grantedBy) {
        return jdbc.sql("""
                insert into user_permission (user_id, permission_key, granted_by) values (:u, :p, :by)
                on conflict do nothing
                """).param("u", userId).param("p", permissionKey).param("by", grantedBy).update() == 1;
    }

    @Override
    public boolean revokePermission(UUID userId, String permissionKey) {
        return jdbc.sql("delete from user_permission where user_id = :u and permission_key = :p")
                .param("u", userId).param("p", permissionKey).update() == 1;
    }
}
