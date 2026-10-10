package com.afrotransact.taxreturns.domain.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RmaTest {

    private static final Instant T0 = Instant.parse("2026-10-05T10:00:00Z");
    private static final RmaLine LINE = new RmaLine("l1", "AT1", "Dress", 1, 6_499, ReturnReason.DAMAGED, null,
            List.of("m1"), null, null, StockStatus.NOT_TRACKED, null);
    private static final ReturnLabel LABEL = new ReturnLabel("lbl", "ups", "1Z999", null);

    private static Rma requested(ReturnMethod method) {
        return Rma.requested(UUID.randomUUID(), "RMA-TEST0001", UUID.randomUUID(), "AT-2026-000001",
                UUID.randomUUID(), "cust", "org_a", FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER, method,
                List.of(LINE), "USD", 7_035, null, false, null, T0);
    }

    @Test
    void dropOffHappyPath() {
        Rma r = requested(ReturnMethod.DROP_OFF);
        assertThat(r.status()).isEqualTo(RmaStatus.REQUESTED);
        r = r.approve(true, null, T0);
        assertThat(r.status()).isEqualTo(RmaStatus.AUTO_APPROVED);
        assertThat(r.autoApproved()).isTrue();
        r = r.labelIssued(LABEL, "QR", T0.plusSeconds(86_400 * 14), T0);
        assertThat(r.status()).isEqualTo(RmaStatus.LABEL_ISSUED);
        assertThat(r.status().cancellable()).isTrue();
        r = r.inTransit(T0);
        assertThat(r.status().cancellable()).isFalse();
        r = r.received(T0);
        r = r.inspected(List.of(LINE.inspected(InspectionOutcome.PASS, ItemCondition.RESTOCK, StockStatus.PENDING,
                null)), "ok", T0);
        assertThat(r.refundableLines()).hasSize(1);
        r = r.withRefundState(RefundState.ISSUED, T0).refunded(T0);
        assertThat(r.status()).isEqualTo(RmaStatus.REFUNDED);
        assertThat(r.refundState()).isEqualTo(RefundState.ISSUED);
        assertThat(r.status().next()).isEmpty();
    }

    @Test
    void refundWithoutReturnGoesFromApprovedToRefunded() {
        Rma r = requested(ReturnMethod.NO_RETURN).approve(false, "ok", T0).refunded(T0);
        assertThat(r.status()).isEqualTo(RmaStatus.REFUNDED);
        assertThat(r.decisionNote()).isEqualTo("ok");
    }

    @Test
    void driverPickupGetsAHandoverCodeNotALabel() {
        Rma approved = requested(ReturnMethod.DRIVER_PICKUP).approve(true, null, T0);
        assertThatThrownBy(() -> approved.labelIssued(LABEL, "QR", T0, T0)).isInstanceOf(IllegalStateException.class);
        Rma r = approved.pickupArranged("123456", "QR", T0, T0);
        assertThat(r.status()).isEqualTo(RmaStatus.LABEL_ISSUED);
        assertThat(r.handoverCode()).isEqualTo("123456");
        assertThatThrownBy(() -> requested(ReturnMethod.DROP_OFF).approve(true, null, T0)
                .pickupArranged("1", "QR", T0, T0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelOnlyBeforeHandover() {
        assertThat(requested(ReturnMethod.DROP_OFF).cancel(T0).status()).isEqualTo(RmaStatus.CANCELLED);
        Rma inTransit = requested(ReturnMethod.DROP_OFF).approve(true, null, T0)
                .labelIssued(LABEL, "QR", T0, T0).inTransit(T0);
        assertThatThrownBy(() -> inTransit.cancel(T0)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in_transit");
    }

    @Test
    void rejectionAndFailedInspection() {
        assertThat(requested(ReturnMethod.DROP_OFF).reject("used", T0).status()).isEqualTo(RmaStatus.REJECTED);
        Rma inspected = requested(ReturnMethod.DROP_OFF).approve(true, null, T0).labelIssued(LABEL, "QR", T0, T0)
                .received(T0).inspected(List.of(LINE.inspected(InspectionOutcome.FAIL, ItemCondition.DAMAGED,
                        StockStatus.PENDING, "worn")), null, T0);
        assertThat(inspected.refundableLines()).isEmpty();
        assertThat(inspected.reject("Inspection failed", T0).status()).isEqualTo(RmaStatus.REJECTED);
    }

    @Test
    void cannotSkipSteps() {
        Rma r = requested(ReturnMethod.DROP_OFF);
        assertThatThrownBy(() -> r.inTransit(T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.received(T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.refunded(T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.approve(true, null, T0).approve(false, null, T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = RmaStatus.class, names = {"REFUNDED", "REJECTED", "CANCELLED"})
    void terminalStatusesGoNowhere(RmaStatus s) {
        assertThat(s.next()).isEmpty();
        assertThat(s.cancellable()).isFalse();
    }

    @Test
    void transitionTable() {
        assertThat(RmaStatus.REQUESTED.next()).isEqualTo(EnumSet.of(RmaStatus.APPROVED, RmaStatus.AUTO_APPROVED,
                RmaStatus.REJECTED, RmaStatus.CANCELLED));
        assertThat(RmaStatus.LABEL_ISSUED.next()).isEqualTo(EnumSet.of(RmaStatus.IN_TRANSIT, RmaStatus.RECEIVED,
                RmaStatus.CANCELLED));
        assertThat(RmaStatus.IN_TRANSIT.next()).containsExactly(RmaStatus.RECEIVED);
        assertThat(RmaStatus.RECEIVED.next()).containsExactly(RmaStatus.INSPECTED);
        assertThat(RmaStatus.INSPECTED.next()).isEqualTo(EnumSet.of(RmaStatus.REFUNDED, RmaStatus.REJECTED));
        assertThat(RmaStatus.REJECTED.open()).isFalse();
        assertThat(RmaStatus.IN_TRANSIT.open()).isTrue();
    }

    @Test
    void reasonsDecideFaultAndEvidence() {
        assertThat(ReturnReason.DAMAGED.sellerFault()).isTrue();
        assertThat(ReturnReason.DAMAGED.evidenceRequired()).isTrue();
        assertThat(ReturnReason.NOT_AS_DESCRIBED.evidenceRequired()).isFalse();
        assertThat(ReturnReason.NO_LONGER_NEEDED.sellerFault()).isFalse();
        assertThat(FulfilmentType.SELLER_FULFILLED.afroTransactFulfilled()).isFalse();
        assertThat(FulfilmentType.RETAIL_1P.afroTransactFulfilled()).isTrue();
    }
}
