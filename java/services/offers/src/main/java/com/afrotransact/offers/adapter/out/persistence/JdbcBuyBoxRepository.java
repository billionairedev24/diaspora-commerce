package com.afrotransact.offers.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.port.BuyBoxRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcBuyBoxRepository implements BuyBoxRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcBuyBoxRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void lock(String atin) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('buy_box:' || :atin))").param("atin", atin)
                .query((rs, n) -> 1).list();
    }

    @Override
    public Optional<CurrentBuyBox> find(String atin, String currency) {
        return jdbc.sql("""
                select atin, currency, winner_offer_id, winner_seller_org, winner_price_minor, winner_landed_minor,
                       offer_count, min_landed_minor, score, reasons::text as reasons, computed_at
                from buy_box where atin = :atin and currency = :currency
                """)
                .param("atin", atin).param("currency", currency)
                .query(this::mapCurrent).optional();
    }

    @Override
    public List<String> currencies(String atin) {
        return jdbc.sql("select currency from buy_box where atin = :atin order by currency").param("atin", atin)
                .query(String.class).list();
    }

    @Override
    public void save(CurrentBuyBox c) {
        jdbc.sql("""
                insert into buy_box (atin, currency, winner_offer_id, winner_seller_org, winner_price_minor,
                                     winner_landed_minor, offer_count, min_landed_minor, score, reasons, computed_at)
                values (:atin, :currency, :winner, :org, :price, :landed, :count, :minLanded, :score,
                        cast(:reasons as jsonb), :at)
                on conflict (atin, currency) do update
                   set winner_offer_id = excluded.winner_offer_id, winner_seller_org = excluded.winner_seller_org,
                       winner_price_minor = excluded.winner_price_minor,
                       winner_landed_minor = excluded.winner_landed_minor, offer_count = excluded.offer_count,
                       min_landed_minor = excluded.min_landed_minor, score = excluded.score,
                       reasons = excluded.reasons, computed_at = excluded.computed_at
                """)
                .param("atin", c.atin())
                .param("currency", c.currency())
                .param("winner", c.winnerOfferId())
                .param("org", c.winnerSellerOrgId())
                .param("price", c.winnerPriceMinor())
                .param("landed", c.winnerLandedPriceMinor())
                .param("count", c.offerCount())
                .param("minLanded", c.minLandedPriceMinor())
                .param("score", c.score())
                .param("reasons", json.write(c.reasons()))
                .param("at", Timestamp.from(c.computedAt()))
                .update();
    }

    @Override
    public void audit(AuditEntry e) {
        jdbc.sql("""
                insert into buy_box_decision (id, atin, currency, previous_winner_id, winner_offer_id, trigger_event,
                                              offers, reasons, decided_at)
                values (:id, :atin, :currency, :previous, :winner, :trigger, cast(:offers as jsonb),
                        cast(:reasons as jsonb), :at)
                """)
                .param("id", e.id())
                .param("atin", e.atin())
                .param("currency", e.currency())
                .param("previous", e.previousWinnerOfferId())
                .param("winner", e.winnerOfferId())
                .param("trigger", e.trigger())
                .param("offers", json.write(e.offers()))
                .param("reasons", json.write(e.reasons()))
                .param("at", Timestamp.from(e.decidedAt()))
                .update();
    }

    @Override
    public List<AuditEntry> history(String atin, int limit) {
        return jdbc.sql("""
                select id, atin, currency, previous_winner_id, winner_offer_id, trigger_event, offers::text as offers,
                       reasons::text as reasons, decided_at
                from buy_box_decision where atin = :atin order by decided_at desc, id limit :limit
                """)
                .param("atin", atin).param("limit", limit)
                .query((rs, n) -> new AuditEntry(rs.getObject("id", UUID.class), rs.getString("atin"),
                        rs.getString("currency"), rs.getObject("previous_winner_id", UUID.class),
                        rs.getObject("winner_offer_id", UUID.class), rs.getString("trigger_event"),
                        json.audited(rs.getString("offers")), json.reasons(rs.getString("reasons")),
                        rs.getTimestamp("decided_at").toInstant()))
                .list();
    }

    private CurrentBuyBox mapCurrent(ResultSet rs, int rowNum) throws SQLException {
        return new CurrentBuyBox(rs.getString("atin"), rs.getString("currency"),
                rs.getObject("winner_offer_id", UUID.class), rs.getString("winner_seller_org"),
                nullableLong(rs, "winner_price_minor"), nullableLong(rs, "winner_landed_minor"),
                rs.getInt("offer_count"), nullableLong(rs, "min_landed_minor"), rs.getDouble("score"),
                json.reasons(rs.getString("reasons")), rs.getTimestamp("computed_at").toInstant());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
