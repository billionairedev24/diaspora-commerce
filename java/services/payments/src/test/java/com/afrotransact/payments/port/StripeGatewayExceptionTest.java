package com.afrotransact.payments.port;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which Stripe failures are a definitive "no" about the object (refusal) and which are worth retrying or fixing. */
class StripeGatewayExceptionTest {

    private static StripeGatewayException failure(int status) {
        return new StripeGatewayException("Stripe says no", "balance_insufficient", null, status, null);
    }

    @Test
    void aClientErrorAboutTheObjectIsARefusal() {
        assertThat(failure(400).refusal()).isTrue();
        assertThat(failure(402).refusal()).isTrue();
        assertThat(failure(404).refusal()).isTrue();
    }

    @Test
    void transientFailuresAndPlatformMisconfigurationAreNotRefusals() {
        assertThat(failure(0).refusal()).as("network failure or timeout").isFalse();
        assertThat(failure(500).refusal()).isFalse();
        assertThat(failure(503).refusal()).isFalse();
        assertThat(failure(429).refusal()).as("rate limited").isFalse();
        assertThat(failure(409).refusal()).as("idempotent request in flight").isFalse();
        assertThat(failure(401).refusal()).as("bad API key").isFalse();
        assertThat(failure(403).refusal()).as("missing permission").isFalse();
    }
}
