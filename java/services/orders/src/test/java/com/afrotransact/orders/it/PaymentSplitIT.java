package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.UUID;

import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.Order;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** S259: the order's payment split carries each recipient's AfroTransact Delivery pickup fees from payments. */
class PaymentSplitIT extends OrdersIntegrationTest {

    @Test
    void theSplitExposesDeliveryFeesPerRecipientAndSellersSeeOnlyTheirRow() {
        String orgC = "org_" + id("c").toLowerCase(Locale.ROOT).replace("-", "");
        Order order = placedOrder(id("cust"), UUID.randomUUID().toString(),
                spec(orgC, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY),
                spec("org_a", FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER));
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/orders/" + order.id() + "/split")), 200, """
                {"orderId":"%s","chargeMinor":6500,"stripeFeeMinor":219,"taxCollectedMinor":372,"currency":"USD",
                 "recipients":[
                  {"recipient":"afrotransact","kind":"PLATFORM","itemsMinor":0,"shippingMinor":1128,"taxMinor":0,
                   "commissionMinor":360,"fbaFeesMinor":0,"deliveryFeesMinor":200,"stripeFeeMinor":38,"netMinor":1650},
                  {"recipient":"%s","kind":"SELLER","itemsMinor":3000,"shippingMinor":0,"taxMinor":248,
                   "commissionMinor":240,"fbaFeesMinor":0,"deliveryFeesMinor":200,"stripeFeeMinor":109,"netMinor":2451},
                  {"recipient":"org_a","kind":"SELLER","itemsMinor":1500,"shippingMinor":500,"taxMinor":124,
                   "commissionMinor":120,"fbaFeesMinor":0,"deliveryFeesMinor":0,"stripeFeeMinor":72,"netMinor":1808}]}
                """.formatted(order.id(), orgC));

        JsonNode staff = ok(send("GET", "/v1/orders/" + order.number() + "/payment-split", null));
        assertThat(staff.path("recipients")).hasSize(3);
        assertThat(staff.path("recipients").get(0).path("deliveryFeesMinor").asLong()).isEqualTo(200);

        TestSupport.as(TestSupport.seller(orgC));
        JsonNode seller = ok(send("GET", "/v1/orders/" + order.number() + "/payment-split", null));
        assertThat(seller.path("recipients")).hasSize(1);
        assertThat(seller.path("recipients").get(0).path("recipient").asString()).isEqualTo(orgC);
        assertThat(seller.path("recipients").get(0).path("deliveryFeesMinor").asLong()).isEqualTo(200);
        assertThat(seller.path("recipients").get(0).path("netMinor").asLong()).isEqualTo(2451);
    }
}
