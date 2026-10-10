package com.afrotransact.taxreturns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import com.afrotransact.taxreturns.domain.tax.StateTaxRule;
import com.afrotransact.taxreturns.domain.tax.TaxRateTable;
import com.afrotransact.taxreturns.port.TaxRateRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTaxRateRepository implements TaxRateRepository {

    private final JdbcClient jdbc;

    JdbcTaxRateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TaxRateTable table() {
        return new TaxRateTable(states(), locals());
    }

    @Override
    public List<StateTaxRule> states() {
        return jdbc.sql("select * from tax_state_rule order by country, state").query(JdbcTaxRateRepository::state)
                .list();
    }

    @Override
    public Optional<StateTaxRule> state(String country, String state) {
        return jdbc.sql("select * from tax_state_rule where country = :c and state = :s").param("c", country)
                .param("s", state).query(JdbcTaxRateRepository::state).optional();
    }

    @Override
    public List<LocalTaxRate> locals() {
        return jdbc.sql("select * from tax_local_rate order by state, zip_prefix")
                .query((rs, n) -> new LocalTaxRate(rs.getString("state"), rs.getString("zip_prefix"),
                        rs.getString("name"), rs.getBigDecimal("rate"), rs.getBoolean("applies_to_grocery")))
                .list();
    }

    @Override
    public StateTaxRule upsertState(StateTaxRule r) {
        int rows = jdbc.sql("""
                insert into tax_state_rule (country, state, name, general_rate, grocery_rate, prepared_food_rate,
                                            shipping_taxable, facilitator, updated_at, version)
                values (:country, :state, :name, :general, :grocery, :prepared, :shipping, :facilitator, now(), 0)
                on conflict (country, state) do update set name = excluded.name,
                    general_rate = excluded.general_rate, grocery_rate = excluded.grocery_rate,
                    prepared_food_rate = excluded.prepared_food_rate, shipping_taxable = excluded.shipping_taxable,
                    facilitator = excluded.facilitator, updated_at = now(), version = tax_state_rule.version + 1
                where :version < 0 or tax_state_rule.version = :version
                """)
                .param("country", r.country()).param("state", r.state()).param("name", r.name())
                .param("general", r.generalRate()).param("grocery", r.groceryRate())
                .param("prepared", r.preparedFoodRate()).param("shipping", r.shippingTaxable())
                .param("facilitator", r.facilitator()).param("version", r.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("tax-returns.concurrent-update", "Rate changed; reload and retry");
        }
        return state(r.country(), r.state()).orElseThrow();
    }

    @Override
    public LocalTaxRate upsertLocal(LocalTaxRate r) {
        jdbc.sql("""
                insert into tax_local_rate (state, zip_prefix, name, rate, applies_to_grocery, updated_at)
                values (:state, :zip, :name, :rate, :grocery, now())
                on conflict (state, zip_prefix) do update set name = excluded.name, rate = excluded.rate,
                    applies_to_grocery = excluded.applies_to_grocery, updated_at = now()
                """)
                .param("state", r.state()).param("zip", r.zipPrefix()).param("name", r.name())
                .param("rate", r.rate()).param("grocery", r.appliesToGrocery())
                .update();
        return r;
    }

    @Override
    public boolean deleteLocal(String state, String zipPrefix) {
        return jdbc.sql("delete from tax_local_rate where state = :s and zip_prefix = :z").param("s", state)
                .param("z", zipPrefix).update() == 1;
    }

    private static StateTaxRule state(ResultSet rs, int rowNum) throws SQLException {
        return new StateTaxRule(rs.getString("country"), rs.getString("state"), rs.getString("name"),
                rs.getBigDecimal("general_rate"), rs.getBigDecimal("grocery_rate"),
                rs.getBigDecimal("prepared_food_rate"), rs.getBoolean("shipping_taxable"),
                rs.getBoolean("facilitator"), rs.getLong("version"));
    }
}
