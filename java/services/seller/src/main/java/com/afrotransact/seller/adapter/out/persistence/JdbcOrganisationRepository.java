package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrganisationRepository implements OrganisationRepository {

    private final JdbcClient jdbc;

    JdbcOrganisationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Organisation insert(Organisation o) {
        jdbc.sql("""
                insert into organisation (id, kind, legal_name, display_name, country, status, created_at, updated_at,
                                          version)
                values (:id, :kind, :legal, :display, :country, :status, :created, :updated, 0)
                """)
                .param("id", o.id()).param("kind", o.kind().name()).param("legal", o.legalName())
                .param("display", o.displayName()).param("country", o.country()).param("status", o.status().name())
                .param("created", Sql.ts(o.createdAt())).param("updated", Sql.ts(o.updatedAt()))
                .update();
        return findById(o.id()).orElseThrow();
    }

    @Override
    public Organisation update(Organisation o) {
        int rows = jdbc.sql("""
                update organisation set legal_name = :legal, display_name = :display, country = :country,
                       status = :status, updated_at = :updated, version = version + 1
                where id = :id and version = :version
                """)
                .param("legal", o.legalName()).param("display", o.displayName()).param("country", o.country())
                .param("status", o.status().name()).param("updated", Sql.ts(o.updatedAt())).param("id", o.id())
                .param("version", o.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("seller.concurrent-update", "Organisation changed; reload and retry");
        }
        return findById(o.id()).orElseThrow();
    }

    @Override
    public Optional<Organisation> findById(String id) {
        return jdbc.sql("select * from organisation where id = :id").param("id", id)
                .query(JdbcOrganisationRepository::map).optional();
    }

    @Override
    public void addMember(OrgMember m) {
        jdbc.sql("""
                insert into org_member (org_id, user_id, role_key, created_at) values (:org, :user, :role, :at)
                on conflict (org_id, user_id) do update set role_key = excluded.role_key
                """)
                .param("org", m.orgId()).param("user", m.userId()).param("role", m.roleKey())
                .param("at", Sql.ts(m.createdAt()))
                .update();
    }

    @Override
    public Optional<OrgMember> findMember(String orgId, String userId) {
        return jdbc.sql("select * from org_member where org_id = :org and user_id = :user")
                .param("org", orgId).param("user", userId).query(JdbcOrganisationRepository::member).optional();
    }

    @Override
    public List<OrgMember> members(String orgId) {
        return jdbc.sql("select * from org_member where org_id = :org order by created_at").param("org", orgId)
                .query(JdbcOrganisationRepository::member).list();
    }

    private static OrgMember member(ResultSet rs, int n) throws SQLException {
        return new OrgMember(rs.getString("org_id"), rs.getString("user_id"), rs.getString("role_key"),
                Sql.instant(rs, "created_at"));
    }

    private static Organisation map(ResultSet rs, int n) throws SQLException {
        return new Organisation(rs.getString("id"), OrgKind.valueOf(rs.getString("kind")), rs.getString("legal_name"),
                rs.getString("display_name"), rs.getString("country"), OrgStatus.valueOf(rs.getString("status")),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
