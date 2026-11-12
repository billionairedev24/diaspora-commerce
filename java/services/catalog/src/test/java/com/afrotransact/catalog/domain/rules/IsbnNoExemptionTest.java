package com.afrotransact.catalog.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.testsupport.RulesFixtures;
import org.junit.jupiter.api.Test;

/**
 * S387: product types that sell by ISBN allow no "no barcode" exemption, whatever the category's own rule says about other
 * kinds of product. The seller's "I have no barcode" tick does not excuse the number; a valid ISBN-13 is always needed.
 */
class IsbnNoExemptionTest {

    private static final String ISBN_13 = "9780306406157";
    private static final String GTIN_13 = "4006381333931";

    private static ListingSubmission submission(IdentifierRule rule, String barcode, boolean exempt) {
        return new ListingSubmission(Map.of(), "A description", 1, 0, null, Map.of(), false, false, rule,
                List.of(new ListingSubmission.VariantFacts(barcode, exempt, null)));
    }

    private static Map<String, String> errors(String category, IdentifierRule rule, String barcode, boolean exempt) {
        CategoryRules rules = RulesFixtures.book().forCategory(category).orElseThrow();
        return ListingRuleChecker.check(rules, submission(rule, barcode, exempt));
    }

    @Test
    void aBookCannotBeExemptedFromItsIsbn() {
        assertThat(errors("printed-books", IdentifierRule.ISBN, "", true))
                .containsEntry("variants[0].gtin", "ISBN-13 required (978/979 prefix, valid check digit)");
    }

    @Test
    void theExemptionTickDoesNotExcuseAWrongNumberEither() {
        assertThat(errors("printed-books", IdentifierRule.ISBN, GTIN_13, true)).containsKey("variants[0].gtin");
        assertThat(errors("printed-books", IdentifierRule.ISBN, "9780306406158", false)).containsKey("variants[0].gtin");
        assertThat(errors("printed-books", IdentifierRule.ISBN, "", false)).containsKey("variants[0].gtin");
    }

    @Test
    void aValidIsbn13Passes() {
        assertThat(errors("printed-books", IdentifierRule.ISBN, ISBN_13, false)).doesNotContainKey("variants[0].gtin");
        assertThat(errors("printed-books", IdentifierRule.ISBN, "9790060115615", false)).doesNotContainKey("variants[0].gtin");
    }

    @Test
    void onlyTheIdentifierRuleOfTheTypeRemovesTheExemptionInACategoryThatOtherwiseAllowsIt() {
        assertThat(errors("bedding-linens", IdentifierRule.GTIN, "", true)).doesNotContainKey("variants[0].gtin");
        assertThat(errors("bedding-linens", IdentifierRule.ISBN, "", true)).containsKey("variants[0].gtin");
    }

    @Test
    void aCategoryWhereTheBarcodeIsRequiredNeverTakesTheExemption() {
        assertThat(errors("phones", IdentifierRule.GTIN, "", true))
                .containsEntry("variants[0].gtin", "A barcode is required for this kind of product");
    }
}
