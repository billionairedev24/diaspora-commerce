package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.security.ActingAsActorResolver;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A person who signed up as a customer and whose seller application was approved: identity's token now says
 * {@code pt=SELLER_MEMBER} with their organisation. On the storefront (gateway-bff sends X-Acting-As: CUSTOMER) they
 * still check out and see their orders as themselves, and orders relays that context to cart and identity.
 */
class DualRoleShopperIT extends OrdersIntegrationTest {

    private static Actor sellerOwner(String subject, String org) {
        return new Actor(subject, PrincipalType.SELLER_MEMBER, Optional.of(org),
                Set.of("account.customer", "account.seller_applicant", "seller.owner"), Set.of("catalog:write"));
    }

    @Test
    void anApprovedSellerChecksOutAndSeesTheirOrdersAsAShopper() {
        String person = id("user");
        String bearer = "tok-" + person;
        String ownOrg = id("org-malo");
        String sellerOrg = id("org-sel");
        String atin = id("ATS");
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer, Upstreams.pkg(sellerOrg, "Mama Tee", "SELLER",
                Upstreams.line(atin, sellerOrg, "SELLER", 4000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.paymentIntent(person, paymentId);
        TestSupport.as(sellerOwner(person, ownOrg));
        String request = """
                {"addressId":"addr-1","deliverySelections":[{"packageId":"%s:SELLER","option":"carrier"}]}
                """.formatted(sellerOrg);

        // The bug: the org-scoped token without the storefront's acting context is refused as a non-shopper.
        problem(send("POST", "/v1/checkout", request, "Idempotency-Key", "dual-" + person,
                "Authorization", "Bearer " + bearer), 403, "orders.customer-only");
        problem(send("GET", "/v1/orders?customer=me&limit=10", null), 403, "orders.customer-only");

        JsonNode created = ok(send("POST", "/v1/checkout", request, "Idempotency-Key", "dual-" + person,
                "Authorization", "Bearer " + bearer, ActingAsActorResolver.HEADER, "CUSTOMER"), 201);

        assertThat(created.path("clientSecret").asString()).isEqualTo("pi_" + paymentId + "_secret");
        WIREMOCK.verify(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v1/cart"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + bearer))
                .withHeader(ActingAsActorResolver.HEADER, WireMock.equalTo("CUSTOMER")));
        WIREMOCK.verify(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v1/me/addresses"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + bearer))
                .withHeader(ActingAsActorResolver.HEADER, WireMock.equalTo("CUSTOMER")));
        assertThat(jdbc.sql("select customer_id from customer_order where id = :id")
                .param("id", UUID.fromString(created.path("orderId").asString())).query(String.class).single())
                .isEqualTo(person);

        JsonNode mine = ok(send("GET", "/v1/orders?customer=me&limit=10", null,
                ActingAsActorResolver.HEADER, "CUSTOMER"));
        assertThat(mine.path("items")).anySatisfy(o -> assertThat(o.path("orderNumber").asString())
                .isEqualTo(created.path("orderNumber").asString()));
        JsonNode detail = ok(send("GET", "/v1/orders/" + created.path("orderNumber").asString(), null,
                ActingAsActorResolver.HEADER, "CUSTOMER"));
        assertThat(detail.path("orderNumber").asString()).isEqualTo(created.path("orderNumber").asString());
    }
}
