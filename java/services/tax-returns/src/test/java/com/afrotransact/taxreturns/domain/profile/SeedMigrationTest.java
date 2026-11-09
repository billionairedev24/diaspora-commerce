package com.afrotransact.taxreturns.domain.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Drift check on the generated seed migration (V12): every outcome code, decision row and STRIPE code of the dataset is in
 * it. {@code python3 tools/taxonomy/gen_seed_sql.py --check} (make taxonomy-check) is the byte-exact check; this one runs
 * with the Java build so a stale V12 also fails here.
 */
class SeedMigrationTest {

    private static String seed() throws IOException {
        try (InputStream in = SeedMigrationTest.class.getResourceAsStream(
                "/db/migration/V12__seed_category_tax_profiles.sql")) {
            assertThat(in).as("V12 on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSeedHoldsEveryProfileOutcomeCodeRowAndStripeCodeOfTheDataset() throws IOException {
        Map<String, Object> map = TaxMapFixture.read("category_tax_map.json");
        String sql = seed();
        assertThat(sql).contains("'" + TaxMapFixture.ruleVersion(map) + "'");
        List<TaxProfile> profiles = TaxMapFixture.profiles(map);
        assertThat(profiles).hasSize(73);
        for (TaxProfile p : profiles) {
            assertThat(sql).contains("('" + p.categoryCode() + "', '");
            for (DecisionRow r : p.rows()) {
                assertThat(sql).as(r.id()).contains("('" + r.internalTaxCode() + "', '" + p.categoryCode() + "', '"
                        + r.outcome() + "'");
                assertThat(sql).contains("('" + r.id() + "', '" + p.categoryCode() + "'");
                r.providerCodes().forEach((provider, code) -> assertThat(sql)
                        .contains("('" + r.internalTaxCode() + "', '" + provider + "', '" + code + "', true, false"));
            }
        }
        assertThat(sql).as("AVALARA starts empty and inactive").contains("', 'AVALARA', null, false, false, null)");
    }

    @Test
    void theMigrationsAreNumberedAsReserved() throws IOException {
        for (String name : List.of("V10__category_tax_profiles.sql", "V11__variant_classification.sql",
                "V12__seed_category_tax_profiles.sql")) {
            try (InputStream in = SeedMigrationTest.class.getResourceAsStream("/db/migration/" + name)) {
                assertThat(in).as(name).isNotNull();
            }
        }
    }
}
