package com.afrotransact.trustops.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class DefaultSellerHealthPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-10T03:15:00Z");
    private final SellerHealthPolicy policy = DefaultSellerHealthPolicy.defaults();

    private static SellerHealthMetrics metrics(int shipments, int delivered, int late, int cancelled, int claims) {
        return new SellerHealthMetrics("org_1", NOW.minusSeconds(86_400 * 90), NOW, shipments, delivered, late,
                cancelled, claims);
    }

    @Test
    void ratesFollowTheMarketplaceDefinitions() {
        SellerHealthMetrics m = metrics(200, 150, 3, 2, 1);
        assertThat(m.lateShipmentRate()).isCloseTo(0.02, within(1e-9));
        assertThat(m.onTimeRate()).isCloseTo(0.98, within(1e-9));
        assertThat(m.cancellationRate()).isCloseTo(0.01, within(1e-9));
        assertThat(m.defectRate()).isCloseTo(0.005, within(1e-9));
        assertThat(policy.assess(m, NOW).status()).isEqualTo(SellerHealthStatus.GOOD);
    }

    @Test
    void anyBreachedTargetMakesTheSellerAtRisk() {
        SellerHealthReport r = policy.assess(metrics(100, 100, 5, 3, 2), NOW);
        assertThat(r.status()).isEqualTo(SellerHealthStatus.AT_RISK);
        assertThat(r.breaches()).hasSize(3).anySatisfy(b -> assertThat(b).startsWith("LATE_SHIPMENT_RATE 5.00%"));
    }

    @Test
    void noShipmentsMeansUnknownWithNullRates() {
        SellerHealthReport r = policy.assess(metrics(0, 0, 0, 0, 0), NOW);
        assertThat(r.status()).isEqualTo(SellerHealthStatus.UNKNOWN);
        assertThat(r.metrics().onTimeRate()).isNull();
        assertThat(r.metrics().defectRate()).isNull();
    }
}
