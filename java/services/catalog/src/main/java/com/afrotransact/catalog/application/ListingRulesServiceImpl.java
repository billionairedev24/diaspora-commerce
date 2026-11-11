package com.afrotransact.catalog.application;

import java.util.Optional;

import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.domain.rules.RuleBook;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.RulesRegistry;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

@Component
class ListingRulesServiceImpl implements ListingRulesService {

    private final RulesRegistry registry;
    private final CategoryRepository categories;

    ListingRulesServiceImpl(RulesRegistry registry, CategoryRepository categories) {
        this.registry = registry;
        this.categories = categories;
    }

    @Override
    public RuleBook book() {
        return registry.book();
    }

    @Override
    public CategoryRules forCode(String categoryCode) {
        return registry.book().forCategory(categoryCode)
                .orElseThrow(() -> new NotFoundException("catalog.listing-rules-not-found",
                        "No listing rules for category " + categoryCode));
    }

    @Override
    public CategoryRules forNode(CategoryNode node) {
        if (node == null) {
            return registry.book().general(null);
        }
        String code = node.code();
        if (node.level() == CategoryLevel.PRODUCT_TYPE && node.parentId() != null) {
            Optional<CategoryNode> parent = categories.findById(node.parentId());
            code = parent.map(CategoryNode::code).orElse(code);
        }
        return registry.book().forCategoryOrGeneral(code);
    }
}
