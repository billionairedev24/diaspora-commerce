package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.adapter.out.rules.ClasspathRulesRegistry;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;

class ListingRulesServiceImplTest {

    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final ListingRulesService service = new ListingRulesServiceImpl(new ClasspathRulesRegistry(), categories);

    private static CategoryNode node(UUID id, UUID parent, String code, CategoryLevel level) {
        return new CategoryNode(id, parent, code, code, level, level == CategoryLevel.PRODUCT_TYPE ? "t" : null,
                "Dept › " + code, 0, true, 0, null, null);
    }

    @Test
    void aProductTypeLeafGetsTheRulesOfItsCategory() {
        UUID category = UUID.randomUUID();
        when(categories.findById(category)).thenReturn(Optional.of(node(category, null, "dairy-eggs", CategoryLevel.CATEGORY)));
        CategoryNode leaf = node(UUID.randomUUID(), category, "dairy-eggs-item", CategoryLevel.PRODUCT_TYPE);

        assertThat(service.forNode(leaf).kindCode()).isEqualTo("PER");
    }

    @Test
    void aCategoryWithoutRulesAndARequestedCategoryGetTheGeneralGoodsRules() {
        UUID category = UUID.randomUUID();
        when(categories.findById(category)).thenReturn(Optional.of(node(category, null, "staff-made-category", CategoryLevel.CATEGORY)));
        CategoryNode leaf = node(UUID.randomUUID(), category, "x", CategoryLevel.PRODUCT_TYPE);

        assertThat(service.forNode(leaf).kindCode()).isEqualTo("HOM");
        assertThat(service.forNode(leaf).provisional()).isTrue();
        assertThat(service.forNode(null).kindCode()).isEqualTo("HOM");
    }

    @Test
    void theCodeGeneralAnswersTheGeneralRulesAndAnUnknownCodeIsNotFound() {
        assertThat(service.forCode("general").name()).isEqualTo("General goods");
        assertThat(service.forCode("fabrics-textiles").provisional()).isTrue();
        assertThatThrownBy(() -> service.forCode("nope")).isInstanceOf(NotFoundException.class);
    }
}
