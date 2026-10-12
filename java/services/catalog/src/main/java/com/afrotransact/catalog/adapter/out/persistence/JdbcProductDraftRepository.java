package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.SavedDraft;
import com.afrotransact.catalog.port.ProductDraftRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProductDraftRepository implements ProductDraftRepository {

    private static final String SELECT = """
            select id, owner_org, created_by, product_id, title, category_path, payload::text as payload, created_at,
                   updated_at, version
            from product_draft
            """;

    private final JdbcClient jdbc;

    JdbcProductDraftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SavedDraft insert(SavedDraft d) {
        jdbc.sql("""
                insert into product_draft (id, owner_org, created_by, product_id, title, category_path, payload,
                                           created_at, updated_at, version)
                values (:id, :org, :by, :productId, :title, :path, cast(:payload as jsonb), :createdAt, :updatedAt, 0)
                """)
                .param("id", d.id()).param("org", d.ownerOrg()).param("by", d.createdBy())
                .param("productId", d.productId()).param("title", d.title()).param("path", d.categoryPath())
                .param("payload", d.payload()).param("createdAt", Timestamp.from(d.createdAt()))
                .param("updatedAt", Timestamp.from(d.updatedAt()))
                .update();
        return findById(d.id()).orElseThrow();
    }

    @Override
    public SavedDraft update(SavedDraft d) {
        int rows = jdbc.sql("""
                update product_draft set title = :title, category_path = :path, payload = cast(:payload as jsonb),
                       updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .param("id", d.id()).param("title", d.title()).param("path", d.categoryPath())
                .param("payload", d.payload()).param("updatedAt", Timestamp.from(d.updatedAt()))
                .param("version", d.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("catalog.draft-changed",
                    "This draft was saved from another tab or device; reload it to keep editing");
        }
        return findById(d.id()).orElseThrow();
    }

    @Override
    public Optional<SavedDraft> findById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(JdbcProductDraftRepository::map).optional();
    }

    @Override
    public List<SavedDraft> findByOwnerOrg(String orgId, int limit) {
        return jdbc.sql(SELECT + " where owner_org = :org order by updated_at desc limit :limit")
                .param("org", orgId).param("limit", limit).query(JdbcProductDraftRepository::map).list();
    }

    @Override
    public void delete(UUID id) {
        jdbc.sql("delete from product_draft where id = :id").param("id", id).update();
    }

    private static SavedDraft map(ResultSet rs, int rowNum) throws SQLException {
        return new SavedDraft(rs.getObject("id", UUID.class), rs.getString("owner_org"), rs.getString("created_by"),
                rs.getObject("product_id", UUID.class), rs.getString("title"), rs.getString("category_path"),
                rs.getString("payload"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }
}
