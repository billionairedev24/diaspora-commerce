package com.afrotransact.deliveryruns.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.OrderPlaced;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OrderEventsListenerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void readsPreorderPackagesAndAttribution() {
        UUID window = UUID.randomUUID();
        String body = """
                {"orderId": "8d1f0f6e-1111-4c3a-9a55-000000000001", "orderNumber": "AT-2026-7KQ3MD",
                 "customerId": "user-1", "currency": "usd", "placedAt": "2026-10-07T16:30:00Z",
                 "shipToName": "Ada Okafor", "shipToCity": "Lubbock", "shipToState": "TX", "shipToPostalCode": "79401",
                 "shipToLine1": "1902 19th St", "shipToPhone": "+1 806 555 0101",
                 "attribution": {"src": "ig", "cr": "story", "po": "LUB-7"},
                 "packages": [
                   {"shipmentId": "s-1", "sellerOrgId": "seller-1", "fulfilmentType": "PREORDER",
                    "windowId": "%s", "fulfilment": "PICKUP", "totalMinor": 2000, "shippingMinor": 0,
                    "lines": [{"variantAtin": "EGU", "title": "Egusi", "quantity": 2}]},
                   {"shipmentId": "s-2", "sellerOrgId": "seller-2", "fulfilmentType": "SELLER_FULFILLED",
                    "deliveryMethod": "CARRIER", "totalMinor": 1500, "lines": []}
                 ]}
                """.formatted(window);
        Optional<OrderPlaced> e = OrderEventsListener.placed(UUID.randomUUID(), json.readTree(body));
        assertThat(e).isPresent();
        OrderPlaced p = e.get();
        assertThat(p.currency()).isEqualTo("USD");
        assertThat(p.src()).isEqualTo("ig");
        assertThat(p.creative()).isEqualTo("story");
        assertThat(p.windowCode()).isEqualTo("LUB-7");
        assertThat(p.phone()).isEqualTo("+1 806 555 0101");
        assertThat(p.packages()).hasSize(1);
        OrderPlaced.Package pkg = p.packages().get(0);
        assertThat(pkg.windowId()).isEqualTo(window);
        assertThat(pkg.fulfilment()).isEqualTo(PreorderFulfilment.PICKUP);
        assertThat(pkg.lines()).singleElement().satisfies(l -> {
            assertThat(l.atin()).isEqualTo("EGU");
            assertThat(l.qty()).isEqualTo(2);
            assertThat(l.sellerOrgId()).isEqualTo("seller-1");
        });
    }

    @Test
    void ordersWithoutPreorderPackagesAreIgnored() {
        String body = """
                {"orderId": "o-1", "placedAt": "2026-10-07T16:30:00Z",
                 "shipments": [{"shipmentId": "s-1", "fulfilmentType": "SELLER_FULFILLED", "lines": []}]}
                """;
        assertThat(OrderEventsListener.placed(UUID.randomUUID(), json.readTree(body))).isEmpty();
    }
}
