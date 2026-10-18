package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.afrotransact.seller.domain.BusinessPage;
import com.afrotransact.seller.port.BusinessPageRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcBusinessPageRepository implements BusinessPageRepository {

    private final JdbcClient jdbc;

    JdbcBusinessPageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BusinessPage> findByOrg(String orgId) {
        return jdbc.sql("select * from business_page where org_id = :org").param("org", orgId)
                .query(JdbcBusinessPageRepository::row).optional();
    }

    @Override
    public Optional<BusinessPage> findBySlug(String slug) {
        return jdbc.sql("select * from business_page where slug = :slug").param("slug", slug)
                .query(JdbcBusinessPageRepository::row).optional();
    }

    @Override
    public boolean insert(BusinessPage page) {
        return jdbc.sql("""
                insert into business_page (org_id, slug, created_at) values (:org, :slug, :at)
                on conflict do nothing
                """).param("org", page.orgId()).param("slug", page.slug()).param("at", Sql.ts(page.createdAt()))
                .update() == 1;
    }

    private static BusinessPage row(ResultSet rs, int n) throws SQLException {
        return new BusinessPage(rs.getString("org_id"), rs.getString("slug"), Sql.instant(rs, "created_at"));
    }
}
