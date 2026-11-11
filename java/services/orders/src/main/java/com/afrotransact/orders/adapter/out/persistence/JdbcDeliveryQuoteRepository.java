package com.afrotransact.orders.adapter.out.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.domain.DeliveryQuote;
import com.afrotransact.orders.domain.DeliveryQuote.Answer;
import com.afrotransact.orders.domain.DeliveryQuote.Decline;
import com.afrotransact.orders.domain.DeliveryQuote.Escalation;
import com.afrotransact.orders.domain.DeliveryQuote.Holder;
import com.afrotransact.orders.domain.QuoteAction;
import com.afrotransact.orders.domain.QuoteContact;
import com.afrotransact.orders.domain.QuoteSnapshot;
import com.afrotransact.orders.domain.QuoteSnapshot.Line;
import com.afrotransact.orders.domain.QuoteSnapshot.Pack;
import com.afrotransact.orders.domain.QuoteSnapshot.Place;
import com.afrotransact.orders.domain.QuoteStatus;
import com.afrotransact.orders.port.DeliveryQuoteRepository;
import com.afrotransact.orders.port.Page;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

/** delivery_quote and delivery_quote_event. Consumption is one conditional UPDATE, so two orders can never share a quote. */
@Repository
class JdbcDeliveryQuoteRepository implements DeliveryQuoteRepository {

    private static final String COLUMNS = """
            id, reference, customer_id, status, cart_hash, ship_to_key, currency, packages, lines, ship_to,
            contact_preference, note, amount_minor, agreed_reason, agreed_by_id, agreed_by_name, agreed_at, expires_at,
            declined_reason, declined_by_id, declined_by_name, declined_at, used_order_id, used_at, assignee_id,
            assignee_name, assigned_by_id, assigned_by_name, assigned_at, stage_entered_at, escalation_level,
            escalated_at, escalation_reason, escalation_target_ids, escalation_target_names, created_at, updated_at,
            version""";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcDeliveryQuoteRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The address columns of the snapshot: the place staff see and the name on the address. */
    private record ShipToDoc(Place place, String customerName) {
    }

    @Override
    public boolean insert(DeliveryQuote q) {
        QuoteSnapshot s = q.snapshot();
        return jdbc.sql("""
                insert into delivery_quote (id, reference, customer_id, status, cart_hash, ship_to_key, dedupe_key,
                    currency, packages, lines, ship_to, contact_preference, note, stage_entered_at, created_at,
                    updated_at, version)
                values (:id, :ref, :customer, :status, :cart, :shipTo, :dedupe, :currency, cast(:packages as jsonb),
                    cast(:lines as jsonb), cast(:place as jsonb), :contact, :note, :entered, :created, :updated, 0)
                on conflict do nothing
                """)
                .param("id", UUID.fromString(q.id())).param("ref", q.reference()).param("customer", q.customerId())
                .param("status", q.status().name()).param("cart", s.cartHash()).param("shipTo", s.shipToKey())
                .param("dedupe", QuoteSnapshot.dedupeKey(s.cartHash(), s.shipToKey())).param("currency", s.currency())
                .param("packages", json.write(s.packages())).param("lines", json.write(s.lines()))
                .param("place", json.write(new ShipToDoc(s.place(), s.customerName())))
                .param("contact", s.contact().name()).param("note", s.note())
                .param("entered", Sql.ts(q.stageEnteredAt())).param("created", Sql.ts(q.createdAt()))
                .param("updated", Sql.ts(q.updatedAt())).update() == 1;
    }

