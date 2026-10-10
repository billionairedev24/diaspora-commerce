package com.afrotransact.retail.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InboundShipmentTest {

    private final UUID itemId = UUID.randomUUID();
    private final PurchaseOrder po = Fixtures.sent(Fixtures.po(Fixtures.approvedAccra(),
            List.of(PoLine.of(1, itemId, "AT-JOL-6", "Jollof", "ACC-J", 2000, 2005))));

    private InboundShipment booked() {
        return InboundShipment.book(UUID.randomUUID(), "SHP-2610-GH01", po, TransportMode.OCEAN_FCL, "MSC",
                "MSCU 482913 7", "MEDUTM2610441", "Tema, Ghana", "Port of Houston", Fixtures.TODAY,
                Fixtures.TODAY.plusDays(32), List.of(new AsnLine(1, 1, itemId, "AT-JOL-6", 2000, null, null, null)),
                List.of(), "ama", Fixtures.NOW);
    }

    private static InboundShipment advance(InboundShipment s, int times) {
        InboundShipment out = s;
        for (int i = 0; i < times; i++) {
            out = out.advance("ops", Fixtures.NOW);
        }
        return out;
    }

    private static InboundShipment allDocs(InboundShipment s, DocumentStatus status) {
        InboundShipment out = s;
        for (ShipmentDocument d : s.documents()) {
            out = out.setDocument(d.name(), status, null, null, Fixtures.NOW);
        }
        return out;
    }

    @Test
    void bookingCreatesTheModesDocumentsAsMissing() {
        InboundShipment s = booked();
        assertThat(s.stage()).isEqualTo(ShipmentStage.BOOKED);
        assertThat(s.documents()).extracting(ShipmentDocument::name).contains("Bill of lading", "ISF 10+2 filed");
        assertThat(s.documents()).allMatch(d -> d.status() == DocumentStatus.MISSING);
        assertThat(TransportMode.AIR.requiredDocuments()).contains("Air waybill").doesNotContain("Bill of lading");
    }

    @Test
    void departureIsBlockedWhileDocumentsAreMissing() {
        InboundShipment exportCleared = advance(booked(), 3);
        assertThat(exportCleared.stage()).isEqualTo(ShipmentStage.EXPORT_CLEARED);
        assertThatThrownBy(() -> exportCleared.advance("ops", Fixtures.NOW)).isInstanceOf(RetailRuleViolation.class)
                .extracting("code").isEqualTo("retail.documents-missing");
        InboundShipment departed = allDocs(exportCleared, DocumentStatus.PENDING).advance("ops", Fixtures.NOW);
        assertThat(departed.stage()).isEqualTo(ShipmentStage.DEPARTED);
        assertThat(departed.asnLocked()).isTrue();
    }

    @Test
    void asnLinesAreEditableUntilDepartureThenLocked() {
        InboundShipment s = booked();
        List<AsnLine> batches = List.of(new AsnLine(1, 1, itemId, "AT-JOL-6", 1200, "L2608-JOL-03",
                Fixtures.TODAY.minusDays(50), Fixtures.TODAY.plusDays(500)),
                new AsnLine(2, 1, itemId, "AT-JOL-6", 800, "L2608-JOL-04", Fixtures.TODAY.minusDays(40),
                        Fixtures.TODAY.plusDays(510)));
        InboundShipment edited = s.updateAsn(po, batches, List.of(new Carton("000860010000000010", 1, 1200)),
                Fixtures.NOW);
        assertThat(edited.lines()).hasSize(2);
        InboundShipment departed = allDocs(advance(edited, 3), DocumentStatus.OK).advance("ops", Fixtures.NOW);
        assertThatThrownBy(() -> departed.updateAsn(po, batches, List.of(), Fixtures.NOW))
                .isInstanceOf(RetailStateConflict.class).extracting("code").isEqualTo("retail.asn-locked");
    }

    @Test
    void customsClearanceIsBlockedByHolds() {
        InboundShipment arrived = advance(allDocs(advance(booked(), 3), DocumentStatus.OK), 2);
        assertThat(arrived.stage()).isEqualTo(ShipmentStage.ARRIVED_PORT);
        InboundShipment held = arrived.setDocument("CBP entry (7501)", DocumentStatus.HOLD, null,
                "FDA exam — aflatoxin screen", Fixtures.NOW);
        assertThat(held.note()).contains("on hold");
        assertThatThrownBy(() -> held.advance("ops", Fixtures.NOW)).isInstanceOf(RetailRuleViolation.class)
                .extracting("code").isEqualTo("retail.customs-hold");
        InboundShipment released = held.setDocument("CBP entry (7501)", DocumentStatus.OK, "7501-1", null,
                Fixtures.NOW);
        assertThat(released.note()).isEqualTo("Hold released");
        InboundShipment cleared = released.advance("ops", Fixtures.NOW);
        assertThat(cleared.stage()).isEqualTo(ShipmentStage.CUSTOMS_CLEARED);
        InboundShipment atFc = cleared.advance("ops", Fixtures.NOW);
        assertThat(atFc.stage()).isEqualTo(ShipmentStage.DELIVERED_TO_FC);
        assertThatThrownBy(() -> atFc.advance("ops", Fixtures.NOW)).isInstanceOf(RetailStateConflict.class);
        assertThat(atFc.markReceived("dan", Fixtures.NOW).stage()).isEqualTo(ShipmentStage.RECEIVED);
        assertThat(atFc.milestones()).hasSize(8);
    }

    @Test
    void cartonsMustAddUpAndSsccMustBeValid() {
        InboundShipment s = booked();
        List<AsnLine> lines = s.lines();
        assertThatThrownBy(() -> s.updateAsn(po, lines, List.of(new Carton("000860010000000010", 1, 10)),
                Fixtures.NOW)).isInstanceOf(RetailRuleViolation.class).extracting("code")
                .isEqualTo("retail.asn-carton-mismatch");
        assertThatThrownBy(() -> new Carton("000860010000000018", 1, 10)).isInstanceOf(RetailRuleViolation.class);
    }

    @Test
    void asnCannotShipMoreThanTwoPercentOverTheOrder() {
        assertThatThrownBy(() -> booked().updateAsn(po,
                List.of(new AsnLine(1, 1, itemId, "AT-JOL-6", 2041, null, null, null)), List.of(), Fixtures.NOW))
                .isInstanceOf(RetailRuleViolation.class).extracting("code").isEqualTo("retail.asn-over-shipped");
        assertThat(booked().updateAsn(po, List.of(new AsnLine(1, 1, itemId, "AT-JOL-6", 2040, null, null, null)),
                List.of(), Fixtures.NOW).lines().getFirst().qty()).isEqualTo(2040);
    }

    @Test
    void asnLotsFollowTheLotFormat() {
        assertThatThrownBy(() -> new AsnLine(1, 1, itemId, "AT-JOL-6", 5, "L1", null, null))
                .isInstanceOf(RetailRuleViolation.class);
        assertThat(new AsnLine(1, 1, itemId, "AT-JOL-6", 5, " l2608-jol-03 ", null, null).lotNo())
                .isEqualTo("L2608-JOL-03");
    }
}
