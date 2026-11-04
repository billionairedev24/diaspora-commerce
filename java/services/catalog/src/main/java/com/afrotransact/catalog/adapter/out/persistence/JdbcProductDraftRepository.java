package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.DraftListItem;
import com.afrotransact.catalog.domain.SavedDraft;
import com.afrotransact.catalog.port.ProductDraftRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProductDraftRepository implements ProductDraftRepository {

    private static final String SELECT = """
            select id, owner_org, created_by, product_id, title, category_path, payload::text as payload, created_at,
                   updated_at, version
            from product_draft
            """;

    /**
     * How many of the five basics the saved payload lacks (see {@link DraftListItem#CHECKS}): a category (or a
     * requested one), a title, a photo, a price (single offer or any variant row) and a 40-character description.
     */
    private static final String TODO = """
            (case when coalesce(payload->>'categoryId', '') = '' and coalesce(payload#>>'{request,id}', '') = ''
                  then 1 else 0 end
             + case when btrim(coalesce(title, '')) = '' then 1 else 0 end
             + case when jsonb_typeof(payload->'images') is distinct from 'array'
                         or jsonb_array_length(payload->'images') = 0 then 1 else 0 end
             + case when coalesce(payload#>>'{single,price}', '') = ''
                         and not exists (select 1 from jsonb_each(case when jsonb_typeof(payload->'rows') = 'object'
                                                                       then payload->'rows' else '{}'::jsonb end) r
                                         where coalesce(r.value->>'price', '') <> '') then 1 else 0 end
             + case when length(btrim(coalesce(payload->>'description', ''))) < 40 then 1 else 0 end)
            """;

    private final JdbcClient jdbc;

    JdbcProductDraftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SavedDraft insert(SavedDraft d) {
        jdbc.sql("""
                insert into product_draft (id, owner_org, created_by, product_id, title, category_path, payload,
                                           created_at, updated_at, version)
                values (:id, :org, :by, :productId, :title, :path, cast(:payload as jsonb), :createdAt, :updatedAt, 0)
                """)
                .param("id", d.id()).param("org", d.ownerOrg()).param("by", d.createdBy())
                .param("productId", d.productId()).param("title", d.title()).param("path", d.categoryPath())
                .param("payload", d.payload()).param("createdAt", Timestamp.from(d.createdAt()))
                .param("updatedAt", Timestamp.from(d.updatedAt()))
                .update();
        return findById(d.id()).orElseThrow();
    }

    @Override
    public SavedDraft update(SavedDraft d) {
        int rows = jdbc.sql("""
                update product_draft set title = :title, category_path = :path, payload = cast(:payload as jsonb),
                       updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .param("id", d.id()).param("title", d.title()).param("path", d.categoryPath())
                .param("payload", d.payload()).param("updatedAt", Timestamp.from(d.updatedAt()))
                .param("version", d.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("catalog.draft-changed",
                    "This draft was saved from another tab or device; reload it to keep editing");
        }
        return findById(d.id()).orElseThrow();
    }

    @Override
    public Optional<SavedDraft> findById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(JdbcProductDraftRepository::map).optional();
    }

    @Override
    public List<SavedDraft> findByOwnerOrg(String orgId, int limit) {
        return jdbc.sql(SELECT + " where owner_org = :org order by updated_at desc limit :limit")
                .param("org", orgId).param("limit", limit).query(JdbcProductDraftRepository::map).list();
    }

    @Override
    public DraftPage search(DraftQuery q) {
        String text = q.text() == null ? "" : q.text().strip().toLowerCase(Locale.ROOT);
        String where = " where owner_org = :org"
                + (q.newOnly() ? " and product_id is null" : "")
                + (text.isEmpty() ? "" : " and (lower(title) like :text escape '!'"
                        + " or lower(coalesce(category_path, '')) like :text escape '!')");
        String direction = q.descending() ? " desc" : " asc";
        String order = switch (q.sort()) {
            case TITLE -> "lower(title)";
            case CATEGORY -> "lower(coalesce(category_path, ''))";
            case UPDATED_AT -> "updated_at";
        } + direction + ", id";
        JdbcClient.StatementSpec count = jdbc.sql("select count(*) from product_draft" + where).param("org", q.orgId());
        JdbcClient.StatementSpec rows = jdbc.sql("select id, product_id, title, category_path, updated_at, version, "
                + TODO + " as todo from product_draft" + where + " order by " + order + " limit :limit offset :offset")
                .param("org", q.orgId()).param("limit", q.size()).param("offset", (long) q.page() * q.size());
        if (!text.isEmpty()) {
            String pattern = "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            count = count.param("text", pattern);
            rows = rows.param("text", pattern);
        }
        long total = count.query(Long.class).single();
        List<DraftListItem> items = rows.query((rs, n) -> new DraftListItem(rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getString("title"), rs.getString("category_path"),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"), rs.getInt("todo"))).list();
        return new DraftPage(items, total, q.page(), q.size());
    }

    @Override
    public void delete(UUID id) {
        jdbc.sql("delete from product_draft where id = :id").param("id", id).update();
    }

    private static SavedDraft map(ResultSet rs, int rowNum) throws SQLException {
        return new SavedDraft(rs.getObject("id", UUID.class), rs.getString("owner_org"), rs.getString("created_by"),
                rs.getObject("product_id", UUID.class), rs.getString("title"), rs.getString("category_path"),
                rs.getString("payload"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }
}
