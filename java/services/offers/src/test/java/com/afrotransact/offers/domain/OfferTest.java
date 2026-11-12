package com.afrotransact.offers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
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

    @Test
    void anOfferHeldForTaxIsNeverLive() {
        Offer held = offer().heldForTax(Offer.HOLD_DECISION_MISSING, NOW);

        assertThat(held.status()).isEqualTo(OfferStatus.TAX_HELD);
        assertThat(held.heldReason()).isEqualTo("TAX_DECISION_MISSING");
        assertThat(held.taxBasis()).as("a held offer has no basis").isNull();
        assertThat(held.live()).isFalse();
        // The seller cannot pause or resume their way out of the hold.
        assertThatThrownBy(() -> held.pause(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> held.resume(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyAHeldOfferIsReleasedAndOnlyOnABasis() {
        Offer held = offer().heldForTax(Offer.HOLD_DECISION_MISSING, NOW);

        Offer released = held.releasedFromTaxHold(Offer.TAX_BASIS_SELLER_DECISION, NOW);
        assertThat(released.status()).isEqualTo(OfferStatus.ACTIVE);
        assertThat(released.live()).isTrue();
        assertThat(released.heldReason()).isNull();
        assertThat(released.taxBasis()).isEqualTo("SELLER_DECISION");

        assertThatThrownBy(() -> held.releasedFromTaxHold(" ", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> released.releasedFromTaxHold(Offer.TAX_BASIS_SELLER_DECISION, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPausedOfferWithoutAVerifiedBasisComesBackHeldNotLive() {
        Offer unverified = new Offer(UUID.randomUUID(), "AT00000002", "AT00000001", null, null, null, "org_a",
                SellerKind.SELLER, "SKU", "SKU", OfferCondition.NEW, 999, null, "USD", 299, Fulfilment.SELLER, 3, 2,
                new ShipFrom("Houston", "TX", "US", "77002"), OfferStatus.PAUSED, true, NOW, NOW, 0);

        Offer resumed = unverified.resume(NOW);

        assertThat(resumed.status()).isEqualTo(OfferStatus.TAX_HELD);
        assertThat(resumed.heldReason()).isEqualTo(Offer.HOLD_UNVERIFIED);
        assertThat(resumed.live()).isFalse();
    }

    @Test
    void theWriteGuardRefusesAnActiveOfferWithoutATaxBasis() {
        Offer unverified = new Offer(UUID.randomUUID(), "AT00000002", "AT00000001", null, null, null, "org_a",
                SellerKind.SELLER, "SKU", "SKU", OfferCondition.NEW, 999, null, "USD", 299, Fulfilment.SELLER, 3, 2,
                new ShipFrom("Houston", "TX", "US", "77002"), OfferStatus.ACTIVE, true, NOW, NOW, 0);

        assertThat(OfferRequiresTax.holds(unverified)).isFalse();
        assertThatThrownBy(() -> OfferRequiresTax.require(unverified))
                .isInstanceOf(OfferRequiresTax.TaxBasisMissing.class);
        assertThat(OfferRequiresTax.holds(offer())).isTrue();
        assertThat(OfferRequiresTax.holds(unverified.heldForTax(Offer.HOLD_UNVERIFIED, NOW))).isTrue();
        assertThat(OfferRequiresTax.holds(unverified.withTaxBasis(Offer.TAX_BASIS_OWNER_PAGE, NOW))).isTrue();
    }

    @Test
    void theConditionNoteIsTrimmedAndBlankMeansNone() {
        assertThat(offerWithNote("  Small scratch on the lid  ").conditionNote()).isEqualTo("Small scratch on the lid");
        assertThat(offerWithNote("   ").conditionNote()).isNull();
    }

    @Test
    void aPackageNeedsPositiveSizesAndWeight() {
        assertThatThrownBy(() -> new OfferPackage(0, 5, 5, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new OfferPackage(12, 8, 4, 2.5).longestEdgeIn()).isEqualTo(12.0);
    }

    /** An offer the seller may sell: ACTIVE with the basis that says why. */
    private static Offer offer() {
        return new Offer(UUID.randomUUID(), "AT00000002", "AT00000001", null, null, null, "org_a", SellerKind.SELLER,
                "SKU", "SKU", OfferCondition.NEW, 999, null, "USD", 299, Fulfilment.SELLER, 3, 2,
                new ShipFrom("Houston", "TX", "US", "77002"), OfferStatus.ACTIVE, true, NOW, NOW, 0, null, null,
                Map.of(), null, Offer.TAX_BASIS_SELLER_DECISION);
    }

    private static Offer offerWithNote(String note) {
        return new Offer(UUID.randomUUID(), "AT00000002", "AT00000001", null, null, null, "org_a", SellerKind.SELLER,
                "SKU", "SKU", OfferCondition.USED_GOOD, 999, null, "USD", 299, Fulfilment.SELLER, 3, 2,
                new ShipFrom("Houston", "TX", "US", "77002"), OfferStatus.ACTIVE, true, NOW, NOW, 0, note, null,
                Map.of(), null, Offer.TAX_BASIS_SELLER_DECISION);
    }
}
