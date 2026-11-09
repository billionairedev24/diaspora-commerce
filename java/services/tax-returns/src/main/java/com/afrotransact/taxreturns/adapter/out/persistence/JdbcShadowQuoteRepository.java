package com.afrotransact.taxreturns.adapter.out.persistence;

import java.time.Instant;
import java.util.List;

import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.shadow.ShadowKind;
import com.afrotransact.taxreturns.domain.shadow.ShadowRow;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.ShadowQuoteRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcShadowQuoteRepository implements ShadowQuoteRepository {

    private static final String WINDOW = " where quoted_at >= :from and quoted_at < :to"
            + " and (cast(:provider as varchar) is null or provider = cast(:provider as varchar))";

    private final JdbcClient jdbc;

    JdbcShadowQuoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveAll(List<ShadowRow> rows) {
        for (ShadowRow r : rows) {
            jdbc.sql("""
                    insert into tax_shadow_quote (quoted_at, order_ref, line_id, atin, provider, state, category_code,
                        internal_tax_code, provider_code, kind, in_house_outcome, in_house_rate, in_house_tax_minor,
                        provider_outcome, provider_rate, provider_tax_minor, provider_error, difference_minor, currency)
                    values (:at, :order, :line, :atin, :provider, :state, :category, :code, :providerCode, :kind,
                        :ho, :hr, :ht, :po, :pr, :pt, :error, :diff, :currency)
                    """)
                    .param("at", Sql.ts(r.quotedAt())).param("order", r.orderRef()).param("line", r.lineId())
                    .param("atin", r.atin()).param("provider", r.provider().name()).param("state", r.state())
                    .param("category", r.categoryCode()).param("code", r.internalTaxCode())
                    .param("providerCode", r.providerCode()).param("kind", r.kind().name())
                    .param("ho", name(r.inHouseOutcome())).param("hr", r.inHouseRate()).param("ht", r.inHouseTaxMinor())
                    .param("po", name(r.providerOutcome())).param("pr", r.providerRate())
                    .param("pt", r.providerTaxMinor())
                    .param("error", r.providerError() == null ? null : trim(r.providerError()))
                    .param("diff", r.differenceMinor()).param("currency", r.currency()).update();
        }
    }

    @Override
    public Totals totals(Instant from, Instant to, TaxProviderId provider) {
        return jdbc.sql("""
                select count(*) as compared, count(*) filter (where kind <> 'MATCH') as differing,
                       coalesce(sum(abs(difference_minor)), 0) as absolute, coalesce(sum(difference_minor), 0) as net
                from tax_shadow_quote""" + WINDOW)
                .param("from", Sql.ts(from)).param("to", Sql.ts(to)).param("provider", name(provider))
                .query((rs, n) -> new Totals(rs.getLong("compared"), rs.getLong("differing"), rs.getLong("absolute"),
                        rs.getLong("net"))).single();
    }

    @Override
    public List<Group> groups(Dimension dimension, Instant from, Instant to, TaxProviderId provider) {
        String key = switch (dimension) {
            case INTERNAL_TAX_CODE -> "coalesce(internal_tax_code, 'NO_DECISION')";
            case CATEGORY -> "coalesce(category_code, 'UNKNOWN')";
            case OUTCOME -> "coalesce(in_house_outcome, 'NONE')";
            case STATE -> "coalesce(state, 'UNKNOWN')";
            case KIND -> "kind";
        };
        String providerCode = dimension == Dimension.INTERNAL_TAX_CODE ? "max(provider_code)" : "cast(null as varchar)";
        return jdbc.sql("select " + key + " as k, " + providerCode + " as pc, count(*) as lines, "
                + "count(*) filter (where kind <> 'MATCH') as differing, "
                + "coalesce(sum(abs(difference_minor)), 0) as absolute from tax_shadow_quote" + WINDOW
                + " group by k order by differing desc, lines desc, k")
                .param("from", Sql.ts(from)).param("to", Sql.ts(to)).param("provider", name(provider))
                .query((rs, n) -> new Group(rs.getString("k"), rs.getString("pc"), rs.getLong("lines"),
                        rs.getLong("differing"), rs.getLong("absolute"))).list();
    }

    @Override
    public List<ShadowRow> rows(Instant from, Instant to, TaxProviderId provider, boolean onlyDifferences, int page,
                                int size) {
        return jdbc.sql("select * from tax_shadow_quote" + WINDOW + (onlyDifferences ? " and kind <> 'MATCH'" : "")
                + " order by quoted_at desc, id desc limit :limit offset :offset")
                .param("from", Sql.ts(from)).param("to", Sql.ts(to)).param("provider", name(provider))
                .param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new ShadowRow(Sql.instant(rs, "quoted_at"), rs.getString("order_ref"),
                        rs.getString("line_id"), rs.getString("atin"),
                        TaxProviderId.valueOf(rs.getString("provider")), rs.getString("state"),
                        rs.getString("category_code"), rs.getString("internal_tax_code"),
                        rs.getString("provider_code"), ShadowKind.valueOf(rs.getString("kind")),
                        outcome(rs.getString("in_house_outcome")), rs.getBigDecimal("in_house_rate"),
                        (Long) rs.getObject("in_house_tax_minor"), outcome(rs.getString("provider_outcome")),
                        rs.getBigDecimal("provider_rate"), (Long) rs.getObject("provider_tax_minor"),
                        rs.getString("provider_error"), rs.getLong("difference_minor"), rs.getString("currency")))
                .list();
    }

    private static Outcome outcome(String v) {
        return v == null ? null : Outcome.valueOf(v);
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    private static String trim(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300);
    }
}
