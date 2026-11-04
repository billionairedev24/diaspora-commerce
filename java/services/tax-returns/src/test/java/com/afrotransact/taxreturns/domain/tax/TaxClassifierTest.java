package com.afrotransact.taxreturns.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TaxClassifierTest {

    private static final List<TaxCode> CODES = List.of(
            new TaxCode("GENERAL_GOODS", "General", TaxCategory.GENERAL, "txcd_99999999", true),
            new TaxCode("CLOTHING", "Clothing", TaxCategory.GENERAL, null, true),
            new TaxCode("PACKAGED_FOOD", "Packaged food", TaxCategory.GROCERY, "txcd_food", true),
            new TaxCode("PREPARED_FOOD", "Prepared food", TaxCategory.PREPARED_FOOD, null, true),
            new TaxCode("SOFT_DRINK", "Soft drink", TaxCategory.GENERAL, null, true),
            new TaxCode("RETIRED", "Retired", TaxCategory.GROCERY, null, false));

    private static final List<ClassificationRule> RULES = List.of(
            new ClassificationRule(1L, "dress", null, null, "CLOTHING", null),
            new ClassificationRule(2L, "grocery", "tax_class", "Packaged, shelf-stable", "PACKAGED_FOOD", null),
            new ClassificationRule(3L, "grocery", "tax_class", "Hot or ready-to-eat", "PREPARED_FOOD", null),
            new ClassificationRule(4L, "grocery", "tax_class", "Soft drink or sweetened drink", "SOFT_DRINK", null),
            new ClassificationRule(5L, "grocery", "tax_class", "Old answer", "RETIRED", null));

    private final TaxClassifier classifier = new TaxClassifier(CODES, RULES);

    @Test
    void typeAloneIsEnoughForClothing() {
        TaxClassification c = classifier.classify("dress", Map.of());
        assertThat(c.taxCode()).isEqualTo("CLOTHING");
        assertThat(c.basis()).isEqualTo(TaxClassification.Basis.TYPE);
        assertThat(c.needsReview()).isFalse();
    }

    @Test
    void theSellersAnswerDecidesFoodAndDrink() {
        assertThat(classifier.classify("grocery", Map.of("tax_class", "Packaged, shelf-stable")).category())
                .isEqualTo(TaxCategory.GROCERY);
        assertThat(classifier.classify("grocery", Map.of("tax_class", "Hot or ready-to-eat")).category())
                .isEqualTo(TaxCategory.PREPARED_FOOD);
        TaxClassification drink = classifier.classify("grocery", Map.of("tax_class", "soft drink or sweetened drink"));
        assertThat(drink.taxCode()).isEqualTo("SOFT_DRINK");
        assertThat(drink.category()).isEqualTo(TaxCategory.GENERAL);
        assertThat(drink.basis()).isEqualTo(TaxClassification.Basis.ANSWER);
    }

    @Test
    void anUnansweredOrUnknownItemIsGeneralTaxableAndNeedsReview() {
        for (TaxClassification c : List.of(classifier.classify("grocery", Map.of()),
                classifier.classify("grocery", Map.of("tax_class", "something new")),
                classifier.classify("custom", Map.of()), classifier.classify(null, null))) {
            assertThat(c.taxCode()).isEqualTo("GENERAL_GOODS");
            assertThat(c.category()).isEqualTo(TaxCategory.GENERAL);
            assertThat(c.providerCode()).isEqualTo("txcd_99999999");
            assertThat(c.needsReview()).isTrue();
        }
    }

    @Test
    void aRuleForAnInactiveCodeIsIgnored() {
        assertThat(classifier.classify("grocery", Map.of("tax_class", "Old answer")).needsReview()).isTrue();
    }

    @Test
    void anAnswerRuleBeatsATypeRuleWhateverTheirOrder() {
        TaxClassifier c = new TaxClassifier(CODES, List.of(
                new ClassificationRule(1L, "grocery", "tax_class", "Hot or ready-to-eat", "PREPARED_FOOD", null),
                new ClassificationRule(2L, "grocery", null, null, "PACKAGED_FOOD", null)));
        assertThat(c.classify("grocery", Map.of("tax_class", "Hot or ready-to-eat")).taxCode())
                .isEqualTo("PREPARED_FOOD");
        assertThat(c.classify("grocery", Map.of()).taxCode()).isEqualTo("PACKAGED_FOOD");
    }
}
