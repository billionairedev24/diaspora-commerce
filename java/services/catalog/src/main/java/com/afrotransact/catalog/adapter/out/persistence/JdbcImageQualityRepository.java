package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.images.ImageFinding;
import com.afrotransact.catalog.domain.images.ImageQuality;
import com.afrotransact.catalog.domain.images.ImageQualityRecord;
import com.afrotransact.catalog.port.ImageQualityRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcImageQualityRepository implements ImageQualityRepository {

    private static final TypeReference<List<ImageFinding>> FINDINGS = new TypeReference<>() { };
    private static final String SELECT = """
            select q.product_id, q.owner_org, p.atin, p.title, q.state, q.reasons::text as reasons, q.main_image_url,
                   q.checked_at, q.failing_since, q.hidden_at
            from product_image_quality q join product p on p.id = q.product_id
            """;

    private final JdbcClient jdbc;
    private final JsonCodec codec;
    private final JsonMapper mapper;

    JdbcImageQualityRepository(JdbcClient jdbc, JsonCodec codec, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.mapper = mapper;
    }

    @Override
    public List<Candidate> candidates(Instant staleBefore, int limit) {
        // A listing whose main picture was replaced since it was measured is picked up again at once, so a seller who
        // fixes the image is not left waiting for the periodic re-check (and a hidden listing comes back).
        return jdbc.sql("""
                select p.id, p.owner_org, p.product_type, p.title, p.atin, p.images::text as images
                from product p
                left join product_image_quality q on q.product_id = p.id
                left join lateral (select e ->> 'url' as url
                                   from jsonb_array_elements(p.images) with ordinality as t(e, ord)
                                   where coalesce(e ->> 'role', '') <> 'LABEL' order by ord limit 1) m on true
                where p.status in ('LIVE', 'PAUSED')
                  and (q.product_id is null or q.checked_at < :stale or q.main_image_url is distinct from m.url)
                order by q.checked_at nulls first, p.id
                limit :limit
                """).param("stale", Timestamp.from(staleBefore)).param("limit", limit)
                .query((rs, n) -> new Candidate(rs.getObject("id", UUID.class), rs.getString("owner_org"),
                        rs.getString("product_type"), rs.getString("title"), rs.getString("atin").trim(),
                        mainImage(codec.images(rs.getString("images")))))
                .list();
    }

    /** The first picture that is not the label photo. */
    private static String mainImage(List<ProductImage> images) {
        return images.stream().filter(i -> !i.isLabel()).map(ProductImage::url).findFirst().orElse(null);
    }

    @Override
    public long unscored() {
        return jdbc.sql("""
                select count(*) from product p left join product_image_quality q on q.product_id = p.id
                where p.status in ('LIVE', 'PAUSED') and q.product_id is null
                """).query(Long.class).single();
    }

    @Override
    public Optional<ImageQualityRecord> find(UUID productId) {
        return jdbc.sql(SELECT + " where q.product_id = :id").param("id", productId).query(this::map).optional();
    }

    @Override
    public void save(ImageQualityRecord r) {
        jdbc.sql("""
                insert into product_image_quality (product_id, owner_org, state, reasons, main_image_url, checked_at,
                                                   failing_since, hidden_at)
                values (:id, :org, :state, cast(:reasons as jsonb), :url, :checked, :failing, :hidden)
                on conflict (product_id) do update set owner_org = excluded.owner_org, state = excluded.state,
                    reasons = excluded.reasons, main_image_url = excluded.main_image_url,
                    checked_at = excluded.checked_at, failing_since = excluded.failing_since,
                    hidden_at = excluded.hidden_at
                """)
                .param("id", r.productId()).param("org", r.ownerOrg()).param("state", r.state().name())
                .param("reasons", mapper.writeValueAsString(r.reasons())).param("url", r.mainImageUrl())
                .param("checked", Timestamp.from(r.checkedAt()))
                .param("failing", r.failingSince() == null ? null : Timestamp.from(r.failingSince()))
                .param("hidden", r.hiddenAt() == null ? null : Timestamp.from(r.hiddenAt()))
                .update();
    }

    @Override
    public void remove(UUID productId) {
        jdbc.sql("delete from product_image_quality where product_id = :id").param("id", productId).update();
    }

    @Override
    public List<ImageQualityRecord> tasks(String ownerOrg) {
        return jdbc.sql(SELECT + """
                where q.owner_org = :org and q.state in ('IMPROVE', 'FAIL') and p.status in ('LIVE', 'PAUSED')
                order by case q.state when 'FAIL' then 0 else 1 end, q.failing_since nulls last, p.title
                """).param("org", ownerOrg).query(this::map).list();
    }

    @Override
    public Map<ImageQuality, Long> counts() {
        Map<ImageQuality, Long> out = ImageQualityRepository.emptyCounts();
        jdbc.sql("""
                select q.state, count(*) as n from product_image_quality q join product p on p.id = q.product_id
                where p.status in ('LIVE', 'PAUSED') group by q.state
                """).query((rs, n) -> out.put(ImageQuality.valueOf(rs.getString("state")), rs.getLong("n"))).list();
        return out;
    }

    @Override
    public List<ImageQualityRecord> failingSince(Instant cutoff, int limit) {
        return jdbc.sql(SELECT + """
                where q.state = 'FAIL' and q.hidden_at is null and q.failing_since <= :cutoff and p.status = 'LIVE'
                order by q.failing_since, q.product_id limit :limit
                """).param("cutoff", Timestamp.from(cutoff)).param("limit", limit).query(this::map).list();
    }

    @Override
    public long countDue(Instant cutoff) {
        return jdbc.sql("""
                select count(*) from product_image_quality q join product p on p.id = q.product_id
                where q.state = 'FAIL' and q.hidden_at is null and q.failing_since <= :cutoff and p.status = 'LIVE'
                """).param("cutoff", Timestamp.from(cutoff)).query(Long.class).single();
    }

    @Override
    public List<ImageQualityRecord> failing(int limit) {
        return jdbc.sql(SELECT + """
                where q.state = 'FAIL' and p.status in ('LIVE', 'PAUSED')
                order by q.failing_since, q.product_id limit :limit
                """).param("limit", limit).query(this::map).list();
    }

    @Override
    public void markHidden(UUID productId, Instant at) {
        jdbc.sql("update product_image_quality set hidden_at = :at where product_id = :id and hidden_at is null")
                .param("at", Timestamp.from(at)).param("id", productId).update();
    }

    @Override
    public Set<UUID> hidden(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select product_id from product_image_quality where hidden_at is not null "
                + "and product_id in (:ids)").param("ids", productIds).query(UUID.class).list());
    }

    private ImageQualityRecord map(ResultSet rs, int row) throws SQLException {
        Timestamp failing = rs.getTimestamp("failing_since");
        Timestamp hidden = rs.getTimestamp("hidden_at");
        return new ImageQualityRecord(rs.getObject("product_id", UUID.class), rs.getString("owner_org"),
                rs.getString("atin").trim(), rs.getString("title"), ImageQuality.valueOf(rs.getString("state")),
                mapper.readValue(rs.getString("reasons"), FINDINGS), rs.getString("main_image_url"),
                rs.getTimestamp("checked_at").toInstant(), failing == null ? null : failing.toInstant(),
                hidden == null ? null : hidden.toInstant());
    }
}
