package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.GiftCard;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.GiftCardRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcGiftCardRepository implements GiftCardRepository {

    private final JdbcClient jdbc;

    JdbcGiftCardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(GiftCard c) {
        jdbc.sql("""
                insert into gift_card (id, code_hash, code_last4, amount_minor, currency, origin, status,
                    purchaser_id, sender_name, recipient_email, recipient_name, message, locale, send_at,
                    delivery_status, delivery_count, sent_at, payment_intent_id, charge_id, issue_reason, issued_by,
                    created_at, updated_at, version)
                values (:id, :hash, :last4, :amount, :currency, :origin, :status, :purchaser, :sender, :email, :name,
                    :message, :locale, :sendAt, :delivery, :count, :sentAt, :pi, :charge, :issueReason, :issuedBy,
                    :createdAt, :updatedAt, 0)
                """)
                .param("id", c.id()).param("hash", c.codeHash()).param("last4", c.codeLast4())
                .param("amount", c.amount().minor()).param("currency", c.amount().currency())
                .param("origin", c.origin().name()).param("status", c.status().name())
                .param("purchaser", c.purchaserId()).param("sender", c.senderName())
                .param("email", c.recipientEmail()).param("name", c.recipientName()).param("message", c.message())
                .param("locale", c.locale()).param("sendAt", Sql.ts(c.sendAt()))
                .param("delivery", c.delivery().name()).param("count", c.deliveryCount())
                .param("sentAt", Sql.ts(c.sentAt())).param("pi", c.paymentIntentId()).param("charge", c.chargeId())
                .param("issueReason", c.issueReason()).param("issuedBy", c.issuedBy())
                .param("createdAt", Sql.ts(c.createdAt())).param("updatedAt", Sql.ts(c.updatedAt())).update();
    }

    @Override
    public Optional<GiftCard> find(UUID id) {
        return jdbc.sql("select * from gift_card where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<GiftCard> lock(UUID id) {
        return jdbc.sql("select * from gift_card where id = :id for update").param("id", id).query(this::map)
                .optional();
    }

    @Override
    public Optional<GiftCard> lockByHash(String codeHash) {
        return jdbc.sql("select * from gift_card where code_hash = :h for update").param("h", codeHash)
                .query(this::map).optional();
    }

    @Override
    public Optional<GiftCard> lockByIntent(String paymentIntentId) {
        return jdbc.sql("select * from gift_card where payment_intent_id = :pi for update")
                .param("pi", paymentIntentId).query(this::map).optional();
    }

    @Override
    public GiftCard update(GiftCard c) {
        int updated = jdbc.sql("""
                update gift_card set code_hash = :hash, code_last4 = :last4, status = :status,
                    delivery_status = :delivery, delivery_count = :count, sent_at = :sentAt, charge_id = :charge,
                    redeemed_by = :redeemedBy, redeemed_at = :redeemedAt, void_reason = :voidReason,
                    voided_by = :voidedBy, voided_at = :voidedAt, refunded_to_card = :refunded,
                    send_at = :sendAt, updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .param("hash", c.codeHash()).param("last4", c.codeLast4()).param("status", c.status().name())
                .param("delivery", c.delivery().name()).param("count", c.deliveryCount())
                .param("sentAt", Sql.ts(c.sentAt())).param("charge", c.chargeId())
                .param("redeemedBy", c.redeemedBy()).param("redeemedAt", Sql.ts(c.redeemedAt()))
                .param("voidReason", c.voidReason()).param("voidedBy", c.voidedBy())
                .param("voidedAt", Sql.ts(c.voidedAt())).param("refunded", c.refundedToCard())
                .param("sendAt", Sql.ts(c.sendAt())).param("updatedAt", Sql.ts(c.updatedAt()))
                .param("id", c.id()).param("version", c.version()).update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Gift card " + c.id() + " was changed concurrently");
        }
        return find(c.id()).orElseThrow();
    }

    @Override
    public List<GiftCard> ofPurchaser(String purchaserId, int limit) {
        return jdbc.sql("select * from gift_card where purchaser_id = :p order by created_at desc, id limit :n")
                .param("p", purchaserId).param("n", limit).query(this::map).list();
    }

    @Override
    public List<GiftCard> search(String query, GiftCard.Status status, int limit) {
        String q = query == null || query.isBlank() ? null : query.trim();
        return jdbc.sql("""
                select * from gift_card
                where (cast(:status as varchar) is null or status = :status)
                  and (cast(:q as varchar) is null or id::text = :q or code_last4 = upper(:q)
                       or lower(recipient_email) like lower('%' || :q || '%')
                       or lower(coalesce(sender_name, '')) like lower('%' || :q || '%')
                       or lower(coalesce(recipient_name, '')) like lower('%' || :q || '%')
                       or purchaser_id = :q)
                order by created_at desc, id limit :n
                """)
                .param("status", status == null ? null : status.name()).param("q", q).param("n", limit)
                .query(this::map).list();
    }

    @Override
    public List<GiftCard> lockDue(Instant now, int limit) {
        return jdbc.sql("""
                select * from gift_card where status = 'ACTIVE' and delivery_status = 'SCHEDULED' and send_at <= :now
                order by send_at limit :n for update skip locked
                """)
                .param("now", Sql.ts(now)).param("n", limit).query(this::map).list();
    }

    @Override
    public Map<GiftCard.Status, long[]> totalsByStatus() {
        Map<GiftCard.Status, long[]> out = new EnumMap<>(GiftCard.Status.class);
        for (GiftCard.Status s : GiftCard.Status.values()) {
            out.put(s, new long[] {0, 0});
        }
        jdbc.sql("select status, count(*) as n, coalesce(sum(amount_minor), 0) as s from gift_card group by status")
                .query((rs, n) -> {
                    out.put(GiftCard.Status.valueOf(rs.getString("status")),
                            new long[] {rs.getLong("n"), rs.getLong("s")});
                    return 0;
                }).list();
        return out;
    }

    private GiftCard map(ResultSet rs, int n) throws SQLException {
        String currency = rs.getString("currency");
        return new GiftCard(rs.getObject("id", UUID.class), rs.getString("code_hash"), rs.getString("code_last4"),
                Money.of(rs.getLong("amount_minor"), currency), GiftCard.Origin.valueOf(rs.getString("origin")),
                GiftCard.Status.valueOf(rs.getString("status")), rs.getString("purchaser_id"),
                rs.getString("sender_name"), rs.getString("recipient_email"), rs.getString("recipient_name"),
                rs.getString("message"), rs.getString("locale"), Sql.instant(rs, "send_at"),
                GiftCard.Delivery.valueOf(rs.getString("delivery_status")), rs.getInt("delivery_count"),
                Sql.instant(rs, "sent_at"), rs.getString("payment_intent_id"), rs.getString("charge_id"),
                rs.getString("issue_reason"), rs.getString("issued_by"), rs.getString("redeemed_by"),
                Sql.instant(rs, "redeemed_at"), rs.getString("void_reason"), rs.getString("voided_by"),
                Sql.instant(rs, "voided_at"), rs.getBoolean("refunded_to_card"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