    @Override
    public DeliveryQuote save(DeliveryQuote q) {
        Answer a = q.answer();
        Decline d = q.decline();
        Holder h = q.holder();
        Escalation e = q.escalation();
        int rows = jdbc.sql("""
                update delivery_quote set status = :status, amount_minor = :amount, agreed_reason = :aReason,
                    agreed_by_id = :aById, agreed_by_name = :aByName, agreed_at = :aAt, expires_at = :expires,
                    declined_reason = :dReason, declined_by_id = :dById, declined_by_name = :dByName,
                    declined_at = :dAt, used_order_id = :order, used_at = :usedAt, assignee_id = :assignee,
                    assignee_name = :assigneeName, assigned_by_id = :byId, assigned_by_name = :byName,
                    assigned_at = :assignedAt, stage_entered_at = :entered, escalation_level = :level,
                    escalated_at = :escAt, escalation_reason = :escReason,
                    escalation_target_ids = cast(:targetIds as text[]),
                    escalation_target_names = cast(:targetNames as text[]), updated_at = :updated,
                    version = version + 1
                where id = :id and version = :version
                """)
                .param("status", q.status().name()).param("amount", a == null ? null : a.amountMinor())
                .param("aReason", a == null ? null : a.reason()).param("aById", a == null ? null : a.byId())
                .param("aByName", a == null ? null : a.byName()).param("aAt", Sql.ts(a == null ? null : a.at()))
                .param("expires", Sql.ts(a == null ? null : a.expiresAt()))
                .param("dReason", d == null ? null : d.reason()).param("dById", d == null ? null : d.byId())
                .param("dByName", d == null ? null : d.byName()).param("dAt", Sql.ts(d == null ? null : d.at()))
                .param("order", Sql.uuid(q.usedOrderId())).param("usedAt", Sql.ts(q.usedAt()))
                .param("assignee", h.assigneeId()).param("assigneeName", h.assigneeName())
                .param("byId", h.assignedById()).param("byName", h.assignedByName())
                .param("assignedAt", Sql.ts(h.assignedAt())).param("entered", Sql.ts(q.stageEnteredAt()))
                .param("level", e.level()).param("escAt", Sql.ts(e.at())).param("escReason", e.reason())
                .param("targetIds", e.targetIds().toArray(String[]::new))
                .param("targetNames", e.targetNames().toArray(String[]::new))
                .param("updated", Sql.ts(q.updatedAt())).param("id", UUID.fromString(q.id()))
                .param("version", q.version()).update();
        if (rows != 1) {
            throw new OptimisticLockingFailureException("Delivery quote " + q.reference() + " was changed by someone "
                    + "else; reload it");
        }
        return new DeliveryQuote(q.id(), q.reference(), q.customerId(), q.status(), q.snapshot(), q.answer(),
                q.decline(), q.usedOrderId(), q.usedAt(), q.holder(), q.stageEnteredAt(), q.escalation(),
                q.createdAt(), q.updatedAt(), q.version() + 1);
    }

    @Override
    public Optional<DeliveryQuote> findById(String id) {
        return one("select " + COLUMNS + " from delivery_quote where id = :id", Map.of("id", UUID.fromString(id)));
    }

    @Override
    public Optional<DeliveryQuote> lockById(String id) {
        return one("select " + COLUMNS + " from delivery_quote where id = :id for update",
                Map.of("id", UUID.fromString(id)));
    }

    @Override
    public Optional<DeliveryQuote> findLive(String customerId, String dedupeKey) {
        return one("select " + COLUMNS + " from delivery_quote where customer_id = :c and dedupe_key = :k "
                + "and status in ('REQUESTED', 'IN_PROGRESS', 'READY')", Map.of("c", customerId, "k", dedupeKey));
    }

    @Override
    public Optional<DeliveryQuote> findRecent(String customerId, String dedupeKey, Instant since) {
        return one("select " + COLUMNS + " from delivery_quote where customer_id = :c and dedupe_key = :k "
                + "and created_at >= :since order by created_at desc limit 1",
                Map.of("c", customerId, "k", dedupeKey, "since", Sql.ts(since)));
    }

    @Override
    public List<DeliveryQuote> forCustomer(String customerId, int limit) {
        return jdbc.sql("select " + COLUMNS + " from delivery_quote where customer_id = :c "
                + "order by created_at desc limit :limit").param("c", customerId).param("limit", limit)
                .query(this::map).list();
    }

