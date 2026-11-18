package com.afrotransact.catalog.domain.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What a seller may type as a new brand: real names pass, links, e-mail addresses and profanity do not. */
class BrandProposalRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"Nike", "L'Oréal", "Levi’s", "H&M", "Dr. Martens", "Ben & Jerry's", "7UP", "3M",
            "Nestlé®", "Yves Saint Laurent", "A.P.C.", "Adire Lagos", "Ẹ̀dé Studio", "Dark & Lovely",
            "Mama Kano's Kitchen"})
    void realBrandNamesAreAccepted(String name) {
        assertThat(BrandProposalRules.check(name)).isEqualTo(name.trim());
    }

    @Test
    void theNameIsTrimmedAndInnerSpacesCollapsed() {
        assertThat(BrandProposalRules.check("   Adire    Lagos  ")).isEqualTo("Adire Lagos");
    }

    @ParameterizedTest
    @ValueSource(strings = {"shop@brand.com", "me@x", "Buy at www.deals.ng", "https://cheap.example", "bestdeals.com",
            "Brand.shop"})
    void linksAndEmailAddressesAreRefused(String name) {
        assertThatThrownBy(() -> BrandProposalRules.check(name)).isInstanceOf(CatalogRuleViolation.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Fuck Brand", "SHIT", "Putain de marque", "Mi puta marca", "Big Porn Co", "Scam House"})
    void profanityIsRefusedAsWholeWordsInAnyCase(String name) {
        assertThatThrownBy(() -> BrandProposalRules.check(name)).isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void aBlockedWordInsideALongerWordIsNotProfanity() {
        assertThat(BrandProposalRules.check("Sussex Classic")).isEqualTo("Sussex Classic");
        assertThat(BrandProposalRules.check("Scampi Gold")).isEqualTo("Scampi Gold");
        assertThat(BrandProposalRules.check("Dickens & Co")).isEqualTo("Dickens & Co");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<b>Nike</b>", "Nike;DROP", "Nike★", "Brand😀", "{brand}", "x", "!!", "12345", "aaaaaaa", "-Nike"})
    void oddCharactersAndNamesWithoutLettersAreRefused(String name) {
        assertThatThrownBy(() -> BrandProposalRules.check(name)).isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void theViolationNamesTheBrandFieldSoTheFormCanShowIt() {
        assertThatThrownBy(() -> BrandProposalRules.check("me@brand.com"))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fields()).contains("attributes.brand"));
    }
}
