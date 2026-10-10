package com.afrotransact.offers.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.domain.Coupon;
import com.afrotransact.offers.domain.CouponFunding;
import com.afrotransact.offers.domain.CouponRedemption;
import com.afrotransact.offers.domain.CouponScope;
import com.afrotransact.offers.domain.CouponStatus;
import com.afrotransact.offers.domain.CouponType;
import com.afrotransact.offers.domain.RedemptionStatus;
import com.afrotransact.offers.port.CouponRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCouponRepository implements CouponRepository {

    private static final String SELECT = "select * from coupon";
    private static final String SELECT_REDEMPTION = """
            select id, coupon_id, code, customer_id, order_id, idempotency_key, fingerprint, currency, discount_minor,
                   shipping_discount_minor, funding, funded_by_org, lines::text as lines, status, created_at,
                   released_at
            from coupon_redemption
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcCouponRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Coupon insert(Coupon c) {
        try {
            jdbc.sql("""
                    insert into coupon (id, code, description, type, value, currency, max_discount_minor, scope,
                                        scope_ref, min_spend_minor, total_limit, per_customer_limit, clip_required,
                                        funding, funded_by_org, starts_at, ends_at, status, redeemed_count, created_by,
                                        created_at, updated_at, version)
                    values (:id, :code, :description, :type, :value, :currency, :maxDiscount, :scope, :scopeRef,
                            :minSpend, :totalLimit, :perCustomer, :clip, :funding, :fundedBy, :startsAt, :endsAt,
                            :status, :redeemed, :createdBy, :createdAt, :updatedAt, 0)
                    """)
                    .params(params(c))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("offers.coupon-code-taken", "Coupon code " + c.code() + " already exists");
        }
        return findById(c.id()).orElseThrow();
    }

    @Override
    public Coupon update(Coupon c) {
        int rows = jdbc.sql("""
                update coupon set status = :status, redeemed_count = :redeemed, updated_at = :updatedAt,
                       version = version + 1
                where id = :id and version = :version
                """)
                .params(params(c))
                .param("version", c.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("offers.concurrent-update", "Coupon changed; reload and retry");
        }
        return findById(c.id()).orElseThrow();
    }

    private static Map<String, Object> params(Coupon c) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", c.id());
        p.put("code", c.code());
        p.put("description", c.description());
        p.put("type", c.type().name());
        p.put("value", c.value());
        p.put("currency", c.currency());
        p.put("maxDiscount", c.maxDiscountMinor());
        p.put("scope", c.scope().name());
        p.put("scopeRef", c.scopeRef());
        p.put("minSpend", c.minSpendMinor());
        p.put("totalLimit", c.totalLimit());
        p.put("perCustomer", c.perCustomerLimit());
        p.put("clip", c.clipRequired());
        p.put("funding", c.funding().name());
        p.put("fundedBy", c.fundedByOrg());
        p.put("startsAt", Timestamp.from(c.startsAt()));
        p.put("endsAt", c.endsAt() == null ? null : Timestamp.from(c.endsAt()));
        p.put("status", c.status().name());
        p.put("redeemed", c.redeemedCount());
        p.put("createdBy", c.createdBy());
        p.put("createdAt", Timestamp.from(c.createdAt()));
        p.put("updatedAt", Timestamp.from(c.updatedAt()));
        return p;
    }

    @Override
    public Optional<Coupon> findById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(JdbcCouponRepository::map).optional();
    }

    @Override
    public Optional<Coupon> findByCode(String code) {
        return jdbc.sql(SELECT + " where code = :code").param("code", code).query(JdbcCouponRepository::map)
                .optional();
    }

    @Override
    public Optional<Coupon> lockByCode(String code) {
        return jdbc.sql(SELECT + " where code = :code for update").param("code", code)
                .query(JdbcCouponRepository::map).optional();
    }

    @Override
    public Optional<Coupon> lockById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(JdbcCouponRepository::map)
                .optional();
    }

    @Override
    public List<Coupon> list(CouponFunding funding, String fundedByOrg, CouponStatus status) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> p = new HashMap<>();
        if (funding != null) {
            where.append(" and funding = :funding");
            p.put("funding", funding.name());
        }
        if (fundedByOrg != null) {
            where.append(" and funded_by_org = :org");
            p.put("org", fundedByOrg);
        }
        if (status != null) {
            where.append(" and status = :status");
            p.put("status", status.name());
        }
        return jdbc.sql(SELECT + where + " order by created_at desc limit 500").params(p)
                .query(JdbcCouponRepository::map).list();
    }

    @Override
    public List<Coupon> findClippable(String currency, Instant now) {
        return jdbc.sql(SELECT + """
                 where clip_required and status = 'ACTIVE' and currency = :currency and starts_at <= :now
                   and (ends_at is null or ends_at > :now)
                 order by created_at
                """)
                .param("currency", currency).param("now", Timestamp.from(now))
                .query(JdbcCouponRepository::map).list();
    }

    @Override
    public boolean clip(UUID couponId, String customerId, Instant now) {
        return jdbc.sql("""
                insert into coupon_clip (coupon_id, customer_id, clipped_at) values (:coupon, :customer, :now)
                on conflict do nothing
                """)
                .param("coupon", couponId).param("customer", customerId).param("now", Timestamp.from(now))
                .update() == 1;
    }

    @Override
    public boolean clipped(UUID couponId, String customerId) {
        return jdbc.sql("select count(*) from coupon_clip where coupon_id = :coupon and customer_id = :customer")
                .param("coupon", couponId).param("customer", customerId).query(Long.class).single() > 0;
    }

    @Override
    public long redeemedCount(UUID couponId) {
        return jdbc.sql("select count(*) from coupon_redemption where coupon_id = :coupon and status = 'REDEEMED'")
                .param("coupon", couponId).query(Long.class).single();
    }

    @Override
    public long redeemedCount(UUID couponId, String customerId) {
        return jdbc.sql("""
                select count(*) from coupon_redemption
                where coupon_id = :coupon and customer_id = :customer and status = 'REDEEMED'
                """)
                .param("coupon", couponId).param("customer", customerId).query(Long.class).single();
    }

    @Override
    public Optional<CouponRedemption> findRedemption(UUID id) {
        return jdbc.sql(SELECT_REDEMPTION + " where id = :id").param("id", id).query(this::mapRedemption).optional();
    }

    @Override
    public Optional<CouponRedemption> findRedemptionByKey(String idempotencyKey) {
        return jdbc.sql(SELECT_REDEMPTION + " where idempotency_key = :key").param("key", idempotencyKey)
                .query(this::mapRedemption).optional();
    }

    @Override
    public CouponRedemption insertRedemption(CouponRedemption r) {
        try {
            jdbc.sql("""
                    insert into coupon_redemption (id, coupon_id, code, customer_id, order_id, idempotency_key,
                                                   fingerprint, currency, discount_minor, shipping_discount_minor,
                                                   funding, funded_by_org, lines, status, created_at, released_at)
                    values (:id, :coupon, :code, :customer, :order, :key, :fp, :currency, :discount, :shipping,
                            :funding, :fundedBy, cast(:lines as jsonb), :status, :createdAt, :releasedAt)
                    """)
                    .param("id", r.id())
                    .param("coupon", r.couponId())
                    .param("code", r.code())
                    .param("customer", r.customerId())
                    .param("order", r.orderId())
                    .param("key", r.idempotencyKey())
                    .param("fp", r.fingerprint())
                    .param("currency", r.currency())
                    .param("discount", r.discountMinor())
                    .param("shipping", r.shippingDiscountMinor())
                    .param("funding", r.funding().name())
                    .param("fundedBy", r.fundedByOrg())
                    .param("lines", json.write(r.lines()))
                    .param("status", r.status().name())
                    .param("createdAt", Timestamp.from(r.createdAt()))
                    .param("releasedAt", r.releasedAt() == null ? null : Timestamp.from(r.releasedAt()))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("offers.idempotency-key-reused",
                    "Idempotency-Key " + r.idempotencyKey() + " was already used for a different request");
        }
        return findRedemption(r.id()).orElseThrow();
    }

    @Override
    public CouponRedemption updateRedemption(CouponRedemption r) {
        jdbc.sql("update coupon_redemption set status = :status, released_at = :releasedAt where id = :id")
                .param("status", r.status().name())
                .param("releasedAt", r.releasedAt() == null ? null : Timestamp.from(r.releasedAt()))
                .param("id", r.id())
                .update();
        return findRedemption(r.id()).orElseThrow();
    }

    private static Coupon map(ResultSet rs, int rowNum) throws SQLException {
        long maxDiscount = rs.getLong("max_discount_minor");
        Long cap = rs.wasNull() ? null : maxDiscount;
        Timestamp ends = rs.getTimestamp("ends_at");
        return new Coupon(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("description"),
                CouponType.valueOf(rs.getString("type")), rs.getLong("value"), rs.getString("currency"), cap,
                CouponScope.valueOf(rs.getString("scope")), rs.getString("scope_ref"), rs.getLong("min_spend_minor"),
                rs.getInt("total_limit"), rs.getInt("per_customer_limit"), rs.getBoolean("clip_required"),
                CouponFunding.valueOf(rs.getString("funding")), rs.getString("funded_by_org"),
                rs.getTimestamp("starts_at").toInstant(), ends == null ? null : ends.toInstant(),
                CouponStatus.valueOf(rs.getString("status")), rs.getLong("redeemed_count"),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }

    private CouponRedemption mapRedemption(ResultSet rs, int rowNum) throws SQLException {
        Timestamp released = rs.getTimestamp("released_at");
        return new CouponRedemption(rs.getObject("id", UUID.class), rs.getObject("coupon_id", UUID.class),
                rs.getString("code"), rs.getString("customer_id"), rs.getString("order_id"),
                rs.getString("idempotency_key"), rs.getString("fingerprint"), rs.getString("currency"),
                rs.getLong("discount_minor"), rs.getLong("shipping_discount_minor"),
                CouponFunding.valueOf(rs.getString("funding")), rs.getString("funded_by_org"),
                json.lines(rs.getString("lines")), RedemptionStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(), released == null ? null : released.toInstant());
    }
}
