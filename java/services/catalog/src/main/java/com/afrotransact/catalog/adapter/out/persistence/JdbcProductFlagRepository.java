package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.FlagSource;
import com.afrotransact.catalog.domain.ListingReasonCode;
import com.afrotransact.catalog.domain.ProductFlag;
import com.afrotransact.catalog.port.ProductFlagRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProductFlagRepository implements ProductFlagRepository {

    private final JdbcClient jdbc;

    JdbcProductFlagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Stored insert(ProductFlag f) {
        int rows = jdbc.sql("""
                insert into product_flag (id, product_id, owner_org, source, reason_code, details, reporter_id,
                                          risk_level, created_at)
                values (:id, :product, :org, :source, :reason, :details, :reporter, :risk, :createdAt)
                on conflict (product_id, reporter_id) where source = 'CUSTOMER_REPORT' do nothing
                """)
                .param("id", f.id()).param("product", f.productId()).param("org", f.ownerOrgId())
                .param("source", f.source().name()).param("reason", f.reasonCode().name())
                .param("details", f.details()).param("reporter", f.reporterId()).param("risk", f.riskLevel())
                .param("createdAt", Timestamp.from(f.createdAt()))
                .update();
        if (rows == 1) {
            return new Stored(findById(f.id()).orElseThrow(), true);
        }
        ProductFlag earlier = jdbc.sql("""
                select * from product_flag where product_id = :product and reporter_id = :reporter
                  and source = 'CUSTOMER_REPORT'
                """).param("product", f.productId()).param("reporter", f.reporterId())
                .query(JdbcProductFlagRepository::map).single();
        return new Stored(earlier, false);
    }

    @Override
    public Optional<ProductFlag> findById(UUID id) {
        return jdbc.sql("select * from product_flag where id = :id").param("id", id)
                .query(JdbcProductFlagRepository::map).optional();
    }

    private static ProductFlag map(ResultSet rs, int row) throws SQLException {
        return new ProductFlag(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("owner_org"), FlagSource.valueOf(rs.getString("source")),
                ListingReasonCode.valueOf(rs.getString("reason_code")), rs.getString("details"),
                rs.getString("reporter_id"), rs.getString("risk_level"), rs.getTimestamp("created_at").toInstant());
    }
}
