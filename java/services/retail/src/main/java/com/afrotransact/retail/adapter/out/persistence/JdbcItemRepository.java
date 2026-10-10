package com.afrotransact.retail.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.retail.domain.ListingStatus;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.StorageType;
import com.afrotransact.retail.domain.SupplierItem;
import com.afrotransact.retail.port.ItemRepository;
import com.afrotransact.retail.port.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcItemRepository implements ItemRepository {

    private static final TypeReference<List<SupplierItem>> SUPPLIER_ITEMS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcItemRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public RetailItem insert(RetailItem i) {
        jdbc.sql("""
                insert into retail_item (id, sku, item_reference, gtin, name, category, atin, primary_supplier_id,
                    supplier_items, origin_country, hs_code, duty_rate_percent, shelf_life_days, storage,
                    reorder_point, pack_size, inner_pack, case_pack, unit_weight_grams, standard_cost_minor,
                    price_minor, currency, listing_status, created_at, updated_at, version)
                values (:id, :sku, :ref, :gtin, :name, :category, :atin, :primary, cast(:supplierItems as jsonb),
                    :origin, :hs, :duty, :shelf, :storage, :reorder, :pack, :inner, :case, :weight, :cost, :price,
                    :currency, :listing, :createdAt, :updatedAt, 0)
                """)
                .params(params(i)).param("sku", i.sku()).param("ref", i.itemReference()).param("gtin", i.gtin())
                .param("origin", i.originCountry()).param("currency", i.currency())
                .param("createdAt", Sql.ts(i.createdAt()))
                .update();
        return findById(i.id()).orElseThrow();
    }

    @Override
    public RetailItem update(RetailItem i) {
        int rows = jdbc.sql("""
                update retail_item set name = :name, category = :category, atin = :atin,
                       primary_supplier_id = :primary, supplier_items = cast(:supplierItems as jsonb), hs_code = :hs,
                       duty_rate_percent = :duty, shelf_life_days = :shelf, storage = :storage,
                       reorder_point = :reorder, pack_size = :pack, inner_pack = :inner, case_pack = :case,
                       unit_weight_grams = :weight, standard_cost_minor = :cost, price_minor = :price,
                       listing_status = :listing, updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .params(params(i)).param("version", i.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("retail.concurrent-update", "Item " + i.sku() + " changed; reload");
        }
        return findById(i.id()).orElseThrow();
    }

    @Override
    public Optional<RetailItem> findById(UUID id) {
        return jdbc.sql("select * from retail_item where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<RetailItem> findBySku(String sku) {
        return jdbc.sql("select * from retail_item where sku = :sku").param("sku", sku).query(this::map).optional();
    }

    @Override
    public Optional<RetailItem> findByAtin(String atin) {
        return jdbc.sql("select * from retail_item where atin = :atin").param("atin", atin).query(this::map)
                .optional();
    }

    @Override
    public List<RetailItem> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select * from retail_item where id in (:ids)").param("ids", ids).query(this::map).list();
    }

    @Override
    public List<RetailItem> findBySkus(Collection<String> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select * from retail_item where sku in (:skus)").param("skus", skus).query(this::map)
                .list();
    }

    @Override
    public Page<RetailItem> page(String query, UUID supplierId, ListingStatus listing, int page, int size) {
        Sql.Where where = new Sql.Where().eq("listing_status", "listing", Sql.name(listing))
                .raw("supplier_items @> cast(:supplier as jsonb)", "supplier",
                        supplierId == null ? null : "[{\"supplierId\":\"" + supplierId + "\"}]")
                .raw("(lower(name) like :q or lower(sku) like :q or gtin like :q or atin like upper(:q))", "q",
                        query == null || query.isBlank() ? null : "%" + query.toLowerCase(Locale.ROOT).trim() + "%");
        long total = jdbc.sql("select count(*) from retail_item" + where.sql()).params(where.params())
                .query(Long.class).single();
        List<RetailItem> items = jdbc.sql("select * from retail_item" + where.sql()
                        + " order by sku limit :limit offset :offset")
                .params(where.params()).param("limit", size).param("offset", (long) page * size)
                .query(this::map).list();
        return new Page<>(items, total, page, size);
    }

    private Map<String, Object> params(RetailItem i) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", i.id());
        p.put("name", i.name());
        p.put("category", i.category());
        p.put("atin", i.atin());
        p.put("primary", i.primarySupplierId());
        p.put("supplierItems", json.write(i.supplierItems()));
        p.put("hs", i.hsCode());
        p.put("duty", i.dutyRatePercent());
        p.put("shelf", i.shelfLifeDays());
        p.put("storage", i.storage().name());
        p.put("reorder", i.reorderPoint());
        p.put("pack", i.packSize());
        p.put("inner", i.innerPack());
        p.put("case", i.casePack());
        p.put("weight", i.unitWeightGrams());
        p.put("cost", i.standardCostMinor());
        p.put("price", i.priceMinor());
        p.put("listing", i.listingStatus().name());
        p.put("updatedAt", Sql.ts(i.updatedAt()));
        return p;
    }

    private RetailItem map(ResultSet rs, int rowNum) throws SQLException {
        return new RetailItem(rs.getObject("id", UUID.class), rs.getString("sku"), rs.getLong("item_reference"),
                rs.getString("gtin"), rs.getString("name"), rs.getString("category"), rs.getString("atin"),
                rs.getObject("primary_supplier_id", UUID.class), json.list(rs.getString("supplier_items"),
                SUPPLIER_ITEMS), rs.getString("origin_country"), rs.getString("hs_code"),
                rs.getBigDecimal("duty_rate_percent").stripTrailingZeros(), rs.getInt("shelf_life_days"),
                StorageType.valueOf(rs.getString("storage")), rs.getInt("reorder_point"), rs.getString("pack_size"),
                rs.getInt("inner_pack"), rs.getInt("case_pack"), rs.getInt("unit_weight_grams"),
                rs.getLong("standard_cost_minor"), rs.getLong("price_minor"), rs.getString("currency"),
                ListingStatus.valueOf(rs.getString("listing_status")), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
