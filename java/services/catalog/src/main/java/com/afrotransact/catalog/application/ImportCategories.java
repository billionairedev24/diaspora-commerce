package com.afrotransact.catalog.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.imports.ImportCategory;
import com.afrotransact.catalog.domain.rules.RuleBook;

/** The categories a bulk upload can name, each with the listing rules that decide its columns and checks. */
final class ImportCategories {

    private ImportCategories() {
    }

    /** Product-type categories of the tree (optionally of one product type) with the rules of their category. */
    static List<ImportCategory> leaves(List<CategoryNode> tree, String typeCode, RuleBook book) {
        Map<UUID, CategoryNode> byId = new HashMap<>();
        tree.forEach(n -> byId.put(n.id(), n));
        return tree.stream()
                .filter(n -> n.level() == CategoryLevel.PRODUCT_TYPE && n.productTypeCode() != null)
                .filter(n -> typeCode == null || typeCode.equals(n.productTypeCode()))
                .map(n -> {
                    CategoryNode parent = n.parentId() == null ? null : byId.get(n.parentId());
                    return new ImportCategory(n.id(), n.path(), n.productTypeCode(), n.taxStatus(),
                            book.forCategoryOrGeneral(parent == null ? null : parent.code()));
                })
                .toList();
    }
}
