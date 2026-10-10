package com.afrotransact.offers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OfferTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void rejectsInvalidTermsWithEveryField() {
        assertThatThrownBy(() -> new Offer(UUID.randomUUID(), "bad", "AT00000001", null, null, null, "org_a",
                SellerKind.SELLER, " ", "SKU", OfferCondition.NEW, 0, null, "usd", -1, Fulfilment.SELLER, -1, 31,
                null, OfferStatus.ACTIVE, true, NOW, NOW, 0))
                .isInstanceOfSatisfying(OfferRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsKeys(
                        "atin", "sellerSku", "priceMinor", "currency", "shippingMinor", "quantity", "handlingDays"));
    }

    @Test
    void listPriceCannotBeBelowPrice() {
        assertThatThrownBy(() -> offer().revise(new Offer.Terms(OfferCondition.NEW, 999, 899L, 0,
                Fulfilment.SELLER, 3, 2, null, "SKU"), NOW))
                .isInstanceOfSatisfying(OfferRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).containsKey("listPriceMinor"));
    }

    @Test
    void lifecycleTransitions() {
        Offer paused = offer().pause(NOW);
        assertThat(paused.status()).isEqualTo(OfferStatus.PAUSED);
        assertThat(paused.live()).isFalse();
        assertThat(paused.resume(NOW).live()).isTrue();
        Offer withdrawn = paused.withdraw(NOW);
        assertThatThrownBy(() -> withdrawn.revise(withdrawn.terms(), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withdrawn.withdraw(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> offer().resume(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(offer().landedPriceMinor()).isEqualTo(999 + 299);
    }

    private static Offer offer() {
        return new Offer(UUID.randomUUID(), "AT00000002", "AT00000001", null, null, null, "org_a", SellerKind.SELLER,
                "SKU", "SKU", OfferCondition.NEW, 999, null, "USD", 299, Fulfilment.SELLER, 3, 2,
                new ShipFrom("Houston", "TX", "US", "77002"), OfferStatus.ACTIVE, true, NOW, NOW, 0);
    }
}
