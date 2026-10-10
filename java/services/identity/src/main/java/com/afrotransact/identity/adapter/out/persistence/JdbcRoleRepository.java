package com.afrotransact.identity.adapter.out.persistence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.Permission;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.domain.Role;
import com.afrotransact.identity.port.RoleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRoleRepository implements RoleRepository {

    private final JdbcClient jdbc;

    JdbcRoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Permission> permissions() {
        return jdbc.sql("select key, description, module from permission order by module, key")
                .query((rs, n) -> new Permission(rs.getString("key"), rs.getString("description"),
                        rs.getString("module")))
                .list();
    }

    @Override
    public boolean permissionExists(String key) {
        return jdbc.sql("select count(*) from permission where key = :key").param("key", key)
                .query(Integer.class).single() > 0;
    }

    @Override
    public List<Role> roles(Portal portal) {
        List<String> keys = jdbc.sql("select key from role where portal = :portal order by position, key")
                .param("portal", portal.name()).query(String.class).list();
        Map<String, Role> byKey = roles(new HashSet<>(keys));
        return keys.stream().map(byKey::get).toList();
    }

    @Override
    public Optional<Role> role(String key) {
        return Optional.ofNullable(roles(Set.of(key)).get(key));
    }

    @Override
    public Map<String, Role> roles(Set<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> perms = new HashMap<>();
        jdbc.sql("select role_key, permission_key from role_permission where role_key in (:keys)")
                .param("keys", keys)
                .query((rs, n) -> perms.computeIfAbsent(rs.getString("role_key"), k -> new HashSet<>())
                        .add(rs.getString("permission_key")))
                .list();
        Map<String, Role> out = new LinkedHashMap<>();
        jdbc.sql("select key, portal, name, description from role where key in (:keys) order by position")
                .param("keys", keys)
                .query((rs, n) -> new Role(rs.getString("key"), Portal.valueOf(rs.getString("portal")),
                        rs.getString("name"), rs.getString("description"),
                        perms.getOrDefault(rs.getString("key"), Set.of())))
                .list()
                .forEach(r -> out.put(r.key(), r));
        return out;
    }

    @Override
    public void addPermissionToRole(String roleKey, String permissionKey) {
        jdbc.sql("insert into role_permission (role_key, permission_key) values (:r, :p) on conflict do nothing")
                .param("r", roleKey).param("p", permissionKey).update();
    }

    @Override
    public void removePermissionFromRole(String roleKey, String permissionKey) {
        jdbc.sql("delete from role_permission where role_key = :r and permission_key = :p")
                .param("r", roleKey).param("p", permissionKey).update();
    }

    @Override
    public List<UUID> holders(String roleKey) {
        return jdbc.sql("select user_id from user_role where role_key = :r").param("r", roleKey)
                .query(UUID.class).list();
    }
}
