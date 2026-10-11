package com.afrotransact.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PickupEligibilityTest {

    @Test
    void platformPackagesArePickedUpAtAfroTransactCountersAndLockers() {
        assertThat(PickupEligibility.refusal("PLATFORM_COUNTER", "afrotransact", true, FulfilmentType.RETAIL_1P,
                "org_afrotransact_retail")).isNull();
        assertThat(PickupEligibility.refusal("LOCKER", "afrotransact", true, FulfilmentType.FBA_AFROTRANSACT,
                "org_seller")).isNull();
        assertThat(PickupEligibility.refusal("SELLER", "org_seller", true, FulfilmentType.FBA_AFROTRANSACT,
                "org_seller")).contains("AfroTransact pickup location");
    }

    @Test
    void sellerPackagesArePickedUpOnlyAtThatSellersOwnLocations() {
        assertThat(PickupEligibility.refusal("SELLER", "org_seller", true, FulfilmentType.SELLER_FULFILLED,
                "org_seller")).isNull();
        assertThat(PickupEligibility.refusal("SELLER", "org_other", true, FulfilmentType.SELLER_FULFILLED,
                "org_seller")).contains("seller's own");
        assertThat(PickupEligibility.refusal("PLATFORM_COUNTER", "afrotransact", true,
                FulfilmentType.SELLER_FULFILLED, "org_seller")).contains("seller's own");
    }

    @Test
    void unknownAndClosedPointsAreRefused() {
        assertThat(PickupEligibility.refusal(null, null, false, FulfilmentType.RETAIL_1P, "r")).contains("doesn't exist");
        assertThat(PickupEligibility.refusal("SELLER", "org_seller", false, FulfilmentType.SELLER_FULFILLED,
                "org_seller")).contains("isn't taking orders");
    }
}
