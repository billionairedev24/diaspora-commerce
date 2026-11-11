package com.afrotransact.catalog.testsupport;

import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.domain.rules.RuleBook;
import com.afrotransact.catalog.port.RulesRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Integration tests that are not about the listing rules run with every question optional, so their fixtures need not
 * carry a label photo, a package and a country of origin. {@code ListingRulesIT} runs with the real matrix.
 */
@TestConfiguration
public class PermissiveRulesConfiguration {

    @Bean
    @Primary
    RulesRegistry permissiveRulesRegistry() {
        RuleBook real = RulesFixtures.book();
        CategoryRules open = RulesFixtures.permissive();
        Map<String, CategoryRules> all = new LinkedHashMap<>();
        real.categories().forEach((code, c) -> all.put(code, new CategoryRules(code, c.name(), c.department(),
                open.kindCode(), open.kindName(), c.approval(), c.note(), c.provisional(), open.fields())));
        RuleBook book = new RuleBook(real.kinds(), all);
        return () -> book;
    }
}
