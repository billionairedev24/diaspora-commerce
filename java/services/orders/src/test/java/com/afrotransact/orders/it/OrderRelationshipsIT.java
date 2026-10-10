package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.orders.application.OrderQueryService;
import com.afrotransact.orders.application.OrderSearchQuery;
import com.afrotransact.orders.application.ShipmentService;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.FgaObject;
import com.afrotransact.platform.security.relationship.RelationshipTuple;
import com.afrotransact.platform.security.relationship.RelationshipWriter;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * S248: orders' relationship checks against a real OpenFGA (local image {@code openfga/openfga:v1.10.0}) loaded with
 * {@code contracts/openfga/model.json}. Sellers only see and move their own organisation's shipments even with a forged
 * {@code org} claim; staff act on orders of their market (the order's ship-to country); revocation applies at once.
 */
class OrderRelationshipsIT extends OrdersIntegrationTest {

    /** LAGOS sells in the US, MAMATEE in Great Britain (see the seeded tuples). */
    private static final String LAGOS = "org_lagostextilehub00001";
    private static final String MAMATEE = "org_mamateespantry000001";
    private static final ShipTo LONDON = new ShipTo("addr-gb", "Ama Mensah", "+447700900123", "10 Downing St", null,
            "London", null, "SW1A 2AA", "GB", null);

    private static final GenericContainer<?> OPENFGA = OpenFgaTestContainer.create();
    private static final String STORE_ID;

    static {
        OPENFGA.start();
        STORE_ID = OpenFgaTestContainer.createStoreWithModel(OPENFGA);
    }

    private OrderQueryService queries;
    private ShipmentService shipments;
    private RelationshipWriter tuples;

    @DynamicPropertySource
    static void openFga(DynamicPropertyRegistry registry) {
        registry.add("at.security.fga.enabled", () -> "true");
        registry.add("at.security.fga.api-url", () -> OpenFgaTestContainer.apiUrl(OPENFGA));
        registry.add("at.security.fga.store-id", () -> STORE_ID);
        registry.add("at.security.fga.timeout", () -> "5s");
    }

    @BeforeEach
    void seedRelationships(@Autowired OrderQueryService orderQueries, @Autowired ShipmentService shipmentService,
                           @Autowired RelationshipWriter relationshipWriter,
                           @Autowired ResourceAccessChecker access) {
        this.queries = orderQueries;
        this.shipments = shipmentService;
        this.tuples = relationshipWriter;
        assertThat(access.relationshipsEnabled()).isTrue();
        FgaObject platform = FgaObject.PLATFORM;
        FgaObject us = FgaObject.of("market", "US");
        FgaObject gb = FgaObject.of("market", "GB");
        FgaObject lagos = FgaObject.organization(LAGOS);
        FgaObject mamatee = FgaObject.organization(MAMATEE);
        tuples.grant(
                RelationshipTuple.of(platform, "platform", lagos), RelationshipTuple.of(us, "market", lagos),
                RelationshipTuple.of(platform, "platform", mamatee), RelationshipTuple.of(gb, "market", mamatee),
                RelationshipTuple.of("user:ful-lagos", "fulfilment", lagos),
                RelationshipTuple.of("user:ful-mamatee", "fulfilment", mamatee),
                RelationshipTuple.of("user:local-dev", "super_admin", platform),
                RelationshipTuple.of("user:global-support", "support", platform),
                RelationshipTuple.of("user:us-support", "support", us),
                RelationshipTuple.of("user:us-ops", "operations", us));
    }

    @Test
    void aSellerCannotSeeOrMoveAnotherOrganisationsShipmentEvenWithAForgedOrgClaim() {
        Order order = placedOrder(id("cust"), UUID.randomUUID().toString(),
                spec(LAGOS, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER),
                spec(MAMATEE, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER));
        Shipment lagosShipment = shipmentOf(order, LAGOS);
        Shipment mamateeShipment = shipmentOf(order, MAMATEE);

        // ful-lagos fulfils for LAGOS only; the token claims MAMATEE and carries the seller permissions.
        TestSupport.as(member("ful-lagos", MAMATEE));
        assertDenied(() -> queries.get(order.number()));
        assertDenied(() -> shipments.transition(mamateeShipment.id(), "confirmed", null, id("k")));
        assertDenied(() -> queries.sellerQueue(null, null, null, 20));

        HttpResponse<String> http = send("POST", "/v1/shipments/" + mamateeShipment.id() + "/transitions",
                "{\"to\":\"confirmed\"}");
        problem(http, 403, "auth.relationship-denied");
        assertThat(status(mamateeShipment.id())).isEqualTo("PLACED");

        // With its real claim it sees and confirms its own shipment only.
        TestSupport.as(member("ful-lagos", LAGOS));
        assertThat(queries.get(order.number()).shipments()).hasSize(1);
        assertThat(shipments.transition(lagosShipment.id(), "confirmed", null, id("k")).outcome())
                .isEqualTo("applied");
        assertThat(queries.sellerQueue(null, null, null, 100).items()).isNotEmpty();
    }

    @Test
    void staffGrantsScopedToAMarketCoverOrdersShippedToThatMarket() {
        Order usOrder = placedOrder(id("cust"), UUID.randomUUID().toString(),
                spec(LAGOS, FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER));
        Order gbOrder = placedOrder(id("cust"), UUID.randomUUID().toString(), LONDON,
                spec(MAMATEE, FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER));

        // US support reads US orders only, and cannot search every market.
        TestSupport.as(staff("us-support", "orders:read"));
        assertThat(queries.get(usOrder.number()).orderNumber()).isEqualTo(usOrder.number());
        assertDenied(() -> queries.get(gbOrder.number()));
        assertDenied(() -> queries.search(new OrderSearchQuery(null, null, null, null, null, null, null), null, 20));
        assertThat(queries.sellerQueue(LAGOS, null, null, 20).items()).isNotEmpty();
        assertDenied(() -> queries.sellerQueue(MAMATEE, null, null, 20));

        // US operations moves AfroTransact-fulfilled shipments of US orders, not of GB orders.
        TestSupport.as(staff("us-ops", "orders:read", "orders:manage"));
        assertThat(shipments.transition(shipmentOf(usOrder, LAGOS).id(), "confirmed", null, id("k")).outcome())
                .isEqualTo("applied");
        assertDenied(() -> shipments.transition(shipmentOf(gbOrder, MAMATEE).id(), "confirmed", null, id("k")));
        assertThat(status(shipmentOf(gbOrder, MAMATEE).id())).isEqualTo("PLACED");

        // A platform-wide grant spans markets and may search.
        TestSupport.as(staff("global-support", "orders:read"));
        assertThat(queries.get(gbOrder.number()).orderNumber()).isEqualTo(gbOrder.number());
        assertThat(queries.search(new OrderSearchQuery(gbOrder.number(), null, null, null, null, null, null), null,
                20).items()).hasSize(1);
    }

    @Test
    void revokingAMembershipTakesEffectOnTheNextCheck() {
        Order order = placedOrder(id("cust"), UUID.randomUUID().toString(),
                spec(MAMATEE, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER));
        TestSupport.as(member("ful-mamatee", MAMATEE));
        assertThat(queries.get(order.number()).shipments()).hasSize(1);

        RelationshipTuple membership = RelationshipTuple.of("user:ful-mamatee", "fulfilment",
                FgaObject.organization(MAMATEE));
        tuples.revoke(membership);
        try {
            assertDenied(() -> queries.get(order.number()));
            assertDenied(() -> shipments.transition(shipmentOf(order, MAMATEE).id(), "confirmed", null, id("k")));
        } finally {
            tuples.grant(membership);
        }
    }

    private static Shipment shipmentOf(Order order, String org) {
        return order.shipments().stream().filter(s -> s.sellerOrgId().equals(org)).findFirst().orElseThrow();
    }

    private static Actor member(String subject, String org) {
        return new Actor(subject, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.fulfilment"),
                Set.of("orders:read", "orders:update-status"));
    }

    private static Actor staff(String subject, String... permissions) {
        return new Actor(subject, PrincipalType.STAFF, Optional.empty(), Set.of("platform.customer_support"),
                Set.of(permissions));
    }

    private static void assertDenied(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(ForbiddenActionException.class)
                .hasFieldOrPropertyWithValue("code", "auth.relationship-denied");
    }
}
