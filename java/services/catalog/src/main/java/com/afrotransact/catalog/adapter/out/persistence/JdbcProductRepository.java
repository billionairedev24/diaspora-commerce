package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProductRepository implements ProductRepository {

    private static final String SELECT = """
            select id, atin, owner_kind, owner_org, category_id, category_request_id, product_type, title,
                   attributes::text as attributes, images::text as images, variation_theme, status, moderation_note,
                   created_at, updated_at, version
            from product
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcProductRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Product insert(Product p) {
        jdbc.sql("""
                insert into product (id, atin, owner_kind, owner_org, category_id, category_request_id, product_type,
                                     title, attributes, images, variation_theme, status, moderation_note,
                                     created_at, updated_at, version)
                values (:id, :atin, :ownerKind, :ownerOrg, :categoryId, :categoryRequestId, :productType, :title,
                        cast(:attributes as jsonb), cast(:images as jsonb), :theme, :status, :note,
                        :createdAt, :updatedAt, 0)
                """)
                .params(params(p))
                .update();
        insertVariants(p);
        return findById(p.id()).orElseThrow();
    }

    @Override
    public Product update(Product p) {
        Map<String, Object> params = params(p);
        params.put("version", p.version());
        int rows = jdbc.sql("""
                update product set category_id = :categoryId, product_type = :productType, title = :title,
                       attributes = cast(:attributes as jsonb), images = cast(:images as jsonb),
                       variation_theme = :theme, status = :status, moderation_note = :note,
                       updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .params(params)
                .update();
        if (rows == 0) {
            throw new ConflictException("catalog.concurrent-update",
                    "Product " + p.id() + " was changed by someone else; reload and try again");
        }
        return findById(p.id()).orElseThrow();
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return one(SELECT + " where id = :v", id);
    }

    @Override
    public Optional<Product> findByAtin(Atin atin) {
        return one(SELECT + " where atin = :v", atin.value());
    }

    @Override
    public Optional<Product> findByVariantAtin(Atin variantAtin) {
        return one(SELECT + " where id = (select product_id from product_variant where atin = :v)", variantAtin.value());
    }

    @Override
    public Optional<Product> findByVariantGtin(String gtin) {
        return one(SELECT + " where id = (select product_id from product_variant where gtin = :v limit 1)", gtin);
    }

    @Override
    public List<Product> findByCategoryRequest(UUID categoryRequestId) {
        return withVariants(jdbc.sql(SELECT + " where category_request_id = :v").param("v", categoryRequestId)
                .query(this::mapProduct).list());
    }

    @Override
    public List<Product> findByOwnerOrg(String orgId) {
        return withVariants(jdbc.sql(SELECT + " where owner_org = :org order by created_at").param("org", orgId)
                .query(this::mapProduct).list());
    }

    @Override
    public ProductPage search(ProductQuery q) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (q.ownerOrgId() != null) {
            where.append(" and owner_org = :org");
            params.put("org", q.ownerOrgId());
        }
        if (q.status() != null) {
            where.append(" and status = :status");
            params.put("status", q.status().name());
        }
        if (q.text() != null && !q.text().isBlank()) {
            where.append(" and (title ilike :text or atin = upper(:raw))");
            params.put("text", "%" + q.text().trim() + "%");
            params.put("raw", q.text().trim());
        }
        long total = jdbc.sql("select count(*) from product" + where).params(params).query(Long.class).single();
        params.put("limit", q.size());
        params.put("offset", (long) q.page() * q.size());
        List<Product> items = withVariants(jdbc.sql(SELECT + where + " order by created_at desc limit :limit offset :offset")
                .params(params).query(this::mapProduct).list());
        return new ProductPage(items, total, q.page(), q.size());
    }

    @Override
    public boolean sellerSkuTaken(String orgId, String sellerSku) {
        return jdbc.sql("select exists(select 1 from product_variant where owner_org = :org and seller_sku = :sku)")
                .param("org", orgId).param("sku", sellerSku).query(Boolean.class).single();
    }

    private Optional<Product> one(String sql, Object value) {
        List<Product> found = jdbc.sql(sql).param("v", value).query(this::mapProduct).list();
        return withVariants(found).stream().findFirst();
    }

    private Map<String, Object> params(Product p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.id());
        m.put("atin", p.atin().value());
        m.put("ownerKind", p.owner().kind().name());
        m.put("ownerOrg", p.owner().orgId());
        m.put("categoryId", p.categoryId());
        m.put("categoryRequestId", p.categoryRequestId());
        m.put("productType", p.productTypeCode());
        m.put("title", p.title());
        m.put("attributes", json.write(p.attributes()));
        m.put("images", json.write(p.images()));
        m.put("theme", p.variationTheme());
        m.put("status", p.status().name());
        m.put("note", p.moderationNote());
        m.put("createdAt", Timestamp.from(p.createdAt()));
        m.put("updatedAt", Timestamp.from(p.updatedAt()));
        return m;
    }

    private void insertVariants(Product p) {
        int position = 0;
        for (Variant v : p.variants()) {
            jdbc.sql("""
                    insert into product_variant (id, product_id, atin, owner_org, seller_sku, gtin, gtin_exempt,
                                                 options, price_minor, currency, quantity, position)
                    values (:id, :productId, :atin, :org, :sku, :gtin, :exempt, cast(:options as jsonb),
                            :price, :currency, :quantity, :position)
                    """)
                    .param("id", v.id())
                    .param("productId", p.id())
                    .param("atin", v.atin().value())
                    .param("org", p.owner().orgId())
                    .param("sku", v.sellerSku())
                    .param("gtin", v.gtin())
                    .param("exempt", v.gtinExempt())
                    .param("options", json.write(v.options()))
                    .param("price", v.priceMinor())
                    .param("currency", v.currency())
                    .param("quantity", v.quantity())
                    .param("position", position++)
                    .update();
        }
    }

    private List<Product> withVariants(List<Product> products) {
        if (products.isEmpty()) {
            return products;
        }
        Map<UUID, List<Variant>> byProduct = new LinkedHashMap<>();
        jdbc.sql("""
                select id, product_id, atin, seller_sku, gtin, gtin_exempt, options::text as options, price_minor,
                       currency, quantity
                from product_variant where product_id in (:ids) order by position
                """)
                .param("ids", products.stream().map(Product::id).toList())
                .query((rs, n) -> {
                    Variant v = new Variant(rs.getObject("id", UUID.class), new Atin(rs.getString("atin")),
                            rs.getString("seller_sku"), rs.getString("gtin"), rs.getBoolean("gtin_exempt"),
                            json.stringMap(rs.getString("options")), rs.getLong("price_minor"),
                            rs.getString("currency"), rs.getInt("quantity"));
                    byProduct.computeIfAbsent(rs.getObject("product_id", UUID.class), k -> new ArrayList<>()).add(v);
                    return v;
                })
                .list();
        return products.stream()
                .map(p -> new Product(p.id(), p.atin(), p.owner(), p.categoryId(), p.categoryRequestId(),
                        p.productTypeCode(), p.title(), p.attributes(), p.images(), p.variationTheme(),
                        byProduct.getOrDefault(p.id(), List.of()), p.status(), p.moderationNote(), p.createdAt(),
                        p.updatedAt(), p.version()))
                .toList();
    }

    private Product mapProduct(ResultSet rs, int rowNum) throws SQLException {
        return new Product(
                rs.getObject("id", UUID.class),
                new Atin(rs.getString("atin")),
                new ListingOwner(ListingOwnerKind.valueOf(rs.getString("owner_kind")), rs.getString("owner_org")),
                rs.getObject("category_id", UUID.class),
                rs.getObject("category_request_id", UUID.class),
                rs.getString("product_type"),
                rs.getString("title"),
                json.stringMap(rs.getString("attributes")),
                json.images(rs.getString("images")),
                rs.getString("variation_theme"),
                List.of(),
                ProductStatus.valueOf(rs.getString("status")),
                rs.getString("moderation_note"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
