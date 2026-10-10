package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultFcAssignmentPolicyTest {

    private final FcAssignmentPolicy policy = new DefaultFcAssignmentPolicy();

    private static FcCandidate fc(String id, List<String> foreign, int own, long held, String... zones) {
        return new FcCandidate(id, List.of(zones), foreign, own, held);
    }

    @Test
    void neverMixesSellersAndPrefersWhereTheSellerAlreadyStocks() {
        List<FcCandidate> fcs = List.of(
                fc("ATL-FC", List.of(), 0, 10, "Ambient"),
                fc("EWR-FC", List.of(), 2, 900, "Ambient", "Chilled"),
                fc("HOU-FC", List.of("SKU-1"), 2, 5, "Ambient", "Chilled"));
        FcAssignment a = policy.assign(fcs, null, null);
        assertThat(a.nodeId()).isEqualTo("EWR-FC");
        assertThat(a.reason()).contains("Already holds 2");
    }

    @Test
    void otherwiseTheLeastLoadedThenByIdAndHonoursTheRequiredZone() {
        List<FcCandidate> fcs = List.of(
                fc("LAX-FC", List.of(), 0, 50, "Ambient", "Chilled"),
                fc("ATL-FC", List.of(), 0, 10, "Ambient"),
                fc("EWR-FC", List.of(), 0, 10, "Ambient"));
        assertThat(policy.assign(fcs, null, null).nodeId()).isEqualTo("ATL-FC");
        assertThat(policy.assign(fcs, null, "chilled").nodeId()).isEqualTo("LAX-FC");
    }

    @Test
    void aRequestedFcMustQualify() {
        List<FcCandidate> fcs = List.of(
                fc("HOU-FC", List.of("SKU-1"), 0, 5, "Ambient"),
                fc("ATL-FC", List.of(), 0, 10, "Ambient"));
        assertThat(policy.assign(fcs, "ATL-FC", null).reason()).isEqualTo("Requested by the seller");
        assertThatThrownBy(() -> policy.assign(fcs, "HOU-FC", null)).isInstanceOf(InventoryRuleViolation.class)
                .hasMessageContaining("another seller");
        assertThatThrownBy(() -> policy.assign(fcs, "XXX-FC", null)).isInstanceOf(InventoryRuleViolation.class);
        assertThatThrownBy(() -> policy.assign(List.of(fcs.getFirst()), null, null))
                .isInstanceOf(InventoryRuleViolation.class);
    }

    @Test
    void inboundShipmentLifecycleReportsDiscrepancies() {
        Instant t = Instant.parse("2026-10-10T12:00:00Z");
        InboundShipment plan = new InboundShipment(UUID.randomUUID(), "FBA-IN-006600", InboundSource.FBA, null,
                "org-1", "HOU-FC", null, "Least loaded", "October restock", InboundStatus.WORKING, null, null, null,
                null, null, null, null, null, null, 0,
                List.of(new InboundLine(1, "SKU-A", "AT00000001", "ATF0000001", 10, 0, 0, 0),
                        new InboundLine(2, "SKU-B", "AT00000002", "ATF0000002", 5, 0, 0, 0)),
                List.of(), "seller", t, null, null, t, 0);
        assertThatThrownBy(() -> plan.receive("SKU-A", null, 1, 0, 1, t))
                .isInstanceOf(InventoryStateConflict.class);
        InboundShipment shipped = plan.ship(t);
        assertThat(shipped.lines()).extracting(InboundLine::inboundOpen).containsExactly(10, 5);
        InboundShipment received = shipped.receive("SKU-A", null, 11, 1, 10, t)
                .receive("SKU-C", "AT00000003", 2, 0, 0, t);
        assertThat(received.status()).isEqualTo(InboundStatus.RECEIVING);
        assertThat(received.discrepancies()).extracting(InboundDiscrepancy::type).containsExactly(
                InboundDiscrepancy.Type.DAMAGED, InboundDiscrepancy.Type.OVER, InboundDiscrepancy.Type.UNEXPECTED);
        InboundShipment closed = received.close(t);
        assertThat(closed.lines()).allMatch(l -> l.inboundOpen() == 0);
        assertThat(closed.discrepancies()).extracting(InboundDiscrepancy::type).contains(
                InboundDiscrepancy.Type.SHORT);
        assertThat(closed.discrepancies().stream().filter(d -> d.type() == InboundDiscrepancy.Type.SHORT)
                .findFirst().orElseThrow().qty()).isEqualTo(5);
        assertThatThrownBy(() -> closed.cancel(t)).isInstanceOf(InventoryStateConflict.class);
        assertThat(AgeBucket.of(0)).isEqualTo(AgeBucket.D0_90);
        assertThat(AgeBucket.of(181)).isEqualTo(AgeBucket.D181_270);
        assertThat(AgeBucket.of(400)).isEqualTo(AgeBucket.D365_PLUS);
        assertThat(ExpiryBucket.of(null)).isEqualTo(ExpiryBucket.NO_EXPIRY);
        assertThat(ExpiryBucket.of(0L)).isEqualTo(ExpiryBucket.EXPIRED);
        assertThat(ExpiryBucket.of(45L)).isEqualTo(ExpiryBucket.D31_60);
    }
}
