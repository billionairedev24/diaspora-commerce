package com.afrotransact.taxreturns.adapter.out.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.afrotransact.taxreturns.domain.tax.ClassificationRule;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxClassification;
import com.afrotransact.taxreturns.domain.tax.TaxCode;
import com.afrotransact.taxreturns.port.TaxClassificationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTaxClassificationRepository implements TaxClassificationRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcTaxClassificationRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<TaxCode> codes() {
        return jdbc.sql("select * from tax_code order by code")
                .query((rs, n) -> new TaxCode(rs.getString("code"), rs.getString("label"),
                        TaxCategory.parse(rs.getString("category")), rs.getString("provider_code"),
                        rs.getBoolean("active")))
                .list();
    }

    @Override
    public TaxCode upsertCode(TaxCode c) {
        jdbc.sql("""
                insert into tax_code (code, label, category, provider_code, active, updated_at)
                values (:code, :label, :category, :provider, :active, now())
                on conflict (code) do update set label = excluded.label, category = excluded.category,
                    provider_code = excluded.provider_code, active = excluded.active, updated_at = now()
                """)
                .param("code", c.code()).param("label", c.label()).param("category", c.category().name())
                .param("provider", c.providerCode()).param("active", c.active()).update();
        return c;
    }

    @Override
    public List<ClassificationRule> rules() {
        return jdbc.sql("select * from tax_classification_rule order by product_type, attr_key nulls first, attr_value")
                .query((rs, n) -> new ClassificationRule(rs.getLong("id"), rs.getString("product_type"),
                        rs.getString("attr_key"), rs.getString("attr_value"), rs.getString("tax_code"),
                        rs.getString("note")))
                .list();
    }

    @Override
    public ClassificationRule upsertRule(ClassificationRule r) {
        long id = jdbc.sql("""
                insert into tax_classification_rule (product_type, attr_key, attr_value, tax_code, note, updated_at)
                values (:type, :key, :value, :code, :note, now())
                on conflict (product_type, coalesce(attr_key, ''), coalesce(lower(attr_value), ''))
                do update set tax_code = excluded.tax_code, note = excluded.note, updated_at = now()
                returning id
                """)
                .param("type", r.productType().toLowerCase(Locale.ROOT)).param("key", r.attrKey())
                .param("value", r.attrValue()).param("code", r.taxCode()).param("note", r.note())
                .query(Long.class).single();
        return new ClassificationRule(id, r.productType(), r.attrKey(), r.attrValue(), r.taxCode(), r.note());
    }

    @Override
    public boolean deleteRule(long id) {
        return jdbc.sql("delete from tax_classification_rule where id = :id").param("id", id).update() > 0;
    }

    @Override
    public void recordSeen(String productType, Map<String, String> attributes, TaxClassification c,
                           String sampleAtin) {
        Map<String, String> sorted = new TreeMap<>(attributes);
        jdbc.sql("""
                insert into tax_classification_seen (product_type, attributes, attr_hash, tax_code, basis, sample_atin)
                values (:type, cast(:attrs as jsonb), :hash, :code, :basis, :atin)
                on conflict (product_type, attr_hash) do update set seen_count = tax_classification_seen.seen_count + 1,
                    last_seen = now(), tax_code = excluded.tax_code, basis = excluded.basis
                """)
                .param("type", productType == null ? "" : productType).param("attrs", json.write(sorted))
                .param("hash", hash(sorted)).param("code", c.taxCode()).param("basis", c.basis().name())
                .param("atin", sampleAtin).update();
    }

    @Override
    public List<Seen> reviewQueue(boolean includeReviewed, int limit) {
        return jdbc.sql("select * from tax_classification_seen where basis = 'DEFAULT'"
                + (includeReviewed ? "" : " and reviewed_at is null") + " order by seen_count desc, last_seen desc limit :n")
                .param("n", limit)
                .query((rs, n) -> new Seen(rs.getString("product_type"), json.readStringMap(rs.getString("attributes")),
                        rs.getString("attr_hash"), rs.getString("tax_code"), rs.getString("basis"),
                        rs.getString("sample_atin"), rs.getLong("seen_count"), rs.getTimestamp("first_seen").toInstant(),
                        rs.getTimestamp("last_seen").toInstant(), instant(rs.getTimestamp("reviewed_at")),
                        rs.getString("reviewed_by"), rs.getString("review_note")))
                .list();
    }

    @Override
    public boolean markReviewed(String productType, String attrHash, String reviewer, String note) {
        return jdbc.sql("""
                update tax_classification_seen set reviewed_at = now(), reviewed_by = :by, review_note = :note
                where product_type = :type and attr_hash = :hash
                """)
                .param("by", reviewer).param("note", note).param("type", productType).param("hash", attrHash)
                .update() > 0;
    }

    @Override
    public void tagLine(String lineId, String taxCode) {
        jdbc.sql("update order_line_snapshot set tax_code = :code where line_id = :id")
                .param("code", taxCode).param("id", lineId).update();
    }

    @Override
    public List<CodeRow> liabilityByCode(Instant from, Instant to) {
        return jdbc.sql("""
                select coalesce(l.tax_code, 'UNTAGGED') as tax_code, t.jurisdiction, t.currency,
                       sum(t.taxable_minor) as taxable, sum(t.tax_minor) as tax
                from tax_transaction t
                left join order_line_snapshot l on l.line_id = t.line_id
                where t.voided = false and t.occurred_at >= :from and t.occurred_at < :to
                group by 1, 2, 3 order by 2, 1
                """)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> new CodeRow(rs.getString("tax_code"), rs.getString("jurisdiction"),
                        rs.getString("currency"), rs.getLong("taxable"), rs.getLong("tax")))
                .list();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String hash(Map<String, String> sorted) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sorted.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
