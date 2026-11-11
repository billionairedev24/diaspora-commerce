package com.afrotransact.catalog.testsupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.domain.rules.FieldRule;
import com.afrotransact.catalog.domain.rules.RuleBook;
import com.afrotransact.catalog.domain.rules.RuleField;
import com.afrotransact.catalog.port.RulesRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Like {@link PermissiveRulesConfiguration} (every question optional) except the brand, which keeps the real matrix
 * status of each category, so the brand registry tests still see hidden, required and exemption brands.
 */
@TestConfiguration
public class BrandRulesConfiguration {

    @Bean
    @Primary
    RulesRegistry brandRulesRegistry() {
        RuleBook real = RulesFixtures.book();
        CategoryRules open = RulesFixtures.permissive();
        Map<String, CategoryRules> all = new LinkedHashMap<>();
        real.categories().forEach((code, c) -> all.put(code, new CategoryRules(code, c.name(), c.department(),
                "HOM", "General goods", c.approval(), c.note(), c.provisional(), withRealBrand(open, c))));
        RuleBook book = new RuleBook(real.kinds(), all);
        return () -> book;
    }

    private static List<FieldRule> withRealBrand(CategoryRules open, CategoryRules real) {
        return open.fields().stream().map(f -> f.field() == RuleField.BRAND ? real.rule(RuleField.BRAND) : f).toList();
    }
}
