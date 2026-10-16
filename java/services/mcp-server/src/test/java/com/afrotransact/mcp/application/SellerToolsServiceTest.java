package com.afrotransact.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.mcp.domain.CallerProfile;
import com.afrotransact.mcp.domain.DefaultShipmentStatusPolicy;
import com.afrotransact.mcp.domain.ToolDescriptor;
import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.InventoryApi;
import com.afrotransact.mcp.port.OrdersApi;
import org.junit.jupiter.api.Test;

class SellerToolsServiceTest {

    private static final McpCaller SELLER = new McpCaller(new CallerProfile("u-1", "seller-app", "SELLER_MEMBER",
            "org_1", Set.of("catalog:write")), "token");
    private static final McpCaller ADMIN = new McpCaller(new CallerProfile("staff-1", "console", "STAFF", null,
            Set.of("*")), "token");

    private final FakeGuard guard = new FakeGuard();
    private final FakeCatalog catalog = new FakeCatalog();
    private final FakeOrders orders = new FakeOrders();
    private final FakeInventory inventory = new FakeInventory();
    private final SellerToolsService tools = new SellerToolsServiceImpl(guard, catalog, orders, inventory,
            new DefaultShipmentStatusPolicy());

    @Test
    void listsProductsOfTheCallersOrganisationWithANormalisedStatus() {
        catalog.next = Downstream.ok(List.of(Map.of("id", "p1")));
        ToolResult r = tools.listMyProducts(SELLER, " live ", "garri", -3);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(catalog.calls).containsExactly("products org_1 LIVE garri 0 25");
        assertThat(guard.required).containsExactly(ToolCatalog.LIST_MY_PRODUCTS);

        assertThat(tools.listMyProducts(SELLER, "SOLD", null, null).status()).isEqualTo("REJECTED");
        catalog.next = Downstream.of(Downstream.Status.UNAVAILABLE);
        assertThat(tools.listMyProducts(SELLER, null, null, 2).message()).contains("not available yet");
    }

    @Test
    void productStatusOnlyShowsTheCallersOwnProducts() {
        catalog.next = Downstream.ok(Map.of("id", "p1", "atin", "AT1", "title", "Garri", "status", "REJECTED",
                "rejectionReason", "photo", "ownerOrgId", "org_1", "secret", "x"));
        ToolResult own = tools.productStatus(SELLER, " AT1 ");
        assertThat(own.status()).isEqualTo("OK");
        assertThat(asMap(own.data())).containsOnlyKeys("id", "atin", "title", "status", "rejectionReason");

        catalog.next = Downstream.ok(Map.of("id", "p2", "sellerOrgId", "org_2"));
        assertThat(tools.productStatus(SELLER, "p2").status()).isEqualTo("NOT_FOUND");
        assertThat(tools.productStatus(ADMIN, "p2").status()).as("staff with * see any product").isEqualTo("OK");

        catalog.next = Downstream.of(Downstream.Status.FORBIDDEN);
        assertThat(tools.productStatus(SELLER, null).status()).isEqualTo("FORBIDDEN");
    }

    @Test
    void listsOrdersAndInventoryForTheOrganisation() {
        orders.next = Downstream.ok(List.of());
        assertThat(tools.listMyOrders(SELLER, "READY", "cur-2").status()).isEqualTo("OK");
        assertThat(orders.calls).containsExactly("sellerShipments ready cur-2 25");

        inventory.next = Downstream.ok(List.of());
        assertThat(tools.checkInventory(SELLER, " atgari0001 ", true).status()).isEqualTo("OK");
        assertThat(tools.checkInventory(SELLER, " ", null).status()).isEqualTo("OK");
        assertThat(inventory.calls).containsExactly("positions org_1 ATGARI0001 true", "positions org_1 null false");
    }

