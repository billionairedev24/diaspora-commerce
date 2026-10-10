package com.afrotransact.mcp.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class ToolAccessPolicyTest {

    private final DefaultToolAccessPolicy policy = new DefaultToolAccessPolicy();
    private final ToolDescriptor search = new ToolDescriptor("search_products", Toolset.SHOPPER, null, true);
    private final ToolDescriptor shipment = new ToolDescriptor("update_shipment_status", Toolset.SELLER,
            "orders:update-status", false);
    private final ToolDescriptor split = new ToolDescriptor("order_payment_split", Toolset.STAFF, "ledger:read", true);

    @Test
    void shopperScopeUnlocksShopperToolsOnly() {
        CallerProfile shopper = caller("CUSTOMER", null, "mcp:shopper");
        assertThat(policy.decide(shopper, search).allowed()).isTrue();
        assertThat(policy.decide(shopper, shipment).reason()).isEqualTo("requires scope mcp:seller");
        assertThat(policy.decide(shopper, split).reason()).isEqualTo("requires scope mcp:staff");
    }

    @Test
    void sellerToolsNeedAnOrganisationAndThePermission() {
        assertThat(policy.decide(caller("SELLER_MEMBER", null, "mcp:seller", "orders:update-status"), shipment)
                .reason()).contains("organisation");
        assertThat(policy.decide(caller("SELLER_MEMBER", "org_1", "mcp:seller"), shipment).reason())
                .isEqualTo("requires permission orders:update-status");
        assertThat(policy.decide(caller("SELLER_MEMBER", "org_1", "mcp:seller", "orders:update-status"), shipment)
                .allowed()).isTrue();
    }

    @Test
    void staffToolsNeedAStaffAccount() {
        assertThat(policy.decide(caller("SELLER_MEMBER", "org_1", "mcp:staff", "ledger:read"), split).reason())
                .contains("staff account");
        assertThat(policy.decide(caller("STAFF", null, "mcp:staff", "ledger:read"), split).allowed()).isTrue();
        assertThat(policy.decide(caller("STAFF", null, "mcp:staff"), split).allowed()).isFalse();
    }

    @Test
    void wildcardIsAllowedEverything() {
        CallerProfile admin = caller("STAFF", null, "*");
        assertThat(policy.decide(admin, search).allowed()).isTrue();
        assertThat(policy.decide(admin, shipment).allowed()).isTrue();
        assertThat(policy.decide(admin, split).allowed()).isTrue();
    }

    @Test
    void argumentDigestIsStableSha256() {
        assertThat(ArgumentDigest.sha256("{\"q\":\"egusi\"}")).startsWith("sha256:").hasSize(71)
                .isEqualTo(ArgumentDigest.sha256("{\"q\":\"egusi\"}"))
                .isNotEqualTo(ArgumentDigest.sha256("{\"q\":\"ogbono\"}"));
    }

    private static CallerProfile caller(String type, String org, String... permissions) {
        return new CallerProfile("user-1", "claude-desktop", type, org, Set.of(permissions));
    }
}
