package com.afrotransact.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Which parts of the tree an organisation may list in, whatever the departments are called. */
class DepartmentScopeTest {

    private static final UUID FASHION = UUID.randomUUID();
    private static final UUID WOMEN = UUID.randomUUID();
    private static final UUID DRESSES = UUID.randomUUID();
    private static final UUID GROCERIES = UUID.randomUUID();
    private static final UUID PANTRY = UUID.randomUUID();
    private static final UUID GRAINS = UUID.randomUUID();
    private static final UUID HEALTH = UUID.randomUUID();
    private static final UUID WELLNESS = UUID.randomUUID();
    private static final UUID TEAS = UUID.randomUUID();

    private static final CategoryNode FASHION_NODE = node(FASHION, null, "fashion", "Fashion", CategoryLevel.DEPARTMENT,
            null, "Fashion");
    private static final CategoryNode DRESSES_NODE = node(DRESSES, WOMEN, "fashion-women-dresses", "Dresses",
            CategoryLevel.PRODUCT_TYPE, "dress", "Fashion › Women › Dresses");
    private static final CategoryNode GRAINS_NODE = node(GRAINS, PANTRY, "groceries-pantry-grains", "Grains",
            CategoryLevel.PRODUCT_TYPE, "grocery", "Groceries › Pantry › Grains");
    /** A product type used in two departments. */
    private static final CategoryNode TEAS_NODE = node(TEAS, WELLNESS, "health-teas", "Herbal teas",
            CategoryLevel.PRODUCT_TYPE, "grocery", "Health › Wellness › Herbal teas");

    private static final List<CategoryNode> TREE = List.of(FASHION_NODE,
            node(WOMEN, FASHION, "fashion-women", "Women", CategoryLevel.CATEGORY, null, "Fashion › Women"),
            DRESSES_NODE,
            node(GROCERIES, null, "groceries", "Groceries", CategoryLevel.DEPARTMENT, null, "Groceries"),
            node(PANTRY, GROCERIES, "groceries-pantry", "Pantry", CategoryLevel.CATEGORY, null, "Groceries › Pantry"),
            GRAINS_NODE,
            node(HEALTH, null, "health", "Health", CategoryLevel.DEPARTMENT, null, "Health"),
            node(WELLNESS, HEALTH, "health-wellness", "Wellness", CategoryLevel.CATEGORY, null, "Health › Wellness"),
            TEAS_NODE);

    private static CategoryNode node(UUID id, UUID parent, String code, String name, CategoryLevel level,
                                     String type, String path) {
        return new CategoryNode(id, parent, code, name, level, type, path, 0, true, 0, null, null);
    }

    private final DepartmentScope foodOnly = DepartmentScope.of(Set.of("groceries"), TREE);

    @Test
    void anOrganisationListsOnlyBelowItsDepartments() {
        assertThat(foodOnly.allows(GRAINS_NODE)).isTrue();
        assertThat(foodOnly.allows(DRESSES_NODE)).isFalse();
        assertThat(foodOnly.filter(TREE)).extracting(CategoryNode::code).containsExactly("groceries",
                "groceries-pantry", "groceries-pantry-grains");
    }

    @Test
    void aProductTypeSharedBetweenDepartmentsIsAllowedThroughAnyOfThem() {
        assertThat(foodOnly.allowsProductType("grocery")).isTrue();
        assertThat(DepartmentScope.of(Set.of("health"), TREE).allowsProductType("grocery")).isTrue();
        assertThat(foodOnly.allowsProductType("dress")).isFalse();
    }

    @Test
    void aSavedPathIsJudgedByItsDepartment() {
        assertThat(foodOnly.allowsPath("Groceries › Pantry")).isTrue();
        assertThat(foodOnly.allowsPath("groceries")).isTrue();
        assertThat(foodOnly.allowsPath("Fashion › Women › Dresses")).isFalse();
        assertThat(foodOnly.allowsPath(null)).as("nothing chosen yet").isTrue();
        assertThat(foodOnly.allowsPath(" ")).isTrue();
    }

    @Test
    void theRefusalNamesTheDepartmentAndSaysHowToAddIt() {
        assertThatThrownBy(() -> foodOnly.require(DRESSES_NODE)).isInstanceOfSatisfying(DepartmentNotAllowed.class, e -> {
            assertThat(e.code()).isEqualTo("catalog.department-not-allowed");
            assertThat(e.getMessage()).contains("Fashion").contains("Request it");
        });
        assertThatThrownBy(() -> foodOnly.requireProductType("dress")).isInstanceOf(DepartmentNotAllowed.class);
        assertThatThrownBy(() -> foodOnly.requirePath("Fashion › Men")).isInstanceOf(DepartmentNotAllowed.class);
        assertThatThrownBy(() -> foodOnly.requireAny(List.of(DRESSES_NODE))).isInstanceOf(DepartmentNotAllowed.class);
        assertThatCode(() -> foodOnly.requireAny(List.of(DRESSES_NODE, GRAINS_NODE))).doesNotThrowAnyException();
    }

    @Test
    void anOrganisationWithNoDepartmentsCannotListAnythingAndIsToldWhy() {
        DepartmentScope none = DepartmentScope.of(Set.of(), TREE);
        assertThat(none.allows(GRAINS_NODE)).isFalse();
        assertThatThrownBy(() -> none.require(GRAINS_NODE)).isInstanceOfSatisfying(DepartmentNotAllowed.class,
                e -> assertThat(e.code()).isEqualTo("catalog.no-departments"));
    }

    @Test
    void retailAndStaffAreNotRestricted() {
        DepartmentScope all = DepartmentScope.unrestricted();
        assertThat(all.isUnrestricted()).isTrue();
        assertThat(all.allows(DRESSES_NODE)).isTrue();
        assertThat(all.allowsPath("Anything › at all")).isTrue();
        assertThatCode(() -> all.requireProductType("dress")).doesNotThrowAnyException();
        assertThat(all.filter(TREE)).hasSameSizeAs(TREE);
    }
}