    @Test
    void updatesASellerFulfilledShipmentForwardOnly() {
        orders.order = Downstream.ok(Map.of("id", "o1", "shipments", List.of(
                Map.of("id", "s1", "sellerOrgId", "org_1", "fulfilmentType", "SELLER_FULFILLED",
                        "deliveryMethod", "CARRIER", "status", "ready"),
                Map.of("id", "s2", "sellerOrgId", "org_2", "fulfilmentType", "SELLER_FULFILLED",
                        "deliveryMethod", "CARRIER", "status", "ready"),
                Map.of("id", "s3", "sellerOrgId", "org_1", "fulfilmentType", "FBA_AFROTRANSACT",
                        "deliveryMethod", "CARRIER", "status", "ready"),
                Map.of("id", "s4", "sellerOrgId", "org_1", "fulfilmentType", "SELLER_FULFILLED",
                        "deliveryMethod", "PICKUP", "status", "ready"),
                Map.of("id", "s5", "sellerOrgId", "org_1", "fulfilmentType", "SELLER_FULFILLED",
                        "deliveryMethod", "CARRIER", "status", "ready", "carrier", "ups", "trackingNumber", "1Z9"))));
        orders.next = Downstream.ok(Map.of("status", "picked_up"));

        ToolResult ok = tools.updateShipmentStatus(SELLER, "o1", "s1", "shipped", " UPS ", " 1Z999 ", null);
        assertThat(ok.status()).isEqualTo("OK");
        assertThat(orders.calls).containsExactly("attachTracking s1 ups 1Z999");
        assertThat(orders.changes).containsExactly(Map.of("to", "picked_up"));

        orders.calls.clear();
        orders.changes.clear();
        assertThat(tools.updateShipmentStatus(SELLER, "o1", "s5", "SHIPPED", null, null, null).status())
                .as("the label bought in Seller Center is already on the shipment").isEqualTo("OK");
        assertThat(orders.calls).isEmpty();
        assertThat(orders.changes).containsExactly(Map.of("to", "picked_up"));

        assertThat(tools.updateShipmentStatus(SELLER, "o1", "s2", "SHIPPED", "UPS", "1Z", null).status())
                .as("another seller's shipment").isEqualTo("NOT_FOUND");
        assertThat(tools.updateShipmentStatus(SELLER, "o1", "missing", "SHIPPED", "UPS", "1Z", null).status())
                .isEqualTo("NOT_FOUND");
        ToolResult noTracking = tools.updateShipmentStatus(SELLER, "o1", "s1", "SHIPPED", null, "", null);
        assertThat(noTracking.status()).isEqualTo("REJECTED");
        assertThat(asMap(noTracking.data())).containsEntry("code", "mcp.tracking-required");
        assertThat(asMap(tools.updateShipmentStatus(SELLER, "o1", "s3", "SHIPPED", "UPS", "1Z", null).data()))
                .containsEntry("code", "mcp.not-seller-fulfilled");

        orders.changes.clear();
        assertThat(asMap(tools.updateShipmentStatus(SELLER, "o1", "s4", "DELIVERED", null, null, null).data()))
                .containsEntry("code", "mcp.handover-code-required");
        assertThat(tools.updateShipmentStatus(SELLER, "o1", "s4", "DELIVERED", null, null, " 0427 ").status())
                .isEqualTo("OK");
        assertThat(orders.changes).containsExactly(Map.of("to", "delivered", "handoverCode", "0427"));

        orders.order = Downstream.of(Downstream.Status.NOT_FOUND);
        assertThat(tools.updateShipmentStatus(SELLER, "o9", "s1", "PACKED", null, null, null).status())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void requestsACategoryWithValidation() {
        catalog.next = Downstream.ok(Map.of("id", "req-1"));
        assertThat(tools.requestCategory(SELLER, " Suya spice ", " cat-9 ", null).status()).isEqualTo("OK");
        assertThat(catalog.bodies).containsExactly(Map.of("name", "Suya spice", "parentId", "cat-9", "reason", ""));

        assertThat(tools.requestCategory(SELLER, " ", null, "x").status()).isEqualTo("REJECTED");
        assertThat(tools.requestCategory(SELLER, "x".repeat(121), null, "x").status()).isEqualTo("REJECTED");
        catalog.next = Downstream.of(Downstream.Status.REJECTED);
        assertThat(tools.requestCategory(SELLER, "Kola", "", " because ").status()).isEqualTo("REJECTED");
        assertThat(catalog.bodies.get(1)).isEqualTo(Map.of("name", "Kola", "reason", "because"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static final class FakeGuard implements ToolGuardService {
        private final List<String> required = new ArrayList<>();

        @Override
        public List<ToolDescriptor> visibleTools(CallerProfile caller) {
            return List.of();
        }

        @Override
        public Admission admit(CallerProfile caller, String toolName) {
            return null;
        }

        @Override
        public void require(CallerProfile caller, String toolName) {
            required.add(toolName);
        }

        @Override
        public void audit(CallerProfile caller, String toolName, String canonicalArguments, String outcome,
                          Duration duration) {
        }
    }

    private static final class FakeCatalog implements CatalogApi {
        private Downstream next = Downstream.ok(Map.of());
        private final List<String> calls = new ArrayList<>();
        private final List<Map<String, Object>> bodies = new ArrayList<>();

        @Override
        public Downstream publicProduct(String atin, String bearerToken) {
            return next;
        }

        @Override
        public Downstream publicVariant(String atin, String bearerToken) {
            return next;
        }

        @Override
        public Downstream products(String orgId, String status, String query, int page, int size,
                                  String bearerToken) {
            calls.add("products " + orgId + " " + status + " " + query + " " + page + " " + size);
            return next;
        }

        @Override
        public Downstream product(String idOrAtin, String bearerToken) {
            return next;
        }

        @Override
        public Downstream requestCategory(Map<String, Object> request, String bearerToken) {
            bodies.add(new HashMap<>(request));
            return next;
        }
    }

    private static final class FakeOrders implements OrdersApi {
        private Downstream order = Downstream.ok(Map.of());
        private Downstream next = Downstream.ok(Map.of());
        private final List<String> calls = new ArrayList<>();
        private final List<Map<String, Object>> changes = new ArrayList<>();

        @Override
        public Downstream order(String orderId, String bearerToken) {
            return order;
        }

        @Override
        public Downstream sellerShipments(String status, String cursor, int limit, String bearerToken) {
            calls.add("sellerShipments " + status + " " + cursor + " " + limit);
            return next;
        }

        @Override
        public Downstream transitionShipment(String shipmentId, Map<String, Object> body, String bearerToken) {
            changes.add(new HashMap<>(body));
            return next;
        }

        @Override
        public Downstream attachTracking(String shipmentId, String carrier, String trackingNumber,
                                         String bearerToken) {
            calls.add("attachTracking " + shipmentId + " " + carrier + " " + trackingNumber);
            return Downstream.ok(Map.of());
        }
    }

    private static final class FakeInventory implements InventoryApi {
        private Downstream next = Downstream.ok(List.of());
        private final List<String> calls = new ArrayList<>();

        @Override
        public Downstream availability(List<String> skus, String bearerToken) {
            return next;
        }

        @Override
        public Downstream positions(String ownerOrg, String sku, boolean lowStock, String bearerToken) {
            calls.add("positions " + ownerOrg + " " + sku + " " + lowStock);
            return next;
        }
    }
}
