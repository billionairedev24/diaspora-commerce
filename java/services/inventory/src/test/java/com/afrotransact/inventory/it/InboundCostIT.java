package com.afrotransact.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;


import com.afrotransact.inventory.application.InboundCommands;
import com.afrotransact.inventory.application.InboundShipmentService;
import com.afrotransact.inventory.domain.CostComponents;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.RateSource;
import com.afrotransact.platform.security.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Counting a shipment at the dock: AfroTransact-owned stock (a retail ASN) can be costed in the currency paid at the
 * receiver's rate; seller FBA units carry no cost, in any currency.
 */
class InboundCostIT extends InventoryIntegrationTest {

    private static final Actor RECEIVER = TestActors.staff("inventory:receive", "inventory:read");

    private InboundShipmentService inbound;

    @BeforeEach
    void wire(@Autowired InboundShipmentService inboundService) {
        this.inbound = inboundService;
    }

    private UUID retailAsn(String sku) {
        String reference = "SHP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        inbound.retailShipmentChanged(UUID.randomUUID(), new InboundCommands.RetailShipmentNotice(
                UUID.randomUUID().toString(), reference, "DEPARTED", NODE, today().plusDays(10),
                List.of(new InboundCommands.NoticeLine(sku, 100))));
        return jdbc.sql("select id from inbound_shipment where external_ref = :r").param("r", reference)
                .query(UUID.class).single();
    }

    @Test
    void aRetailAsnReceivedAtTheDockCarriesTheCostInThePesosPaid() {
        String sku = sku("DOCK-MX");
        UUID shipment = retailAsn(sku);
        TestActors.as(RECEIVER);

        InboundShipmentService.ReceiptOutcome outcome = inbound.receive(shipment, new InboundCommands.ReceivePlan(List.of(
                new InboundCommands.ReceiveLine(sku, null, null, 60, 2, "L-DOCK-1", today().minusDays(20),
                        today().plusDays(500), "A-02-01", null, new InboundCommands.ReceiveCost(12_000, "MXN",
                        new BigDecimal("0.0577"), null, new CostComponents(10_000, 1_500, 400, 100)))),
                List.of()), UUID.randomUUID().toString());

        assertThat(outcome.receipt().lots()).extracting(Lot::lotNo).containsExactlyInAnyOrder("L-DOCK-1",
                "L-DOCK-1-DMG");
        assertThat(outcome.receipt().lots()).allSatisfy(l -> {
            assertThat(l.currency()).isEqualTo("MXN");
            assertThat(l.cost().rateSource()).isEqualTo(RateSource.ENTERED);
            assertThat(l.unitCostUsdMinor()).isEqualTo(692);
            assertThat(l.cost().components().freightMinor()).isEqualTo(1_500);
        });
    }

    @Test
    void withoutACostTheUnitsStayAtZeroDollars() {
        String sku = sku("DOCK-ZERO");
        UUID shipment = retailAsn(sku);
        TestActors.as(RECEIVER);

        InboundShipmentService.ReceiptOutcome outcome = inbound.receive(shipment, new InboundCommands.ReceivePlan(List.of(
                new InboundCommands.ReceiveLine(sku, null, null, 10, 0, "L-DOCK-0", null, null, null, null)),
                List.of()), UUID.randomUUID().toString());

        Lot lot = outcome.receipt().lots().getFirst();
        assertThat(lot.currency()).isEqualTo("USD");
        assertThat(lot.unitCostUsdMinor()).isZero();
    }

    @Test
    void sellerUnitsCarryNoCostOnAfroTransactsBooks() {
        String sku = sku("DOCK-FBA");
        UUID shipment = UUID.randomUUID();
        jdbc.sql("""
                insert into inbound_shipment (id, number, source, owner_org, node_id, status, created_by, created_at,
                                              updated_at)
                values (:id, :number, 'FBA', 'org_fba_seller', :node, 'SHIPPED', 'seller', now(), now())
                """).param("id", shipment).param("number", "FBA-IN-" + UUID.randomUUID().toString().substring(0, 8))
                .param("node", NODE).update();
        jdbc.sql("insert into inbound_shipment_line (shipment_id, line_no, sku, expected_qty) values (:id, 1, :sku, 10)")
                .param("id", shipment).param("sku", sku).update();
        TestActors.as(RECEIVER);

        assertThatThrownBy(() -> inbound.receive(shipment, new InboundCommands.ReceivePlan(List.of(
                new InboundCommands.ReceiveLine(sku, null, null, 10, 0, "L-FBA-1", null, null, null, null,
                        new InboundCommands.ReceiveCost(500, "MXN", new BigDecimal("0.0577"), null, null))),
                List.of()), UUID.randomUUID().toString())).isInstanceOfSatisfying(InventoryRuleViolation.class,
                ex -> assertThat(ex.code()).isEqualTo("inventory.fba-cost-not-allowed"));
        assertThat(jdbc.sql("select count(*) from lot where sku = :sku").param("sku", sku).query(Long.class).single())
                .isZero();
    }
}