    @Override
    public int countSince(String customerId, Instant since) {
        Integer n = jdbc.sql("select count(*) from delivery_quote where customer_id = :c and created_at >= :since")
                .param("c", customerId).param("since", Sql.ts(since)).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    @Override
    public Page<DeliveryQuote> search(Search s) {
        List<String> where = new ArrayList<>();
        Map<String, Object> p = new HashMap<>();
        switch (s.queue()) {
            case MY -> {
                where.add("status = 'IN_PROGRESS' and assignee_id = :viewer");
                p.put("viewer", s.viewerId());
            }
            case UNASSIGNED -> where.add("status = 'REQUESTED'");
            case WAITING -> where.add("status = 'READY'");
            case CLOSED -> where.add("status in ('DECLINED', 'EXPIRED', 'USED')");
            case ALL -> where.add("true");
        }
        if (s.assigneeId() != null) {
            where.add("assignee_id = :assignee");
            p.put("assignee", s.assigneeId());
        }
        if (s.unassignedOnly()) {
            where.add("status = 'REQUESTED'");
        }
        if (s.escalatedOnly()) {
            where.add("escalation_level > 0 and status in ('REQUESTED', 'IN_PROGRESS')");
        }
        if (s.text() != null && !s.text().isBlank()) {
            where.add("(lower(reference) like :text or lower(assignee_name) like :text or lower(ship_to::text) "
                    + "like :text)");
            p.put("text", "%" + s.text().trim().toLowerCase().replace("%", "") + "%");
        }
        String clause = String.join(" and ", where);
        var count = jdbc.sql("select count(*) from delivery_quote where " + clause);
        var rows = jdbc.sql("select " + COLUMNS + " from delivery_quote where " + clause
                + " order by stage_entered_at asc, id limit :limit offset :offset");
        for (Map.Entry<String, Object> e : p.entrySet()) {
            count = count.param(e.getKey(), e.getValue());
            rows = rows.param(e.getKey(), e.getValue());
        }
        Long total = count.query(Long.class).single();
        List<DeliveryQuote> items = rows.param("limit", s.limit()).param("offset", s.offset()).query(this::map).list();
        return new Page<>(items, s.offset(), s.limit(), total == null ? 0 : total);
    }

    @Override
    public List<String> escalationCandidates(int limit) {
        return jdbc.sql("select id from delivery_quote where status in ('REQUESTED', 'IN_PROGRESS') "
                + "and escalation_level < 2 order by stage_entered_at limit :limit").param("limit", limit)
                .query((rs, n) -> rs.getString("id")).list();
    }

    @Override
    public List<String> expiredBefore(Instant now, int limit) {
        return jdbc.sql("select id from delivery_quote where status = 'READY' and expires_at <= :now "
                + "order by expires_at limit :limit").param("now", Sql.ts(now)).param("limit", limit)
                .query((rs, n) -> rs.getString("id")).list();
    }

    @Override
    public Map<String, Integer> openLoad() {
        Map<String, Integer> out = new HashMap<>();
        jdbc.sql("select assignee_id, count(*) n from delivery_quote where status = 'IN_PROGRESS' "
                + "group by assignee_id").query((rs, i) -> out.put(rs.getString("assignee_id"), rs.getInt("n")))
                .list();
        return out;
    }

    @Override
    public Optional<DeliveryQuote> consume(String quoteId, String customerId, String cartHash, String shipToKey,
                                           String orderId, Instant now) {
        Map<String, Object> p = Map.of("id", UUID.fromString(quoteId), "c", customerId, "cart", cartHash,
                "ship", shipToKey, "order", UUID.fromString(orderId), "now", Sql.ts(now));
        var update = jdbc.sql("""
                update delivery_quote set status = 'USED', used_order_id = :order, used_at = :now, updated_at = :now,
                    version = version + 1
                where id = :id and customer_id = :c and cart_hash = :cart and ship_to_key = :ship
                  and status = 'READY' and expires_at > :now
                """);
        for (Map.Entry<String, Object> e : p.entrySet()) {
            update = update.param(e.getKey(), e.getValue());
        }
        if (update.update() == 1) {
            return findById(quoteId);
        }
        // A retried step of the same order finds its own consumption.
        return findById(quoteId).filter(q -> q.status() == QuoteStatus.USED && orderId.equals(q.usedOrderId())
                && q.customerId().equals(customerId) && q.snapshot().cartHash().equals(cartHash)
                && q.snapshot().shipToKey().equals(shipToKey));
    }

    @Override
    public Optional<DeliveryQuote> findByOrder(String orderId) {
        return one("select " + COLUMNS + " from delivery_quote where used_order_id = :o",
                Map.of("o", UUID.fromString(orderId)));
    }

    @Override
    public void appendEvent(QuoteEvent e) {
        jdbc.sql("""
                insert into delivery_quote_event (id, quote_id, action, from_status, to_status, actor_id, actor_name,
                    note, amount_minor, occurred_at)
                values (:id, :quote, :action, :from, :to, :actor, :name, :note, :amount, :at)
                """)
                .param("id", UUID.fromString(e.id())).param("quote", UUID.fromString(e.quoteId()))
                .param("action", e.action().name()).param("from", e.from() == null ? null : e.from().name())
                .param("to", e.to().name()).param("actor", e.actorId()).param("name", e.actorName())
                .param("note", e.note()).param("amount", e.amountMinor()).param("at", Sql.ts(e.at())).update();
    }

    @Override
    public List<QuoteEvent> events(String quoteId) {
        return jdbc.sql("select id, quote_id, action, from_status, to_status, actor_id, actor_name, note, "
                + "amount_minor, occurred_at from delivery_quote_event where quote_id = :q "
                + "order by occurred_at, id").param("q", UUID.fromString(quoteId))
                .query((rs, n) -> new QuoteEvent(Sql.str(rs, "id"), Sql.str(rs, "quote_id"),
                        QuoteAction.valueOf(rs.getString("action")), status(rs.getString("from_status")),
                        QuoteStatus.valueOf(rs.getString("to_status")), rs.getString("actor_id"),
                        rs.getString("actor_name"), rs.getString("note"), (Long) rs.getObject("amount_minor"),
                        Sql.instant(rs, "occurred_at"))).list();
    }

    private Optional<DeliveryQuote> one(String sql, Map<String, Object> params) {
        var spec = jdbc.sql(sql);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query(this::map).optional();
    }

    private DeliveryQuote map(ResultSet rs, int n) throws SQLException {
        ShipToDoc to = json.read(rs.getString("ship_to"), ShipToDoc.class);
        List<Pack> packs = json.read(rs.getString("packages"), new TypeReference<List<Pack>>() { });
        List<Line> lines = json.read(rs.getString("lines"), new TypeReference<List<Line>>() { });
        QuoteSnapshot snapshot = new QuoteSnapshot(rs.getString("cart_hash"), rs.getString("ship_to_key"),
                rs.getString("currency"), packs, lines, to.place(), to.customerName(),
                QuoteContact.valueOf(rs.getString("contact_preference")), rs.getString("note"));
        Answer answer = rs.getObject("amount_minor") == null || rs.getString("agreed_by_id") == null ? null
                : new Answer(rs.getLong("amount_minor"), rs.getString("agreed_reason"), rs.getString("agreed_by_id"),
                rs.getString("agreed_by_name"), Sql.instant(rs, "agreed_at"), Sql.instant(rs, "expires_at"));
        Decline decline = rs.getString("declined_reason") == null ? null
                : new Decline(rs.getString("declined_reason"), rs.getString("declined_by_id"),
                rs.getString("declined_by_name"), Sql.instant(rs, "declined_at"));
        Holder holder = new Holder(rs.getString("assignee_id"), rs.getString("assignee_name"),
                rs.getString("assigned_by_id"), rs.getString("assigned_by_name"), Sql.instant(rs, "assigned_at"));
        Escalation escalation = new Escalation(rs.getInt("escalation_level"), Sql.instant(rs, "escalated_at"),
                rs.getString("escalation_reason"), strings(rs, "escalation_target_ids"),
                strings(rs, "escalation_target_names"));
        return new DeliveryQuote(Sql.str(rs, "id"), rs.getString("reference"), rs.getString("customer_id"),
                QuoteStatus.valueOf(rs.getString("status")), snapshot, answer, decline, Sql.str(rs, "used_order_id"),
                Sql.instant(rs, "used_at"), holder, Sql.instant(rs, "stage_entered_at"), escalation,
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array a = rs.getArray(column);
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    private static QuoteStatus status(String s) {
        return s == null ? null : QuoteStatus.valueOf(s);
    }
}
