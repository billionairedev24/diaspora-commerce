package com.afrotransact.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Security off (local profile): everyone is the local super admin, except customers, drivers and seller / provider
 * members, who act as themselves when their token comes with the request (their carts, orders, routes and
 * organisations' shipments are keyed by who they are).
 */
class LocalActorResolverTest {

    private static String token(String claimsJson) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"RS256\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8)) + ".c2lnbmF0dXJl";
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void noRequestOrNoTokenIsTheSuperAdmin() {
        assertThat(new LocalActorResolver().current()).isEqualTo(LocalActorResolver.LOCAL_SUPER_ADMIN);
        assertThat(LocalActorResolver.actorFor(null).can("orders:manage")).isTrue();
        assertThat(LocalActorResolver.actorFor("Basic Zm9vOmJhcg==")).isEqualTo(LocalActorResolver.LOCAL_SUPER_ADMIN);
        assertThat(LocalActorResolver.actorFor("Bearer not-a-jwt")).isEqualTo(LocalActorResolver.LOCAL_SUPER_ADMIN);
    }

    @Test
    void aCustomerTokenActsAsThatCustomerWithItsOwnPermissions() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/orders");
        request.addHeader("Authorization", "Bearer " + token("{\"sub\":\"cust-1\",\"pt\":\"CUSTOMER\","
                + "\"roles\":[\"account.customer\"],\"perms\":[\"mcp:shopper\"],\"scope\":[\"openid\"]}"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Actor actor = new LocalActorResolver().current();

        assertThat(actor.subject()).isEqualTo("cust-1");
        assertThat(actor.type()).isEqualTo(PrincipalType.CUSTOMER);
        assertThat(actor.permissions()).containsExactlyInAnyOrder("mcp:shopper", "openid");
        assertThat(actor.can("orders:manage")).isFalse();
    }

    @Test
    void aDriverTokenActsAsThatDriver() {
        Actor actor = LocalActorResolver.actorFor("bearer " + token("{\"sub\":\"drv-7\",\"pt\":\"driver\","
                + "\"perms\":[\"delivery:operate\"],\"scope\":\"openid profile\"}"));

        assertThat(actor.subject()).isEqualTo("drv-7");
        assertThat(actor.type()).isEqualTo(PrincipalType.DRIVER);
        assertThat(actor.permissions()).containsExactlyInAnyOrder("delivery:operate", "openid", "profile");
    }

    @Test
    void aTokenNeverGrantsTheWildcard() {
        Actor actor = LocalActorResolver.actorFor("Bearer " + token("{\"sub\":\"cust-2\",\"pt\":\"CUSTOMER\","
                + "\"perms\":[\"*\"]}"));

        assertThat(actor.type()).isEqualTo(PrincipalType.CUSTOMER);
        assertThat(actor.can("orders:manage")).isFalse();
    }

    @Test
    void aSellerMemberActsForTheirOrganisation() {
        Actor actor = LocalActorResolver.actorFor("Bearer " + token("{\"sub\":\"u1\",\"pt\":\"SELLER_MEMBER\","
                + "\"org\":\"org_mama_put_pantry\",\"roles\":[\"seller.owner\"],\"perms\":[\"orders:update-status\"]}"));

        assertThat(actor.type()).isEqualTo(PrincipalType.SELLER_MEMBER);
        assertThat(actor.orgId()).contains("org_mama_put_pantry");
        assertThat(actor.can("orders:update-status")).isTrue();
        assertThat(actor.can("orders:manage")).isFalse();
    }

    @Test
    void staffAndServicesKeepTheSuperAdmin() {
        for (String pt : new String[] {"STAFF", "SERVICE", "PARTNER_APP", "AI_AGENT", "SOMETHING_NEW"}) {
            Actor actor = LocalActorResolver.actorFor("Bearer " + token("{\"sub\":\"u1\",\"pt\":\"" + pt
                    + "\",\"org\":\"org_x\",\"perms\":[\"orders:read\"]}"));
            assertThat(actor).as(pt).isEqualTo(LocalActorResolver.LOCAL_SUPER_ADMIN);
        }
        assertThat(LocalActorResolver.actorFor("Bearer " + token("{\"pt\":\"CUSTOMER\"}")))
                .as("no subject").isEqualTo(LocalActorResolver.LOCAL_SUPER_ADMIN);
    }
}
