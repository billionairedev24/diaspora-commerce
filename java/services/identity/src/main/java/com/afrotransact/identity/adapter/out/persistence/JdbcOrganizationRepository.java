package com.afrotransact.identity.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgMembership;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.port.OrganizationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrganizationRepository implements OrganizationRepository {

    private final JdbcClient jdbc;

    JdbcOrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Organization upsert(Organization org) {
        jdbc.sql("""
                insert into organization (id, kind, name, status, market, updated_at)
                values (:id, :kind, :name, :status, :market, :at)
                on conflict (id) do update set kind = excluded.kind, name = excluded.name, status = excluded.status,
                    market = coalesce(excluded.market, organization.market), updated_at = excluded.updated_at
                """)
                .param("id", org.id())
                .param("kind", org.kind().name())
                .param("name", org.name())
                .param("status", org.status().name())
                .param("market", org.market())
                .param("at", Jdbc.ts(org.updatedAt()))
                .update();
        return findById(org.id()).orElseThrow();
    }

    @Override
    public Optional<Organization> findById(String id) {
        return jdbc.sql("select * from organization where id = :id").param("id", id)
                .query((rs, n) -> new Organization(rs.getString("id"), OrgKind.valueOf(rs.getString("kind")),
                        rs.getString("name"), OrgStatus.valueOf(rs.getString("status")), rs.getString("market"),
                        Jdbc.instant(rs, "updated_at")))
                .optional();
    }

    @Override
    public List<OrgMembership> memberships(UUID userId) {
        return jdbc.sql("""
                select o.id, o.kind, o.name, o.status, m.role_key
                from org_membership m join organization o on o.id = m.org_id
                where m.user_id = :u order by o.name
                """)
                .param("u", userId)
                .query((rs, n) -> new OrgMembership(rs.getString("id"),
                        OrgKind.valueOf(rs.getString("kind")), rs.getString("name"),
                        OrgStatus.valueOf(rs.getString("status")), rs.getString("role_key")))
                .list();
    }

    @Override
    public boolean setMember(String orgId, UUID userId, String roleKey) {
        return jdbc.sql("""
                insert into org_membership (org_id, user_id, role_key) values (:o, :u, :r)
                on conflict (org_id, user_id) do update set role_key = excluded.role_key
                where org_membership.role_key <> excluded.role_key
                """).param("o", orgId).param("u", userId).param("r", roleKey).update() == 1;
    }

    @Override
    public boolean removeMember(String orgId, UUID userId) {
        return jdbc.sql("delete from org_membership where org_id = :o and user_id = :u")
                .param("o", orgId).param("u", userId).update() == 1;
    }
}
