package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** S324 the verified-purchase check catalog-service makes before it accepts a product review. */
class PurchaseCheckIT extends OrdersIntegrationTest {

    private static final Actor CATALOG = new Actor("catalog-service", PrincipalType.SERVICE, Optional.empty(),
            Set.of(), Set.of("orders:purchase-check"));

    private void deliver(Order order, String atin) {
        UUID shipment = UUID.fromString(order.shipments().getFirst().id());
        jdbc.sql("update order_line set product_atin = :atin where shipment_id = :s").param("atin", atin)
                .param("s", shipment).update();
        jdbc.sql("update shipment set status = 'DELIVERED', delivered_at = now() where id = :s").param("s", shipment)
                .update();
    }

    private JsonNode check(String customer, String atin) {
        return ok(send("GET", "/v1/lookup/customers/" + customer + "/purchases/" + atin, null));
    }

    @Test
    void onlyADeliveredLineOfTheSameCustomerAndProductCounts() {
        String customer = id("cust");
        String atin = id("ATIN");
        Order order = placedOrder(customer, UUID.randomUUID().toString(),
                spec(id("org-a"), FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER));
        jdbc.sql("update order_line set product_atin = :atin where shipment_id = :s").param("atin", atin)
                .param("s", UUID.fromString(order.shipments().getFirst().id())).update();

        TestSupport.as(CATALOG);
        assertThat(check(customer, atin).path("delivered").asBoolean()).as("placed but not delivered").isFalse();

        deliver(order, atin);
        JsonNode delivered = check(customer, atin);
        assertThat(delivered.path("delivered").asBoolean()).isTrue();
        assertThat(delivered.path("orderNumber").asString()).isEqualTo(order.number());
        assertThat(delivered.path("orderId").asString()).isEqualTo(order.id());
        assertThat(delivered.path("deliveredAt").asString()).isNotBlank();
        assertThat(check(customer, atin.toLowerCase()).path("delivered").asBoolean()).as("ATIN case").isTrue();

        assertThat(check(id("other-cust"), atin).path("delivered").asBoolean()).as("another customer").isFalse();
        assertThat(check(customer, id("ATIN")).path("delivered").asBoolean()).as("another product").isFalse();
    }

    @Test
    void aReturnedShipmentIsNotAPurchaseAnymore() {
        String customer = id("cust");
        String atin = id("ATIN");
        Order order = placedOrder(customer, UUID.randomUUID().toString(),
                spec(id("org-a"), FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER));
        deliver(order, atin);
        jdbc.sql("update shipment set status = 'RETURNED' where id = :s")
                .param("s", UUID.fromString(order.shipments().getFirst().id())).update();

        TestSupport.as(CATALOG);
        assertThat(check(customer, atin).path("delivered").asBoolean()).isFalse();
    }

    @Test
    void shoppersAndSellersCannotAskButStaffAndLookupServicesCan() {
        String customer = id("cust");
        TestSupport.as(TestSupport.customer(customer));
        problem(send("GET", "/v1/lookup/customers/" + customer + "/purchases/ATIN-1", null), 403,
                "orders.lookup-forbidden");
        TestSupport.as(TestSupport.seller(id("org")));
        problem(send("GET", "/v1/lookup/customers/" + customer + "/purchases/ATIN-1", null), 403,
                "orders.lookup-forbidden");

        TestSupport.as(new Actor("svc-tax-returns", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of("orders:lookup")));
        assertThat(check(customer, "ATIN-1").path("delivered").asBoolean()).isFalse();
        TestSupport.as(TestSupport.admin());
        assertThat(check(customer, "ATIN-1").path("delivered").asBoolean()).isFalse();
    }
}
