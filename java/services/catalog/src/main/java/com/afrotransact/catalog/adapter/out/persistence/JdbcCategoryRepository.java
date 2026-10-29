package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.domain.taxonomy.StoredProductType;
import com.afrotransact.catalog.port.CategoryRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCategoryRepository implements CategoryRepository {

    private static final String TYPE_COLUMNS = """
            code, label, identifier_rule, attributes::text as attributes, themes::text as themes,
            traits::text as traits, version, updated_at
            """;

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
    public List<CategoryNode> findAll() {
        return jdbc.sql("select * from category order by level, position, name")
                .query(JdbcCategoryRepository::mapNode).list();
    }

    @Override
    public Optional<CategoryNode> findById(UUID id) {
        return jdbc.sql("select * from category where id = :id").param("id", id)
                .query(JdbcCategoryRepository::mapNode).optional();
    }

    @Override
    public List<CategoryNode> findChildren(UUID parentId) {
        String where = parentId == null ? "parent_id is null" : "parent_id = :parent";
        JdbcClient.StatementSpec spec = jdbc.sql("select * from category where " + where + " order by position, name");
        if (parentId != null) {
            spec = spec.param("parent", parentId);
        }
        return spec.query(JdbcCategoryRepository::mapNode).list();
    }

    @Override
    public Optional<CategoryNode> findByCode(String code) {
        return jdbc.sql("select * from category where code = :code").param("code", code)
                .query(JdbcCategoryRepository::mapNode).optional();
    }

    @Override
    public Optional<CategoryNode> findByPath(String path) {
        return jdbc.sql("select * from category where lower(path) = lower(:path)").param("path", path)
                .query(JdbcCategoryRepository::mapNode).optional();
    }

    @Override
    public Optional<ProductType> findProductType(String code) {
        return findStoredProductType(code).map(StoredProductType::type);
    }

    @Override
    public List<ProductType> findAllProductTypes() {
        return findAllStoredProductTypes().stream().map(StoredProductType::type).toList();
    }

    @Override
    public Optional<StoredProductType> findStoredProductType(String code) {
        return jdbc.sql("select " + TYPE_COLUMNS + " from product_type where code = :code").param("code", code)
                .query(this::mapType).optional();
    }

    @Override
    public List<StoredProductType> findAllStoredProductTypes() {
        return jdbc.sql("select " + TYPE_COLUMNS + " from product_type order by code").query(this::mapType).list();
    }

    @Override
    public CategoryNode insert(CategoryNode n) {
        jdbc.sql("""
                insert into category (id, parent_id, code, name, level, product_type_code, path, position, active,
                                      version, retired_at, retired_reason, updated_at)
                values (:id, :parentId, :code, :name, :level, :type, :path, :position, :active, :version,
                        :retiredAt, :retiredReason, now())
                """)
                .param("id", n.id())
                .param("parentId", n.parentId())
                .param("code", n.code())
                .param("name", n.name())
                .param("level", n.level().name())
                .param("type", n.productTypeCode())
                .param("path", n.path())
                .param("position", n.position())
                .param("active", n.active())
                .param("version", n.version())
                .param("retiredAt", n.retiredAt() == null ? null : Timestamp.from(n.retiredAt()))
                .param("retiredReason", n.retiredReason())
                .update();
        return n;
    }

    @Override
    public CategoryNode update(CategoryNode n) {
        int rows = jdbc.sql("""
                update category set parent_id = :parentId, name = :name, path = :path, position = :position,
                       active = :active, retired_at = :retiredAt, retired_reason = :retiredReason,
                       version = version + 1, updated_at = now()
                where id = :id and version = :version
                """)
                .param("id", n.id())
                .param("parentId", n.parentId())
                .param("name", n.name())
                .param("path", n.path())
                .param("position", n.position())
                .param("active", n.active())
                .param("retiredAt", n.retiredAt() == null ? null : Timestamp.from(n.retiredAt()))
                .param("retiredReason", n.retiredReason())
                .param("version", n.version())
                .update();
        if (rows == 0) {
            throw new StaleVersionException("This category changed since you opened it. Reload and try again.");
        }
        return findById(n.id()).orElseThrow();
    }

    @Override
    public void updatePath(UUID id, String path) {
        jdbc.sql("update category set path = :path where id = :id").param("id", id).param("path", path).update();
    }

    @Override
    public void rewriteDraftPaths(String oldPath, String newPath) {
        jdbc.sql("""
                update product_draft set category_path = :newPath || substr(category_path, char_length(:oldPath) + 1)
                where category_path = :oldPath or category_path like :prefix
                """)
                .param("oldPath", oldPath).param("newPath", newPath)
                .param("prefix", escapeLike(oldPath) + " › %").update();
    }

    @Override
    public StoredProductType insertProductType(ProductType t) {
        jdbc.sql("""
                insert into product_type (code, label, identifier_rule, attributes, themes, traits, version,
                                          updated_at)
                values (:code, :label, :rule, cast(:attributes as jsonb), cast(:themes as jsonb),
                        cast(:traits as jsonb), 0, now())
                """)
                .param("code", t.code()).param("label", t.label()).param("rule", t.identifierRule().name())
                .param("attributes", json.write(t.attributes())).param("themes", json.write(t.themes()))
                .param("traits", json.write(traitsOf(t)))
                .update();
        return findStoredProductType(t.code()).orElseThrow();
    }

    @Override
    public StoredProductType updateProductType(ProductType t, long expectedVersion) {
        int rows = jdbc.sql("""
                update product_type set label = :label, identifier_rule = :rule,
                       attributes = cast(:attributes as jsonb), traits = cast(:traits as jsonb),
                       version = version + 1, updated_at = now()
                where code = :code and version = :version
                """)
                .param("code", t.code()).param("label", t.label()).param("rule", t.identifierRule().name())
                .param("attributes", json.write(t.attributes())).param("traits", json.write(traitsOf(t)))
                .param("version", expectedVersion)
                .update();
        if (rows == 0) {
            throw new StaleVersionException("This product type changed since you opened it. Reload and try again.");
        }
        return findStoredProductType(t.code()).orElseThrow();
    }

    private static Map<String, Boolean> traitsOf(ProductType t) {
        return Map.of("food", t.traits().food(), "perishable", t.traits().perishable(),
                "regulated", t.traits().regulated(), "preorder", t.traits().preorder());
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static CategoryNode mapNode(ResultSet rs, int rowNum) throws SQLException {
        Timestamp retired = rs.getTimestamp("retired_at");
        return new CategoryNode(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                rs.getString("code"), rs.getString("name"), CategoryLevel.valueOf(rs.getString("level")),
                rs.getString("product_type_code"), rs.getString("path"), rs.getInt("position"),
                rs.getBoolean("active"), rs.getLong("version"), retired == null ? null : retired.toInstant(),
                rs.getString("retired_reason"));
    }

    private StoredProductType mapType(ResultSet rs, int rowNum) throws SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        Instant at = updated == null ? null : updated.toInstant();
        ProductType type = new ProductType(rs.getString("code"), rs.getString("label"),
                json.attributes(rs.getString("attributes")), json.themes(rs.getString("themes")),
                IdentifierRule.valueOf(rs.getString("identifier_rule")), json.traits(rs.getString("traits")));
        return new StoredProductType(type, rs.getLong("version"), at);
    }
}
