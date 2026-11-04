package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.seller.domain.DepartmentRequest;
import com.afrotransact.seller.domain.DepartmentRequestStatus;
import com.afrotransact.seller.port.CatalogScopeRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCatalogScopeRepository implements CatalogScopeRepository {

    private final JdbcClient jdbc;

    JdbcCatalogScopeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> departments(String orgId) {
        return new LinkedHashSet<>(jdbc.sql("select department_code from org_department where org_id = :org "
                + "order by department_code").param("org", orgId).query(String.class).list());
    }

    @Override
    public void add(String orgId, Set<String> departments, String source, String actor, Instant at) {
        for (String code : departments) {
            jdbc.sql("""
                    insert into org_department (org_id, department_code, source, added_by, created_at)
                    values (:org, :code, :source, :actor, :at) on conflict (org_id, department_code) do nothing
                    """).param("org", orgId).param("code", code).param("source", source).param("actor", actor)
                    .param("at", Sql.ts(at)).update();
        }
    }

    @Override
    public void replace(String orgId, Set<String> departments, String source, String actor, Instant at) {
        jdbc.sql("delete from org_department where org_id = :org and department_code not in (:keep)")
                .param("org", orgId).param("keep", departments.isEmpty() ? Set.of("") : departments).update();
        add(orgId, departments, source, actor, at);
    }

    @Override
    public DepartmentRequest insert(DepartmentRequest r) {
        jdbc.sql("""
                insert into department_request (id, org_id, department_code, note, status, requested_by, created_at)
                values (:id, :org, :code, :note, :status, :by, :at)
                """).param("id", r.id()).param("org", r.orgId()).param("code", r.departmentCode())
                .param("note", r.note()).param("status", r.status().name()).param("by", r.requestedBy())
                .param("at", Sql.ts(r.createdAt())).update();
        return r;
    }

    @Override
    public DepartmentRequest update(DepartmentRequest r) {
        jdbc.sql("""
                update department_request set status = :status, decided_by = :by, decided_at = :at,
                       decision_note = :note where id = :id
                """).param("status", r.status().name()).param("by", r.decidedBy()).param("at", Sql.ts(r.decidedAt()))
                .param("note", r.decisionNote()).param("id", r.id()).update();
        return r;
    }

    @Override
    public Optional<DepartmentRequest> findRequest(UUID id) {
        return jdbc.sql("select * from department_request where id = :id").param("id", id)
                .query(JdbcCatalogScopeRepository::request).optional();
    }

    @Override
    public Optional<DepartmentRequest> findPending(String orgId, String departmentCode) {
        return jdbc.sql("select * from department_request where org_id = :org and department_code = :code "
                + "and status = 'PENDING'").param("org", orgId).param("code", departmentCode)
                .query(JdbcCatalogScopeRepository::request).optional();
    }

    @Override
    public List<DepartmentRequest> requestsOf(String orgId) {
        return jdbc.sql("select * from department_request where org_id = :org order by created_at desc limit 50")
                .param("org", orgId).query(JdbcCatalogScopeRepository::request).list();
    }

    @Override
    public List<DepartmentRequest> queue(DepartmentRequestStatus status, int limit) {
        String order = status == DepartmentRequestStatus.PENDING ? "created_at" : "decided_at desc, created_at desc";
        return jdbc.sql("select * from department_request where status = :status order by " + order + " limit :limit")
                .param("status", status.name()).param("limit", limit).query(JdbcCatalogScopeRepository::request)
                .list();
    }

    private static DepartmentRequest request(ResultSet rs, int n) throws SQLException {
        return new DepartmentRequest(rs.getObject("id", UUID.class), rs.getString("org_id"),
                rs.getString("department_code"), rs.getString("note"),
                DepartmentRequestStatus.valueOf(rs.getString("status")), rs.getString("requested_by"),
                Sql.instant(rs, "created_at"), rs.getString("decided_by"), Sql.instant(rs, "decided_at"),
                rs.getString("decision_note"), Set.of());
    }
}
