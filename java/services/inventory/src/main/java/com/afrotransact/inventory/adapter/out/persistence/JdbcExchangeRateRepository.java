package com.afrotransact.inventory.adapter.out.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.afrotransact.inventory.domain.ExchangeRate;
import com.afrotransact.inventory.domain.ExchangeRateChange;
import com.afrotransact.inventory.domain.RateAction;
import com.afrotransact.inventory.port.ExchangeRateRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcExchangeRateRepository implements ExchangeRateRepository {

    private static final String SELECT = """
            select currency, rate_date, rate_to_usd, note, updated_by, created_at, updated_at, version
            from exchange_rate
            """;

    private final JdbcClient jdbc;

    JdbcExchangeRateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ExchangeRate> lockByDay(String currency, LocalDate rateDate) {
        return jdbc.sql(SELECT + " where currency = :currency and rate_date = :day for update")
                .param("currency", currency).param("day", Date.valueOf(rateDate))
                .query(JdbcExchangeRateRepository::rate).optional();
    }

    @Override
    public Optional<ExchangeRate> latestOnOrBefore(String currency, LocalDate day) {
        return jdbc.sql(SELECT + " where currency = :currency and rate_date <= :day order by rate_date desc limit 1")
                .param("currency", currency).param("day", Date.valueOf(day))
                .query(JdbcExchangeRateRepository::rate).optional();
    }

    @Override
    public List<ExchangeRate> latestPerCurrency(LocalDate day) {
        return jdbc.sql("""
                select distinct on (currency) currency, rate_date, rate_to_usd, note, updated_by, created_at,
                       updated_at, version
                from exchange_rate where rate_date <= :day order by currency, rate_date desc
                """)
                .param("day", Date.valueOf(day)).query(JdbcExchangeRateRepository::rate).list();
    }

    @Override
    public Page<ExchangeRate> search(String currency, int page, int size) {
        long total = jdbc.sql("select count(*) from exchange_rate where (cast(:currency as varchar) is null "
                        + "or currency = :currency)")
                .param("currency", currency).query(Long.class).single();
        List<ExchangeRate> items = jdbc.sql(SELECT + """
                where (cast(:currency as varchar) is null or currency = :currency)
                order by rate_date desc, currency limit :limit offset :offset
                """)
                .param("currency", currency).param("limit", size).param("offset", (long) page * size)
                .query(JdbcExchangeRateRepository::rate).list();
        return new Page<>(items, total, page, size);
    }

    @Override
    public void insert(ExchangeRate r) {
        jdbc.sql("""
                insert into exchange_rate (currency, rate_date, rate_to_usd, note, updated_by, created_at, updated_at,
                                           version)
                values (:currency, :day, :rate, :note, :by, :createdAt, :updatedAt, 0)
                """)
                .param("currency", r.currency()).param("day", Date.valueOf(r.rateDate()))
                .param("rate", r.rateToUsd()).param("note", r.note()).param("by", r.updatedBy())
                .param("createdAt", Timestamp.from(r.createdAt())).param("updatedAt", Timestamp.from(r.updatedAt()))
                .update();
    }

    @Override
    public void update(ExchangeRate r) {
        int rows = jdbc.sql("""
                update exchange_rate set rate_to_usd = :rate, note = :note, updated_by = :by, updated_at = :updatedAt,
                       version = version + 1
                where currency = :currency and rate_date = :day and version = :version
                """)
                .param("currency", r.currency()).param("day", Date.valueOf(r.rateDate()))
                .param("rate", r.rateToUsd()).param("note", r.note()).param("by", r.updatedBy())
                .param("updatedAt", Timestamp.from(r.updatedAt())).param("version", r.version()).update();
        if (rows == 0) {
            throw new ConflictException("inventory.concurrent-update", "The rate of " + r.currency() + " on "
                    + r.rateDate() + " changed concurrently");
        }
    }

    @Override
    public void audit(ExchangeRateChange c) {
        jdbc.sql("""
                insert into exchange_rate_audit (occurred_at, currency, rate_date, action, old_rate, new_rate, reason,
                                                 actor)
                values (:at, :currency, :day, :action, :oldRate, :newRate, :reason, :actor)
                """)
                .param("at", Timestamp.from(c.occurredAt())).param("currency", c.currency())
                .param("day", Date.valueOf(c.rateDate())).param("action", c.action().name())
                .param("oldRate", c.oldRate()).param("newRate", c.newRate()).param("reason", c.reason())
                .param("actor", c.actor()).update();
    }

    @Override
    public Page<ExchangeRateChange> history(String currency, int page, int size) {
        long total = jdbc.sql("select count(*) from exchange_rate_audit where (cast(:currency as varchar) is null "
                        + "or currency = :currency)")
                .param("currency", currency).query(Long.class).single();
        List<ExchangeRateChange> items = jdbc.sql("""
                select id, occurred_at, currency, rate_date, action, old_rate, new_rate, reason, actor
                from exchange_rate_audit
                where (cast(:currency as varchar) is null or currency = :currency)
                order by id desc limit :limit offset :offset
                """)
                .param("currency", currency).param("limit", size).param("offset", (long) page * size)
                .query(JdbcExchangeRateRepository::change).list();
        return new Page<>(items, total, page, size);
    }

    private static ExchangeRate rate(ResultSet rs, int row) throws SQLException {
        return new ExchangeRate(rs.getString("currency"), rs.getDate("rate_date").toLocalDate(),
                ExchangeRate.tidy(rs.getBigDecimal("rate_to_usd")), rs.getString("note"), rs.getString("updated_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }

    private static ExchangeRateChange change(ResultSet rs, int row) throws SQLException {
        BigDecimal old = rs.getBigDecimal("old_rate");
        return new ExchangeRateChange(rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("currency"), rs.getDate("rate_date").toLocalDate(),
                RateAction.valueOf(rs.getString("action")), old == null ? null : ExchangeRate.tidy(old),
                ExchangeRate.tidy(rs.getBigDecimal("new_rate")), rs.getString("reason"), rs.getString("actor"));
    }
}
