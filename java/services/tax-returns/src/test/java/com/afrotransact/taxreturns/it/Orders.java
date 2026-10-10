package com.afrotransact.taxreturns.it;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.application.OrderPlaced;
import com.afrotransact.taxreturns.port.PaymentsGateway;

/**
 * A two-shipment order shipped to Houston, TX (77019, 8.25 %):
 * S1 fulfilled by AfroTransact (carrier) from org_a — 2 × Ankara dress 64.99 (tax 10.72) and a perishable jollof
 * seasoning 18.50 (grocery, no tax), shipping 7.99; S2 seller-fulfilled by org_b, delivered by AfroTransact Delivery —
 * a mudcloth pillow cover 32.00 (tax 2.64), no shipping.
 */
final class Orders {

    final UUID orderId = UUID.randomUUID();
    final String number = "AT-2026-" + orderId.toString().substring(0, 6).toUpperCase();
    final UUID s1 = UUID.randomUUID();
    final UUID s2 = UUID.randomUUID();
    final String dress = UUID.randomUUID().toString();
    final String jollof = UUID.randomUUID().toString();
    final String pillow = UUID.randomUUID().toString();
    final String customer;

    Orders(String customer) {
        this.customer = customer;
    }

    OrderPlaced placed(Instant at) {
        return new OrderPlaced(orderId, number, customer, "USD", at, "Houston", "TX", "77019", "US", List.of(
                new OrderPlaced.Shipment(s1, "org_a", "Lagos Textile Hub", "FBA_AFROTRANSACT", "CARRIER",
                        12_998 + 1_072 + 1_850 + 799, List.of(
                        new OrderPlaced.Line(dress, "ATDRESS001", "Ankara wrap dress", 2, 6_499, 1_072),
                        new OrderPlaced.Line(jollof, "ATJOLLOF01", "Jollof seasoning 8 oz", 1, 1_850, 0))),
                new OrderPlaced.Shipment(s2, "org_b", "Bamako Home", "SELLER_FULFILLED", "AFROTRANSACT_DELIVERY",
                        3_200 + 264, List.of(
                        new OrderPlaced.Line(pillow, "ATPILLOW01", "Bogolan mudcloth pillow cover", 1, 3_200,
                                264)))), "Adaeze Nwosu", "12 Bayou Ln", null, null);
    }

    PaymentsGateway.ChargedPayment payment() {
        List<PaymentsGateway.ChargedLine> lines = new ArrayList<>();
        lines.add(new PaymentsGateway.ChargedLine(dress, "org_a", 2, 12_998, 799, 1_072, true));
        lines.add(new PaymentsGateway.ChargedLine(jollof, "org_a", 1, 1_850, 0, 0, true));
        lines.add(new PaymentsGateway.ChargedLine(pillow, "org_b", 1, 3_200, 0, 264, true));
        return new PaymentsGateway.ChargedPayment(UUID.randomUUID(), "USD", lines);
    }
}
