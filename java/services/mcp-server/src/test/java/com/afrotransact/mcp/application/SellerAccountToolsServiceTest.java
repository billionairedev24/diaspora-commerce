package com.afrotransact.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.mcp.domain.CallerProfile;
import com.afrotransact.mcp.domain.ToolDescriptor;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.OrdersApi;
import com.afrotransact.mcp.port.PaymentsApi;
import org.junit.jupiter.api.Test;

/** The seller's money and account tools: when the money arrives, why it is late, and one order without buyer data. */
class SellerAccountToolsServiceTest {

    private static final McpCaller SELLER = new McpCaller(new CallerProfile("u-1", "seller-app", "SELLER_MEMBER",
            "org_1", Set.of("orders:read")), "token");

    private final List<String> calls = new ArrayList<>();
    private Downstream balance = Downstream.ok(Map.of());
    private Downstream health = Downstream.ok(Map.of());
    private Downstream order = Downstream.ok(Map.of());

    private final SellerAccountToolsService tools = new SellerAccountToolsServiceImpl(new AllowAll(),
            new PaymentsApi() {
                @Override
                public Downstream orderSplit(String orderId, String bearerToken) {
                    return Downstream.ok(Map.of());
                }

                @Override
                public Downstream payoutBalance(String orgId, String bearerToken) {
                    calls.add("balance " + orgId);
                    return balance;
                }

                @Override
                public Downstream payoutRequests(String orgId, int limit, String bearerToken) {
                    calls.add("requests " + orgId + " " + limit);
                    return Downstream.ok(List.of());
                }
            }, (orgId, bearerToken) -> {
                calls.add("health " + orgId);
                return health;
            }, new OrdersApi() {
                @Override
                public Downstream order(String orderId, String bearerToken) {
                    return order;
                }

                @Override
                public Downstream myOrders(String cursor, int limit, String bearerToken) {
                    return Downstream.ok(List.of());
                }

                @Override
                public Downstream sellerShipments(String status, String cursor, int limit, String bearerToken) {
                    return Downstream.ok(List.of());
                }

                @Override
                public Downstream transitionShipment(String shipmentId, Map<String, Object> body,
                                                     String bearerToken) {
                    return Downstream.ok(Map.of());
                }

                @Override
                public Downstream attachTracking(String shipmentId, String carrier, String trackingNumber,
                                                 String bearerToken) {
                    return Downstream.ok(Map.of());
                }
            });

    @Test
    void payoutBalanceIsForTheCallersOrganisationAndCarriesTheReleaseRules() {
        balance = Downstream.ok(Map.of("availableMinor", 1000, "onHold", List.of(Map.of("orderId", "AT-1",
                "amountMinor", 70000, "releaseAt", "2026-10-24T10:00:00Z"))));
        ToolResult r = tools.payoutBalance(SELLER);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(calls).containsExactly("balance org_1");
        assertThat(r.message()).contains("7 days after delivery").contains("releaseAt").contains("account_health");
        assertThat(String.valueOf(r.data())).contains("2026-10-24T10:00:00Z");

        balance = Downstream.of(Downstream.Status.FORBIDDEN);
        assertThat(tools.payoutBalance(SELLER).status()).as("a member without access to payouts").isEqualTo("FORBIDDEN");
        balance = Downstream.of(Downstream.Status.UNAVAILABLE);
        assertThat(tools.payoutBalance(SELLER).message()).contains("not available yet");
    }

    @Test
    void payoutsAndAccountHealthUseTheCallersOrganisation() {
        assertThat(tools.listPayouts(SELLER).status()).isEqualTo("OK");
        assertThat(tools.accountHealth(SELLER).status()).isEqualTo("OK");
        assertThat(calls).containsExactly("requests org_1 20", "health org_1");
    }

    @Test
    void ownOrderShowsOnlyThePartnersShipmentsWithoutBuyerData() {
        order = Downstream.ok(Map.of("id", "o1", "number", "AT-1", "status", "paid", "buyerEmail", "ada@example.com",
                "shippingAddress", Map.of("line1", "1 Main St"), "shipments", List.of(
                        Map.of("id", "s1", "sellerOrgId", "org_1", "status", "ready", "recipientPhone", "+1713",
                                "lines", List.of(Map.of("title", "Garri", "quantity", 2, "buyerNote", "leave at door"))),
                        Map.of("id", "s2", "sellerOrgId", "org_2", "status", "ready"))));
        ToolResult r = tools.getMyOrder(SELLER, " AT-1 ");
        assertThat(r.status()).isEqualTo("OK");
        String shown = String.valueOf(r.data());
        assertThat(shown).contains("s1", "Garri").doesNotContain("s2", "ada@example.com", "Main St", "+1713",
                "leave at door");

        order = Downstream.ok(Map.of("id", "o2", "shipments", List.of(Map.of("id", "s9", "sellerOrgId", "org_9"))));
        assertThat(tools.getMyOrder(SELLER, "o2").status()).isEqualTo("NOT_FOUND");
        assertThat(tools.getMyOrder(SELLER, " ").status()).isEqualTo("REJECTED");
    }

    @Test
    void everyNewToolIsInTheCatalogue() {
        assertThat(ToolCatalog.find(ToolCatalog.PAYOUT_BALANCE)).isPresent();
        assertThat(ToolCatalog.find(ToolCatalog.SELLER_PAYOUT_STATUS)).get().extracting(ToolDescriptor::readOnly)
                .isEqualTo(true);
        assertThat(ToolCatalog.all()).hasSize(27);
    }

    /** Access is checked by the guard's own tests; here every call is allowed. */
    private static final class AllowAll implements ToolGuardService {
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
        }

        @Override
        public void audit(CallerProfile caller, String toolName, String canonicalArguments, String outcome,
                          Duration duration) {
        }
    }
}
