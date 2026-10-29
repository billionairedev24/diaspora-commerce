package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.port.CategoryRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCategoryRepository implements CategoryRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcCategoryRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<CategoryNode> findAllActive() {
        return jdbc.sql("select * from category where active order by level, position, name")
                .query(JdbcCategoryRepository::mapNode).list();
    }

    @Override
    public Optional<CategoryNode> findById(UUID id) {
        return jdbc.sql("select * from category where id = :id").param("id", id)
                .query(JdbcCategoryRepository::mapNode).optional();
    }

    @Override
    public Optional<ProductType> findProductType(String code) {
        return jdbc.sql("""
                select code, label, identifier_rule, attributes::text as attributes, themes::text as themes,
                       traits::text as traits
                from product_type where code = :code
                """).param("code", code).query(this::mapType).optional();
    }

    @Override
    public List<ProductType> findAllProductTypes() {
        return jdbc.sql("""
                select code, label, identifier_rule, attributes::text as attributes, themes::text as themes,
                       traits::text as traits
                from product_type order by code
                """).query(this::mapType).list();
    }

    @Override
    public CategoryNode insert(CategoryNode n) {
        jdbc.sql("""
                insert into category (id, parent_id, name, level, product_type_code, path, position, active)
                values (:id, :parentId, :name, :level, :type, :path, :position, :active)
                """)
                .param("id", n.id())
                .param("parentId", n.parentId())
                .param("name", n.name())
                .param("level", n.level().name())
                .param("type", n.productTypeCode())
                .param("path", n.path())
                .param("position", n.position())
                .param("active", n.active())
                .update();
        return n;
    }

    private static CategoryNode mapNode(ResultSet rs, int rowNum) throws SQLException {
        return new CategoryNode(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                rs.getString("name"), CategoryLevel.valueOf(rs.getString("level")),
                rs.getString("product_type_code"), rs.getString("path"), rs.getInt("position"),
                rs.getBoolean("active"));
    }

    private ProductType mapType(ResultSet rs, int rowNum) throws SQLException {
        return new ProductType(rs.getString("code"), rs.getString("label"),
                json.attributes(rs.getString("attributes")), json.themes(rs.getString("themes")),
                IdentifierRule.valueOf(rs.getString("identifier_rule")), json.traits(rs.getString("traits")));
    }
}
