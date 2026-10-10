package com.afrotransact.identity.adapter.out.persistence;

import java.util.LinkedHashSet;
import java.util.Set;
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
