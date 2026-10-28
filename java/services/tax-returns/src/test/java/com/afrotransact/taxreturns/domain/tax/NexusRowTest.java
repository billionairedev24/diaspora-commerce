package com.afrotransact.taxreturns.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NexusRowTest {

    @Test
    void thePercentIsTheHigherOfTheSalesAndTransactionMeasures() {
        assertThat(NexusRow.percentOf(5_000_000, 10_000_000, 50, null)).isEqualTo(50);
        assertThat(NexusRow.percentOf(5_000_000, 10_000_000, 150, 200)).isEqualTo(75);
        assertThat(NexusRow.percentOf(5_000_000, 10_000_000, 250, 200)).isEqualTo(125);
        assertThat(NexusRow.percentOf(0, 0, 10, null)).isZero();
        assertThat(NexusRow.percentOf(-500, 10_000_000, 0, null)).as("net refunds can't go below zero").isZero();
    }

    @Test
    void statusFollowsRegistrationThenHowCloseTheStateIs() {
        assertThat(NexusRow.statusOf(false, 79)).isEqualTo(NexusStatus.BELOW);
        assertThat(NexusRow.statusOf(false, 80)).isEqualTo(NexusStatus.APPROACHING);
        assertThat(NexusRow.statusOf(false, 99)).isEqualTo(NexusStatus.APPROACHING);
        assertThat(NexusRow.statusOf(false, 100)).isEqualTo(NexusStatus.EXCEEDED);
        assertThat(NexusRow.statusOf(true, 250)).as("registered wherever the sales are").isEqualTo(NexusStatus.REGISTERED);
    }
}
