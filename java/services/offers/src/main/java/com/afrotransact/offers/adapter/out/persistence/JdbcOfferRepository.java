package com.afrotransact.offers.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOfferRepository implements OfferRepository {

    private static final String SELECT = """
            select id, atin, product_atin, product_id, gtin, category_id, seller_org, seller_kind, seller_sku,
                   inventory_sku, item_condition, price_minor, list_price_minor, currency, shipping_minor, fulfilment,
                   seller_quantity, handling_days, ship_from::text as ship_from, status, product_live, created_at,
                   updated_at, version
            from offer
            """;
    private static final String OPEN = " status <> 'WITHDRAWN' ";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcOfferRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Offer insert(Offer o) {
        try {
            jdbc.sql("""
                    insert into offer (id, atin, product_atin, product_id, gtin, category_id, seller_org, seller_kind,
                                       seller_sku, inventory_sku, item_condition, price_minor, list_price_minor,
                                       currency, shipping_minor, fulfilment, seller_quantity, handling_days, ship_from,
                                       status, product_live, created_at, updated_at, version)
                    values (:id, :atin, :productAtin, :productId, :gtin, :categoryId, :org, :kind, :sku, :invSku,
                            :condition, :price, :listPrice, :currency, :shipping, :fulfilment, :qty, :handling,
                            cast(:shipFrom as jsonb), :status, :live, :createdAt, :updatedAt, 0)
                    """)
                    .params(params(o))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("offers.offer-exists",
                    "An open offer with this seller, ATIN and condition (or SKU) already exists");
        }
        return findById(o.id()).orElseThrow();
    }

    @Override
    public Offer update(Offer o) {
        int rows;
        try {
            rows = jdbc.sql("""
                    update offer set item_condition = :condition, price_minor = :price, list_price_minor = :listPrice,
                           shipping_minor = :shipping, fulfilment = :fulfilment, seller_quantity = :qty,
                           handling_days = :handling, ship_from = cast(:shipFrom as jsonb), inventory_sku = :invSku,
                           status = :status, product_live = :live, updated_at = :updatedAt, version = version + 1
                    where id = :id and version = :version
                    """)
                    .params(params(o))
                    .param("version", o.version())
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("offers.offer-exists",
                    "Another open offer of yours already uses this ATIN and condition");
        }
        if (rows == 0) {
            throw new ConflictException("offers.concurrent-update", "Offer changed; reload and retry");
        }
        return findById(o.id()).orElseThrow();
    }

    private Map<String, Object> params(Offer o) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", o.id());
        p.put("atin", o.atin());
        p.put("productAtin", o.productAtin());
        p.put("productId", o.productId());
        p.put("gtin", o.gtin());
        p.put("categoryId", o.categoryId());
        p.put("org", o.sellerOrgId());
        p.put("kind", o.sellerKind().name());
        p.put("sku", o.sellerSku());
        p.put("invSku", o.inventorySku());
        p.put("condition", o.condition().name());
        p.put("price", o.priceMinor());
        p.put("listPrice", o.listPriceMinor());
        p.put("currency", o.currency());
        p.put("shipping", o.shippingMinor());
        p.put("fulfilment", o.fulfilment().name());
        p.put("qty", o.sellerQuantity());
        p.put("handling", o.handlingDays());
        p.put("shipFrom", json.write(o.shipFrom()));
        p.put("status", o.status().name());
        p.put("live", o.productLive());
        p.put("createdAt", Timestamp.from(o.createdAt()));
        p.put("updatedAt", Timestamp.from(o.updatedAt()));
        return p;
    }

    @Override
    public Optional<Offer> findById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public List<Offer> findOpenByAtins(Collection<String> atins) {
        if (atins.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " where atin in (:atins) and" + OPEN + "order by created_at, id")
                .param("atins", List.copyOf(atins)).query(this::map).list();
    }

    @Override
    public List<Offer> findOpenByProductAtin(String productAtin) {
        return jdbc.sql(SELECT + " where product_atin = :p and" + OPEN + "order by created_at, id")
                .param("p", productAtin).query(this::map).list();
    }

    @Override
    public List<Offer> findOpenByProductAtins(Collection<String> productAtins) {
        if (productAtins.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " where product_atin in (:p) and" + OPEN + "order by created_at, id")
                .param("p", List.copyOf(productAtins)).query(this::map).list();
    }

    @Override
    public Optional<Offer> findOpen(String sellerOrgId, String atin, OfferCondition condition) {
        return jdbc.sql(SELECT + " where seller_org = :org and atin = :atin and item_condition = :c and" + OPEN)
                .param("org", sellerOrgId).param("atin", atin).param("c", condition.name())
                .query(this::map).optional();
    }

    @Override
    public boolean sellerSkuTaken(String sellerOrgId, String sellerSku) {
        return jdbc.sql("select count(*) from offer where seller_org = :org and seller_sku = :sku and" + OPEN)
                .param("org", sellerOrgId).param("sku", sellerSku).query(Long.class).single() > 0;
    }

    @Override
    public List<Offer> findOpenBySellerSku(String sellerOrgId, String sellerSku) {
        return jdbc.sql(SELECT + " where seller_org = :org and seller_sku = :sku and" + OPEN)
                .param("org", sellerOrgId).param("sku", sellerSku).query(this::map).list();
    }

    @Override
    public List<Offer> findOpenByInventorySku(String sellerOrgId, String inventorySku) {
        return jdbc.sql(SELECT + " where seller_org = :org and inventory_sku = :sku and fulfilment = 'AFROTRANSACT' and"
                        + OPEN)
                .param("org", sellerOrgId).param("sku", inventorySku).query(this::map).list();
    }

    @Override
    public List<String> openAtinsOfSeller(String sellerOrgId) {
        return jdbc.sql("select distinct atin from offer where seller_org = :org and" + OPEN + "order by atin")
                .param("org", sellerOrgId).query(String.class).list();
    }

    @Override
    public OfferPage search(OfferQuery q) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> p = new HashMap<>();
        if (q.sellerOrgId() != null) {
            where.append(" and seller_org = :org");
            p.put("org", q.sellerOrgId());
        }
        if (q.atin() != null) {
            where.append(" and (atin = :atin or product_atin = :atin)");
            p.put("atin", q.atin());
        }
        if (q.status() != null) {
            where.append(" and status = :status");
            p.put("status", q.status().name());
        }
        long total = jdbc.sql("select count(*) from offer" + where).params(p).query(Long.class).single();
        p.put("limit", q.size());
        p.put("offset", (long) q.page() * q.size());
        List<Offer> items = jdbc.sql(SELECT + where + " order by created_at desc, id limit :limit offset :offset")
                .params(p).query(this::map).list();
        return new OfferPage(items, total, q.page(), q.size());
    }

    private Offer map(ResultSet rs, int rowNum) throws SQLException {
        long listPrice = rs.getLong("list_price_minor");
        Long list = rs.wasNull() ? null : listPrice;
        return new Offer(rs.getObject("id", UUID.class), rs.getString("atin"), rs.getString("product_atin"),
                rs.getObject("product_id", UUID.class), rs.getString("gtin"), rs.getObject("category_id", UUID.class),
                rs.getString("seller_org"), SellerKind.valueOf(rs.getString("seller_kind")),
                rs.getString("seller_sku"), rs.getString("inventory_sku"),
                OfferCondition.valueOf(rs.getString("item_condition")), rs.getLong("price_minor"), list,
                rs.getString("currency"), rs.getLong("shipping_minor"),
                Fulfilment.valueOf(rs.getString("fulfilment")), rs.getInt("seller_quantity"),
                rs.getInt("handling_days"), json.shipFrom(rs.getString("ship_from")),
                OfferStatus.valueOf(rs.getString("status")), rs.getBoolean("product_live"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
