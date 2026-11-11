package com.afrotransact.catalog.testsupport;

import java.util.Arrays;
import java.util.List;

import com.afrotransact.catalog.adapter.out.rules.ClasspathRulesRegistry;
import com.afrotransact.catalog.application.ListingRulesService;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.domain.rules.FieldRule;
import com.afrotransact.catalog.domain.rules.FieldStatus;
import com.afrotransact.catalog.domain.rules.RuleBook;
import com.afrotransact.catalog.domain.rules.RuleField;

/** Rules for tests: the real rule book, and a permissive category where every question is optional. */
public final class RulesFixtures {

    private static final RuleBook BOOK = new ClasspathRulesRegistry().book();

    private RulesFixtures() {
    }

    public static RuleBook book() {
        return BOOK;
    }

    /** A category that asks nothing (every field optional, no package): tests of other rules are not about the matrix. */
    public static CategoryRules permissive() {
        CategoryRules any = BOOK.forCategory("beans-pantry-sauces").orElseThrow();
        List<FieldRule> fields = Arrays.stream(RuleField.values())
                .map(f -> new FieldRule(f, "", f.id(), f == RuleField.PACKAGE_SIZE || f == RuleField.PACKAGE_WEIGHT
                        ? FieldStatus.HIDDEN : FieldStatus.OPTIONAL, "")).toList();
        return new CategoryRules("permissive", "Permissive", "Test", any.kindCode(), any.kindName(), "open", "", false,
                fields);
    }

    /** A rules service that answers {@link #permissive()} for every node. */
    public static ListingRulesService permissiveService() {
        return new ListingRulesService() {
            @Override
            public RuleBook book() {
                return BOOK;
            }

            @Override
            public CategoryRules forCode(String categoryCode) {
                return permissive();
            }

            @Override
            public CategoryRules forNode(CategoryNode node) {
                return permissive();
            }
        };
    }
}
