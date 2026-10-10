package com.afrotransact.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.afrotransact.ai.domain.guardrail.DefaultGroundingVerifier;
import com.afrotransact.ai.domain.guardrail.GroundingFacts;
import com.afrotransact.ai.domain.guardrail.GroundingResult;
import com.afrotransact.ai.domain.lola.ProductCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroundingVerifierTest {

    private final DefaultGroundingVerifier verifier = new DefaultGroundingVerifier();
    private final GroundingFacts facts = new GroundingFacts();

    @BeforeEach
    void facts() {
        facts.addProduct(card("ATJOL00001", "Jollof seasoning", 499));
        facts.addProduct(card("ATRICE0002", "Parboiled rice 5kg", 1_299));
        facts.addAmount(3_596);
    }

    @Test
    void keepsSentencesBackedByToolResultsAndBuildsCardsFromThem() {
        GroundingResult r = verifier.verify("Try [[ATJOL00001]] for $4.99 with [[ATRICE0002]] at $12.99. "
                + "Together that's $35.96 for 8 people.", facts);
        assertThat(r.dropped()).isEmpty();
        assertThat(r.text()).isEqualTo("Try [[ATJOL00001]] for $4.99 with [[ATRICE0002]] at $12.99. "
                + "Together that's $35.96 for 8 people.");
        assertThat(r.cards()).extracting(ProductCard::atin).containsExactly("ATJOL00001", "ATRICE0002");
        assertThat(r.cards().getFirst().priceMinor()).isEqualTo(499);
    }

    @Test
    void dropsSentencesWithInventedProducts() {
        GroundingResult r = verifier.verify("Jollof seasoning [[ATJOL00001]] is in stock. "
                + "You could also add Magic Cube [[ATFAKE9999]]. Enjoy!", facts);
        assertThat(r.text()).isEqualTo("Jollof seasoning [[ATJOL00001]] is in stock. Enjoy!");
        assertThat(r.dropped()).containsExactly(new GroundingResult.DroppedClaim("ATIN", "ATFAKE9999"));
        assertThat(r.cards()).extracting(ProductCard::atin).containsExactly("ATJOL00001");
    }

    @Test
    void dropsSentencesWithInventedPrices() {
        GroundingResult r = verifier.verify("The seasoning [[ATJOL00001]] is only $3.50 today!\n"
                + "Rice [[ATRICE0002]] is $12.99.\n", facts);
        assertThat(r.text()).isEqualTo("\nRice [[ATRICE0002]] is $12.99.\n");
        assertThat(r.dropped()).extracting(GroundingResult.DroppedClaim::value).containsExactly("$3.50");
        assertThat(r.cards()).extracting(ProductCard::atin).containsExactly("ATRICE0002");
    }

    @Test
    void recognisesOtherMoneyFormats() {
        assertThat(verifier.verify("It costs 12.99 dollars.", facts).dropped()).isEmpty();
        assertThat(verifier.verify("It costs 13 dollars.", facts).dropped()).hasSize(1);
        assertThat(verifier.verify("It is USD 1,299.00 wholesale.", facts).dropped()).hasSize(1);
        assertThat(verifier.verify("Serves 8 people with 2 cups.", facts).dropped()).isEmpty();
    }

    @Test
    void decimalPointsAreNotSentenceBoundaries() {
        assertThat(verifier.completeSentencesEnd("It is $4.")).isZero();
        assertThat(verifier.completeSentencesEnd("It is $4.99. Next")).isEqualTo("It is $4.99.".length());
        assertThat(verifier.completeSentencesEnd("Line one\nline two")).isEqualTo("Line one\n".length());
    }

    private static ProductCard card(String atin, String title, long price) {
        return new ProductCard(atin, title, "", "AfroTransact Retail", price, "USD", true, 20, false, 1, 4.6, "");
    }
}
