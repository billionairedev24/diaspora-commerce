package com.afrotransact.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.mcp.domain.CallerProfile;
import com.afrotransact.mcp.domain.DefaultToolAccessPolicy;
import com.afrotransact.mcp.domain.ShipmentStatusPolicy;
import com.afrotransact.mcp.domain.ToolDescriptor;
import com.afrotransact.mcp.domain.Toolset;
import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.InventoryApi;
import com.afrotransact.mcp.port.OrdersApi;
import com.afrotransact.mcp.port.PaymentsApi;
import com.afrotransact.mcp.port.SellerApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-tenant proof for mcp-server. Tools never take an organisation or customer id from the prompt for seller or
 * shopper work: the org comes from the token ({@link CallerProfile#orgId()}), every platform call relays the caller's
 * own bearer token, and tools that fetch a record by id (order, shipment, product) re-check the record's owner org.
 * The only tools with an {@code orgId} argument are staff tools, behind the STAFF toolset rule.
 */
class McpTenantIsolationTest {

    private static final String A = "org-a";
    private static final String B = "org-b";
    private static final String TOKEN_B = "token-of-b";

    private final ToolGuardService guard = mock(ToolGuardService.class);
    private final CatalogApi catalog = mock(CatalogApi.class);
    private final OrdersApi orders = mock(OrdersApi.class);
    private final InventoryApi inventory = mock(InventoryApi.class);
    private final PaymentsApi payments = mock(PaymentsApi.class);
    private final SellerApi sellers = mock(SellerApi.class);
    private final McpCaller sellerB = new McpCaller(new CallerProfile("user-b", "client", "SELLER_MEMBER", B,
            Set.of("mcp:seller")), TOKEN_B);

    @BeforeEach
    void orderOfA() {
        Map<String, Object> mine = Map.of("id", "shp-b", "sellerOrgId", B, "status", "PENDING");
        Map<String, Object> theirs = Map.of("id", "shp-a", "sellerOrgId", A, "status", "PENDING");
        when(orders.order("ord-1", TOKEN_B)).thenReturn(Downstream.ok(Map.of("id", "ord-1", "status", "PAID",
                "shipments", List.of(mine, theirs))));
        when(orders.order("ord-a-only", TOKEN_B)).thenReturn(Downstream.ok(Map.of("id", "ord-a-only", "status", "PAID",
                "shipments", List.of(theirs))));
    }

    @BeforeEach
    void emptyAnswers() {
        Downstream empty = Downstream.ok(Map.of());
        when(catalog.products(any(), any(), any(), anyInt(),
                anyInt(), any())).thenReturn(empty);
        when(inventory.positions(any(), any(), anyBoolean(), any())).thenReturn(empty);
        when(payments.payoutBalance(any(), any())).thenReturn(empty);
        when(sellers.accountHealth(any(), any())).thenReturn(empty);
    }

    private SellerToolsServiceImpl sellerTools() {
        return new SellerToolsServiceImpl(guard, catalog, orders, inventory, mock(ShipmentStatusPolicy.class));
    }

    private SellerAccountToolsServiceImpl accountTools() {
        return new SellerAccountToolsServiceImpl(guard, payments, sellers, orders);
    }

    @Test
    void shipmentOfAnotherOrganisationCannotBeUpdated() {
        ToolResult r = sellerTools().updateShipmentStatus(sellerB, "ord-a-only", "shp-a", "SHIPPED", null, null, null);
        assertThat(r.status()).isEqualTo("NOT_FOUND");
        verify(orders, never()).transitionShipment(anyString(), any(), any());
        verify(orders, never()).attachTracking(anyString(), anyString(), anyString(), any());
        // a shipment id of A smuggled into an order that B does belong to is refused as well
        ToolResult mixed = sellerTools().updateShipmentStatus(sellerB, "ord-1", "shp-a", "SHIPPED", null, null, null);
        assertThat(mixed.status()).isEqualTo("NOT_FOUND");
        verify(orders, never()).transitionShipment(anyString(), any(), any());
    }

    @Test
    void orderViewShowsOnlyTheCallersOwnShipments() {
        ToolResult both = accountTools().getMyOrder(sellerB, "ord-1");
        assertThat(both.status()).isEqualTo("OK");
        assertThat(both.data().toString()).contains("shp-b").doesNotContain("shp-a");
        assertThat(accountTools().getMyOrder(sellerB, "ord-a-only").status()).isEqualTo("NOT_FOUND");
    }

    @Test
    void productOfAnotherOrganisationLooksNonexistent() {
        when(catalog.product("AT1AAAAAAA", TOKEN_B)).thenReturn(Downstream.ok(Map.of("id", "p1", "atin", "AT1AAAAAAA",
                "ownerOrgId", A, "title", "Egusi", "status", "LIVE")));
        assertThat(sellerTools().productStatus(sellerB, "AT1AAAAAAA").status()).isEqualTo("NOT_FOUND");
    }

    @Test
    void sellerListsAndBalancesUseTheTokenOrgNeverAnArgument() {
        sellerTools().listMyProducts(sellerB, null, null, null);
        verify(catalog).products(B, null, null, 0, 25, TOKEN_B);
        sellerTools().checkInventory(sellerB, null, null);
        verify(inventory).positions(B, null, false, TOKEN_B);
        accountTools().payoutBalance(sellerB);
        verify(payments).payoutBalance(B, TOKEN_B);
        accountTools().accountHealth(sellerB);
        verify(sellers).accountHealth(B, TOKEN_B);
    }

    @Test
    void sellerAndShopperPrincipalsCannotUseStaffTools() {
        DefaultToolAccessPolicy policy = new DefaultToolAccessPolicy();
        ToolDescriptor staffTool = new ToolDescriptor("seller_payout_status", Toolset.STAFF, null, true);
        CallerProfile seller = new CallerProfile("user-b", "client", "SELLER_MEMBER", B,
                Set.of("mcp:staff", "mcp:seller"));
        assertThat(policy.decide(seller, staffTool).allowed()).isFalse();
        CallerProfile customer = new CallerProfile("cust-1", "client", "CUSTOMER", null, Set.of("mcp:staff"));
        assertThat(policy.decide(customer, staffTool).allowed()).isFalse();
    }

    @Test
    void sellerToolsNeedAnOrganisationInTheToken() {
        DefaultToolAccessPolicy policy = new DefaultToolAccessPolicy();
        ToolDescriptor sellerTool = new ToolDescriptor("payout_balance", Toolset.SELLER, null, true);
        CallerProfile noOrg = new CallerProfile("cust-1", "client", "CUSTOMER", null, Set.of("mcp:seller"));
        assertThat(policy.decide(noOrg, sellerTool).allowed()).isFalse();
    }
}
