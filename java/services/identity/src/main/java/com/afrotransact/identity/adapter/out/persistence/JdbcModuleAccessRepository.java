package com.afrotransact.identity.adapter.out.persistence;

import java.util.List;

import com.afrotransact.identity.domain.AccessLevel;
import com.afrotransact.identity.domain.ModuleAccess;
import com.afrotransact.identity.domain.ModuleDefinition;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.port.ModuleAccessRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcModuleAccessRepository implements ModuleAccessRepository {

    private final JdbcClient jdbc;

    JdbcModuleAccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ModuleDefinition> modules(Portal portal) {
        return jdbc.sql("select key, label, position from access_module where portal = :p order by position")
                .param("p", portal.name())
                .query((rs, n) -> new ModuleDefinition(portal, rs.getString("key"), rs.getString("label"),
                        rs.getInt("position")))
                .list();
    }

    @Override
    public List<ModuleAccess> matrix(Portal portal) {
        return jdbc.sql("""
                select ma.module_key, ma.role_key, ma.level from module_access ma
                join access_module m on m.portal = ma.portal and m.key = ma.module_key
                where ma.portal = :p order by m.position, ma.role_key
                """)
                .param("p", portal.name())
                .query((rs, n) -> new ModuleAccess(portal, rs.getString("module_key"), rs.getString("role_key"),
                        AccessLevel.valueOf(rs.getString("level"))))
                .list();
    }

    @Override
    public AccessLevel set(Portal portal, String moduleKey, String roleKey, AccessLevel level) {
        AccessLevel previous = jdbc.sql("""
                select level from module_access where portal = :p and module_key = :m and role_key = :r
                """).param("p", portal.name()).param("m", moduleKey).param("r", roleKey)
                .query(String.class).optional().map(AccessLevel::valueOf).orElse(AccessLevel.NONE);
        jdbc.sql("""
                insert into module_access (portal, module_key, role_key, level) values (:p, :m, :r, :l)
                on conflict (portal, module_key, role_key) do update set level = excluded.level
                """).param("p", portal.name()).param("m", moduleKey).param("r", roleKey).param("l", level.name())
                .update();
        return previous;
    }
}
