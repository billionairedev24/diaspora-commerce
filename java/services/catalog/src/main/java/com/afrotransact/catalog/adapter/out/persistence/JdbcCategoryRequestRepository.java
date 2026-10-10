package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.CategoryRequestStatus;
import com.afrotransact.catalog.port.CategoryRequestRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCategoryRequestRepository implements CategoryRequestRepository {

    private final JdbcClient jdbc;

    JdbcCategoryRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CategoryRequest insert(CategoryRequest r) {
        jdbc.sql("""
                insert into category_request (id, reference, requested_by_org, parent_path, name, example_product,
                                              details_needed, status, created_at, version)
                values (:id, :ref, :org, :parentPath, :name, :example, :details, :status, :createdAt, 0)
                """)
                .param("id", r.id())
                .param("ref", r.reference())
                .param("org", r.requestedByOrg())
                .param("parentPath", r.parentPath())
                .param("name", r.name())
                .param("example", r.exampleProduct())
                .param("details", r.detailsNeeded())
                .param("status", r.status().name())
                .param("createdAt", Timestamp.from(r.createdAt()))
                .update();
        return findById(r.id()).orElseThrow();
    }

    @Override
    public CategoryRequest update(CategoryRequest r) {
        int rows = jdbc.sql("""
                update category_request set status = :status, resolved_category_id = :resolved,
                       decision_note = :note, decided_at = :decidedAt, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", r.status().name())
                .param("resolved", r.resolvedCategoryId())
                .param("note", r.decisionNote())
                .param("decidedAt", r.decidedAt() == null ? null : Timestamp.from(r.decidedAt()))
                .param("id", r.id())
                .param("version", r.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("catalog.concurrent-update", "Category request changed; reload and retry");
        }
        return findById(r.id()).orElseThrow();
    }

    @Override
    public Optional<CategoryRequest> findById(UUID id) {
        return jdbc.sql("select * from category_request where id = :id").param("id", id)
                .query(JdbcCategoryRequestRepository::map).optional();
    }

    @Override
    public Optional<CategoryRequest> findByReference(String reference) {
        return jdbc.sql("select * from category_request where reference = :ref").param("ref", reference)
                .query(JdbcCategoryRequestRepository::map).optional();
    }

    @Override
    public List<CategoryRequest> findByStatus(CategoryRequestStatus status) {
        return jdbc.sql("select * from category_request where status = :s order by created_at")
                .param("s", status.name()).query(JdbcCategoryRequestRepository::map).list();
    }

    @Override
    public List<CategoryRequest> findByOrg(String orgId) {
        return jdbc.sql("select * from category_request where requested_by_org = :org order by created_at desc")
                .param("org", orgId).query(JdbcCategoryRequestRepository::map).list();
    }

    private static CategoryRequest map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new CategoryRequest(rs.getObject("id", UUID.class), rs.getString("reference"),
                rs.getString("requested_by_org"), rs.getString("parent_path"), rs.getString("name"),
                rs.getString("example_product"), rs.getString("details_needed"),
                CategoryRequestStatus.valueOf(rs.getString("status")),
                rs.getObject("resolved_category_id", UUID.class), rs.getString("decision_note"),
                rs.getTimestamp("created_at").toInstant(), decided == null ? null : decided.toInstant(),
                rs.getLong("version"));
    }
}
