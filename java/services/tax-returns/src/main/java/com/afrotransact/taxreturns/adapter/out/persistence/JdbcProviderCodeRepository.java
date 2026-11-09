package com.afrotransact.taxreturns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.profile.ProviderCode;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.ProviderCodeRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProviderCodeRepository implements ProviderCodeRepository {

    private static final String SELECT = """
            select m.*, o.category_code, o.outcome
            from provider_code_map m join tax_outcome_code o on o.internal_tax_code = m.internal_tax_code
            """;

    private final JdbcClient jdbc;

    JdbcProviderCodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ProviderCode> list(TaxProviderId provider, String categoryCode, boolean unmappedOnly) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where 1 = 1");
        if (provider != null) {
            sql.append(" and m.provider = :provider");
        }
        if (categoryCode != null && !categoryCode.isBlank()) {
            sql.append(" and o.category_code = :category");
        }
        if (unmappedOnly) {
            sql.append(" and m.provider_code is null and o.outcome <> 'BLOCKED'");
        }
        sql.append(" order by o.category_code, m.internal_tax_code, m.provider");
        JdbcClient.StatementSpec q = jdbc.sql(sql.toString());
        if (provider != null) {
            q = q.param("provider", provider.name());
        }
        if (categoryCode != null && !categoryCode.isBlank()) {
            q = q.param("category", categoryCode);
        }
        return q.query((rs, n) -> map(rs)).list();
    }

    @Override
    public Optional<ProviderCode> find(String internalTaxCode, TaxProviderId provider) {
        return jdbc.sql(SELECT + " where m.internal_tax_code = :code and m.provider = :provider")
                .param("code", internalTaxCode).param("provider", provider.name()).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public List<ProviderCode> forCodes(Collection<String> internalTaxCodes) {
        if (internalTaxCodes.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(jdbc.sql(SELECT + " where m.internal_tax_code in (:codes)")
                .param("codes", internalTaxCodes).query((rs, n) -> map(rs)).list());
    }

    @Override
    public long countMapped(TaxProviderId provider) {
        return jdbc.sql("select count(*) from provider_code_map where provider = :p and provider_code is not null "
                + "and active").param("p", provider.name()).query(Long.class).single();
    }

    @Override
    public boolean update(ProviderCode e) {
        return jdbc.sql("""
                update provider_code_map set provider_code = :code, active = :active, verified = :verified,
                    verified_note = :note, map_version = map_version + 1, changed_by = :by, changed_at = :at
                where internal_tax_code = :internal and provider = :provider
                """)
                .param("code", e.providerCode()).param("active", e.active()).param("verified", e.verified())
                .param("note", e.verifiedNote()).param("by", e.changedBy()).param("at", Sql.ts(e.changedAt()))
                .param("internal", e.internalTaxCode()).param("provider", e.provider().name()).update() > 0;
    }

    private static ProviderCode map(ResultSet rs) throws SQLException {
        return new ProviderCode(rs.getString("internal_tax_code"), rs.getString("category_code"),
                Outcome.valueOf(rs.getString("outcome")), TaxProviderId.valueOf(rs.getString("provider")),
                rs.getString("provider_code"), rs.getBoolean("active"), rs.getBoolean("verified"),
                rs.getString("verified_note"), rs.getInt("map_version"), rs.getString("changed_by"),
                Sql.instant(rs, "changed_at"));
    }
}
