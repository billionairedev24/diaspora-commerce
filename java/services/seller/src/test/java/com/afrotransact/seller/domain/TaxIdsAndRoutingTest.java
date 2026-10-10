package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaxIdsAndRoutingTest {

    @Test
    void ssnIsValidatedAndDashedServerSide() {
        assertThat(TaxIds.individualType("123456789")).contains(TaxIdType.SSN);
        assertThat(TaxIds.individualType("123-45-6789")).contains(TaxIdType.SSN);
        assertThat(TaxIds.format(TaxIdType.SSN, "123456789")).isEqualTo("123-45-6789");
        assertThat(TaxIds.last4("123-45-6789")).isEqualTo("6789");
        assertThat(TaxIds.masked(TaxIdType.SSN, "6789")).isEqualTo("•••-••-6789");
    }

    @Test
    void impossibleSsnsAreRejected() {
        assertThat(TaxIds.individualType("000-12-3456")).isEmpty();
        assertThat(TaxIds.individualType("666-12-3456")).isEmpty();
        assertThat(TaxIds.individualType("123-00-4567")).isEmpty();
        assertThat(TaxIds.individualType("123-45-0000")).isEmpty();
        assertThat(TaxIds.individualType("12345678")).isEmpty();
        assertThat(TaxIds.individualType("1234567890")).isEmpty();
        assertThat(TaxIds.individualType("12a-45-6789")).isEmpty();
    }

    @Test
    void itinsStartWithNineAndUseItinGroups() {
        assertThat(TaxIds.individualType("912-70-1234")).contains(TaxIdType.ITIN);
        assertThat(TaxIds.individualType("900-12-1234")).isEmpty();
    }

    @Test
    void einIsValidatedAndDashedAfterTwoDigits() {
        assertThat(TaxIds.isValidEin("12-3456789")).isTrue();
        assertThat(TaxIds.isValidEin("123456789")).isTrue();
        assertThat(TaxIds.format(TaxIdType.EIN, "123456789")).isEqualTo("12-3456789");
        assertThat(TaxIds.masked(TaxIdType.EIN, "6789")).isEqualTo("••-•••6789");
        assertThat(TaxIds.isValidEin("07-1234567")).as("never-assigned prefix").isFalse();
        assertThat(TaxIds.isValidEin("12-345678")).isFalse();
    }

    @Test
    void routingNumbersUseTheAbaChecksum() {
        assertThat(RoutingNumbers.isValid("021000021")).as("JPMorgan Chase NY").isTrue();
        assertThat(RoutingNumbers.isValid("011000015")).as("Federal Reserve Boston").isTrue();
        assertThat(RoutingNumbers.isValid("121000358")).as("Bank of America CA").isTrue();
        assertThat(RoutingNumbers.isValid("021000022")).as("bad checksum").isFalse();
        assertThat(RoutingNumbers.isValid("991000021")).as("invalid prefix").isFalse();
        assertThat(RoutingNumbers.isValid("02100002")).isFalse();
        assertThat(RoutingNumbers.isValid(null)).isFalse();
    }
}
