package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CapacityTest {

    @Test
    void ordersLimitFillsUp() {
        Run run = Runs.withOrders(Runs.open(1, new Capacity(2, null), CutoffPolicy.CANCEL), 2);
        assertThat(run.reservedOrders()).isEqualTo(2);
        assertThat(run.isFull(1)).isTrue();
        assertThat(run.canTake(1, Runs.NOW)).isFalse();
        assertThat(run.terms().capacity().ordersLeft(run.reservedOrders())).isZero();
        assertThatThrownBy(() -> run.reserve(1, Runs.NOW)).isInstanceOf(RuleViolation.class)
                .extracting("code").isEqualTo("delivery-runs.run-full");
    }

    @Test
    void packagesLimitCountsPackagesNotOrders() {
        Run run = Runs.open(1, new Capacity(null, 5), CutoffPolicy.CANCEL).reserve(4, Runs.NOW);
        assertThat(run.canTake(1, Runs.NOW)).isTrue();
        assertThat(run.canTake(2, Runs.NOW)).isFalse();
        assertThat(run.terms().capacity().ordersLeft(run.reservedOrders())).isNull();
    }

    @Test
    void bothLimitsApply() {
        Capacity c = new Capacity(3, 6);
        assertThat(c.fits(2, 5, 1)).isTrue();
        assertThat(c.fits(3, 0, 1)).isFalse();
        assertThat(c.fits(0, 5, 2)).isFalse();
        assertThat(Capacity.UNLIMITED.fits(10_000, 10_000, 50)).isTrue();
    }

    @Test
    void releaseFreesTheSlot() {
        Run full = Runs.withOrders(Runs.open(1, new Capacity(1, null), CutoffPolicy.CANCEL), 1);
        Run released = full.release(1, Runs.NOW);
        assertThat(released.reservedOrders()).isZero();
        assertThat(released.canTake(1, Runs.NOW)).isTrue();
    }

    @Test
    void closedRunsTakeNoOrders() {
        Run run = Runs.open(1, Capacity.UNLIMITED, CutoffPolicy.CANCEL);
        assertThat(run.canTake(1, Runs.CUTOFF)).isFalse();
        assertThatThrownBy(() -> run.reserve(1, Runs.CUTOFF)).isInstanceOf(RuleViolation.class)
                .extracting("code").isEqualTo("delivery-runs.run-closed");
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> new Capacity(0, null)).isInstanceOf(RuleViolation.class);
    }
}
