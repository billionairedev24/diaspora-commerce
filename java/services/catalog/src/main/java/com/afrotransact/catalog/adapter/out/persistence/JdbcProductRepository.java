package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
                   created_at, updated_at, version, preorder_eligible, ships_now, listing::text as listing
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
                                     created_at, updated_at, version, preorder_eligible, ships_now, listing)
                values (:id, :atin, :ownerKind, :ownerOrg, :categoryId, :categoryRequestId, :productType, :title,
                        cast(:attributes as jsonb), cast(:images as jsonb), :theme, :status, :note,
                        :createdAt, :updatedAt, 0, :preorderEligible, :shipsNow, cast(:listing as jsonb))
                """)
                .params(params(p))
                .update();
        insertVariants(p);
        return findById(p.id()).orElseThrow();
    }

    @Override
    public boolean mirrorVariantQuantity(String ownerOrg, String variantAtin, int quantity) {
        int rows = jdbc.sql("""
                update product_variant set quantity = :quantity
                where atin = :atin and quantity <> :quantity
                  and product_id in (select id from product where owner_org = :org)
                """)
                .param("quantity", quantity).param("atin", variantAtin).param("org", ownerOrg).update();
        if (rows == 0) {
            return false;
        }
        jdbc.sql("""
                update product set version = version + 1, updated_at = now()
                where id = (select product_id from product_variant where atin = :atin)
                """).param("atin", variantAtin).update();
        return true;
    }

    @Override
    public Product update(Product p) {
        Map<String, Object> params = params(p);
        params.put("version", p.version());
        int rows = jdbc.sql("""
                update product set category_id = :categoryId, product_type = :productType, title = :title,
                       attributes = cast(:attributes as jsonb), images = cast(:images as jsonb),
                       variation_theme = :theme, status = :status, moderation_note = :note,
                       preorder_eligible = :preorderEligible, ships_now = :shipsNow,
                       listing = cast(:listing as jsonb), updated_at = :updatedAt, version = version + 1
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
    public Product updateWithVariants(Product p) {
        Product current = findById(p.id()).orElseThrow();
        Set<UUID> kept = new HashSet<>();
        p.variants().forEach(v -> kept.add(v.id()));
        for (Variant old : current.variants()) {
            if (!kept.contains(old.id())) {
                jdbc.sql("delete from product_variant where id = :id").param("id", old.id()).update();
            }
        }
        Set<UUID> existing = new HashSet<>();
        current.variants().forEach(v -> existing.add(v.id()));
        int position = 0;
        for (Variant v : p.variants()) {
            if (existing.contains(v.id())) {
                jdbc.sql("""
                        update product_variant set seller_sku = :sku, gtin = :gtin, gtin_exempt = :exempt,
                               options = cast(:options as jsonb), price_minor = :price, currency = :currency,
                               quantity = :quantity, position = :position, compare_at_minor = :compareAt,
                               images = cast(:images as jsonb)
                        where id = :id
                        """)
                        .params(variantParams(p, v, position++))
                        .update();
            } else {
                insertVariant(p, v, position++);
            }
        }
        return update(p);
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
    public List<Product> findByOwnerOrgAfter(String orgId, UUID afterId, int limit) {
        String sql = SELECT + (afterId == null ? " where owner_org = :org" : " where owner_org = :org and id > :after")
                + " order by id limit :limit";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("org", orgId).param("limit", limit);
        if (afterId != null) {
            spec = spec.param("after", afterId);
        }
        return withVariants(spec.query(this::mapProduct).list());
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
    public Optional<Product> findBySellerSku(String orgId, String sellerSku) {
        return one(SELECT + " where id = (select product_id from product_variant where owner_org = :org and seller_sku = :v)",
                "org", orgId, sellerSku);
    }

    @Override
    public Map<String, SkuOwner> productsBySku(String orgId, Collection<String> sellerSkus) {
        Map<String, SkuOwner> out = new HashMap<>();
        List<String> all = List.copyOf(sellerSkus);
        for (int from = 0; from < all.size(); from += 1000) {
            jdbc.sql("""
                    select v.seller_sku, v.product_id, p.product_type from product_variant v
                    join product p on p.id = v.product_id
                    where v.owner_org = :org and v.seller_sku in (:skus)
                    """)
                    .param("org", orgId).param("skus", all.subList(from, Math.min(all.size(), from + 1000)))
                    .query((rs, n) -> out.put(rs.getString("seller_sku"),
                            new SkuOwner(rs.getObject("product_id", UUID.class), rs.getString("product_type"))))
                    .list();
        }
        return out;
    }

    @Override
    public Set<String> takenSellerSkus(String orgId, Collection<String> sellerSkus) {
        if (sellerSkus.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select seller_sku from product_variant where owner_org = :org and seller_sku in (:skus)")
                .param("org", orgId).param("skus", List.copyOf(sellerSkus)).query(String.class).list());
    }

    @Override
    public Set<String> existingGtins(Collection<String> gtins) {
        if (gtins.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select distinct gtin from product_variant where gtin in (:gtins)")
                .param("gtins", List.copyOf(gtins)).query(String.class).list());
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

    private Optional<Product> one(String sql, String name, Object param, Object value) {
        List<Product> found = jdbc.sql(sql).param(name, param).param("v", value).query(this::mapProduct).list();
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
        m.put("preorderEligible", p.preorderEligible());
        m.put("shipsNow", p.shipsNow());
        m.put("listing", json.write(p.details()));
        return m;
    }

    private void insertVariants(Product p) {
        int position = 0;
        for (Variant v : p.variants()) {
            insertVariant(p, v, position++);
        }
    }

    private void insertVariant(Product p, Variant v, int position) {
        jdbc.sql("""
                insert into product_variant (id, product_id, atin, owner_org, seller_sku, gtin, gtin_exempt,
                                             options, price_minor, currency, quantity, position, compare_at_minor,
                                             images)
                values (:id, :productId, :atin, :org, :sku, :gtin, :exempt, cast(:options as jsonb),
                        :price, :currency, :quantity, :position, :compareAt, cast(:images as jsonb))
                """)
                .params(variantParams(p, v, position))
                .update();
    }

    private Map<String, Object> variantParams(Product p, Variant v, int position) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", v.id());
        m.put("productId", p.id());
        m.put("atin", v.atin().value());
        m.put("org", p.owner().orgId());
        m.put("sku", v.sellerSku());
        m.put("gtin", v.gtin());
        m.put("exempt", v.gtinExempt());
        m.put("options", json.write(v.options()));
        m.put("price", v.priceMinor());
        m.put("currency", v.currency());
        m.put("quantity", v.quantity());
        m.put("position", position);
        m.put("compareAt", v.compareAtMinor());
        m.put("images", json.write(v.images()));
        return m;
    }

    private List<Product> withVariants(List<Product> products) {
        if (products.isEmpty()) {
            return products;
        }
        Map<UUID, List<Variant>> byProduct = new LinkedHashMap<>();
        jdbc.sql("""
                select id, product_id, atin, seller_sku, gtin, gtin_exempt, options::text as options, price_minor,
                       currency, quantity, compare_at_minor, images::text as images
                from product_variant where product_id in (:ids) order by position
                """)
                .param("ids", products.stream().map(Product::id).toList())
                .query((rs, n) -> {
                    Variant v = new Variant(rs.getObject("id", UUID.class), new Atin(rs.getString("atin")),
                            rs.getString("seller_sku"), rs.getString("gtin"), rs.getBoolean("gtin_exempt"),
                            json.stringMap(rs.getString("options")), rs.getLong("price_minor"),
                            rs.getString("currency"), rs.getInt("quantity"),
                            rs.getObject("compare_at_minor", Long.class), json.images(rs.getString("images")));
                    byProduct.computeIfAbsent(rs.getObject("product_id", UUID.class), k -> new ArrayList<>()).add(v);
                    return v;
                })
                .list();
        return products.stream()
                .map(p -> p.withVariants(byProduct.getOrDefault(p.id(), List.of())))
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
                rs.getLong("version"),
                rs.getBoolean("preorder_eligible"),
                rs.getBoolean("ships_now"),
                json.listing(rs.getString("listing")));
    }
}
