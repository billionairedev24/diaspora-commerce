package com.afrotransact.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReferralProgramImplTest {

    private static ReferralProgram.OrderPlaced order(String line1, String line2, String postal, String country) {
        return new ReferralProgram.OrderPlaced("ORD-1", "cust", 4000, Instant.EPOCH, line1, line2, postal, country,
                List.of());
    }

    @Test
    void friendsAreShownAsFirstNameAndLastInitialOnly() {
        assertThat(ReferralProgramImpl.friendName(new ReferralProgram.SignUp("u", "Amara", "okafor", "SHOP", null, null,
                null, Instant.EPOCH))).isEqualTo("Amara O.");
        assertThat(ReferralProgramImpl.friendName(new ReferralProgram.SignUp("u", " Kofi ", null, "SHOP", null, null,
                null, Instant.EPOCH))).isEqualTo("Kofi");
        assertThat(ReferralProgramImpl.friendName(new ReferralProgram.SignUp("u", null, null, "SHOP", null, null, null,
                Instant.EPOCH))).isEqualTo("A friend");
    }

    @Test
    void addressesAreComparedWithoutCaseSpacingOrPunctuation() {
        String a = ReferralProgramImpl.normalisedAddress(order("12 Main St.", "Apt 4", "77002", "US"));
        assertThat(ReferralProgramImpl.normalisedAddress(order("12  main st", "apt. 4", "77002", "us"))).isEqualTo(a);
        assertThat(ReferralProgramImpl.normalisedAddress(order("12 Main St", "Apt 5", "77002", "US")))
                .as("another unit is another address").isNotEqualTo(a);
        assertThat(ReferralProgramImpl.normalisedAddress(order("12 Main St", null, "77003", "US"))).isNotEqualTo(a);
    }

    @Test
    void anOrderWithoutAStreetOrPostalCodeHasNoAddressToCompare() {
        assertThat(ReferralProgramImpl.normalisedAddress(order(null, null, "77002", "US"))).isNull();
        assertThat(ReferralProgramImpl.normalisedAddress(order("12 Main St", null, " ", "US"))).isNull();
    }
}
