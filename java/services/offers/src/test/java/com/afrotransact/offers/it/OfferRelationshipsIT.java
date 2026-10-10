package com.afrotransact.offers.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.offers.application.CreateOfferCommand;
import com.afrotransact.offers.application.OfferService;
import com.afrotransact.offers.application.OfferView;
import com.afrotransact.offers.application.OffersPermissions;
import com.afrotransact.offers.application.OffersRelations;
import com.afrotransact.offers.application.UpdateOfferCommand;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.FgaObject;
import com.afrotransact.platform.security.relationship.RelationshipTuple;
import com.afrotransact.platform.security.relationship.RelationshipWriter;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.security.relationship.ResourceAccessCheckers;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * S022 pilot against a real OpenFGA (Testcontainers, local image) loaded with {@code contracts/openfga/model.json}:
 * relationship checks on offers stop a member of one organisation from touching another organisation's offers even
 * with the right RBAC permission and a forged {@code org} claim, and staff grants are scoped by market.
 */
class OfferRelationshipsIT extends OffersIntegrationTest {

    /** LAGOS sells in the US, MAMATEE in Great Britain (see the seeded tuples). */
    private static final GenericContainer<?> OPENFGA = OpenFgaTestContainer.create();
    private static final String STORE_ID;
    private static final ShipFrom HOUSTON = new ShipFrom("Houston", "TX", "US", "77002");

    static {
        OPENFGA.start();
        STORE_ID = OpenFgaTestContainer.createStoreWithModel(OPENFGA);
    }

    private final OfferService offers;
    private final RelationshipWriter tuples;
    private final ResourceAccessChecker access;
    private final PermissionChecker permissions;
    private final int port;

    @Autowired
    OfferRelationshipsIT(JdbcClient jdbc, OfferService offers, RelationshipWriter tuples, ResourceAccessChecker access,
                         PermissionChecker permissions, @LocalServerPort int port) {
        super(jdbc);
        this.offers = offers;
        this.tuples = tuples;
        this.access = access;
        this.permissions = permissions;
        this.port = port;
    }

    @DynamicPropertySource
    static void openFga(DynamicPropertyRegistry registry) {
        registry.add("at.security.fga.enabled", () -> "true");
        registry.add("at.security.fga.api-url", () -> OpenFgaTestContainer.apiUrl(OPENFGA));
        registry.add("at.security.fga.store-id", () -> STORE_ID);
        registry.add("at.security.fga.timeout", () -> "5s");
    }

    @BeforeEach
    void seedRelationships() {
        FgaObject platform = FgaObject.PLATFORM;
        FgaObject us = FgaObject.of("market", "US");
        FgaObject gb = FgaObject.of("market", "GB");
        FgaObject lagos = FgaObject.organization(LAGOS);
        FgaObject mamatee = FgaObject.organization(MAMATEE);
        tuples.grant(
                // what authz-sync writes from seller.org.approved / updated
                RelationshipTuple.of(platform, "platform", us), RelationshipTuple.of(platform, "platform", gb),
                RelationshipTuple.of(platform, "platform", lagos), RelationshipTuple.of(us, "market", lagos),
                RelationshipTuple.of("user:*", "sells_products", lagos),
                RelationshipTuple.of(platform, "platform", mamatee), RelationshipTuple.of(gb, "market", mamatee),
                RelationshipTuple.of("user:*", "sells_products", mamatee),
                // what authz-sync writes from identity.role.granted (orgId set → organisation role)
                RelationshipTuple.of("user:cat-lagos", "catalog", lagos),
                RelationshipTuple.of("user:owner-mamatee", "owner", mamatee),
                // platform roles: the local super admin and a platform-wide support agent
                RelationshipTuple.of("user:local-dev", "super_admin", platform),
                RelationshipTuple.of("user:global-support", "support", platform),
                // market-scoped staff grants (operators / staff console)
                RelationshipTuple.of("user:us-catalog", "catalog_manager", us),
                RelationshipTuple.of("user:us-support", "support", us));
    }

    @Test
    void aMemberOfOrgACannotModifyOrgBsOfferEvenWithThePermissionAndAForgedOrgClaim() throws Exception {
        assertThat(access.relationshipsEnabled()).isTrue();
        OfferView lagosOffer = offerFor(LAGOS, "LAG-1");
        OfferView mamateeOffer = offerFor(MAMATEE, "MAM-1");

        // cat-lagos is a catalog member of LAGOS only, but the token claims MAMATEE and carries offers:write.
        Actor forger = new Actor("cat-lagos", PrincipalType.SELLER_MEMBER, Optional.of(MAMATEE),
                Set.of("seller.catalog"), Set.of(OffersPermissions.READ, OffersPermissions.WRITE));
        TestActors.as(forger);

        // RBAC alone (claims only) would let it through:
        assertThatCode(() -> ResourceAccessCheckers.rbacOnly(permissions).requireOwned(OffersPermissions.WRITE,
                OffersRelations.CAN_EDIT, FgaObject.of("offer", mamateeOffer.offer().id()), MAMATEE))
                .doesNotThrowAnyException();

        // With relationships, every write path is refused:
        assertDenied(() -> offers.update(mamateeOffer.offer().id(), priceTo(1)));
        assertDenied(() -> offers.pause(mamateeOffer.offer().id()));
        assertDenied(() -> offers.withdraw(mamateeOffer.offer().id()));
        assertDenied(() -> offers.create(offer(atinLiveFor(MAMATEE), "FORGED-1")));
        assertDenied(() -> offers.setSellerStock("MAM-1", 0, "forged", null));
        // ... and so are reads of the other organisation.
        assertDenied(() -> offers.get(mamateeOffer.offer().id()));
        assertDenied(() -> offers.list(null, null, null, 0, 20));

        // Over HTTP the denial is an RFC 9457 problem with code auth.relationship-denied.
        HttpResponse<String> http = patch("/v1/offers/" + mamateeOffer.offer().id(), "{\"priceMinor\":1}");
        assertThat(http.statusCode()).isEqualTo(403);
        assertThat(http.body()).contains("auth.relationship-denied");

        // Nothing changed on MAMATEE's offer.
        TestActors.reset();
        OfferView unchanged = offers.get(mamateeOffer.offer().id());
        assertThat(unchanged.offer().priceMinor()).isEqualTo(1500);
        assertThat(unchanged.offer().status()).isEqualTo(OfferStatus.ACTIVE);

        // The same member, with its real claim, still manages its own organisation's offer.
        TestActors.as(new Actor("cat-lagos", PrincipalType.SELLER_MEMBER, Optional.of(LAGOS),
                Set.of("seller.catalog"), Set.of(OffersPermissions.READ, OffersPermissions.WRITE)));
        assertThat(offers.update(lagosOffer.offer().id(), priceTo(1299)).offer().priceMinor()).isEqualTo(1299);
        assertThat(offers.list(null, null, null, 0, 100).items()).extracting(v -> v.offer().sellerOrgId())
                .containsOnly(LAGOS);
    }

    @Test
    void aStaffGrantScopedToAMarketWorksOnlyInThatMarket() {
        OfferView usOffer = offerFor(LAGOS, "LAG-2");
        OfferView gbOffer = offerFor(MAMATEE, "MAM-2");

        Actor usCatalog = staff("us-catalog", OffersPermissions.READ, OffersPermissions.WRITE);
        TestActors.as(usCatalog);

        // RBAC alone lets any staff member with offers:write edit any organisation's offer:
        assertThatCode(() -> ResourceAccessCheckers.rbacOnly(permissions).requireOwned(OffersPermissions.WRITE,
                OffersRelations.CAN_EDIT, FgaObject.of("offer", gbOffer.offer().id()), MAMATEE))
                .doesNotThrowAnyException();

        // A catalog-manager grant on market:US works for the US seller ...
        assertThat(offers.update(usOffer.offer().id(), priceTo(1199)).offer().priceMinor()).isEqualTo(1199);
        assertThat(offers.pause(usOffer.offer().id()).offer().status()).isEqualTo(OfferStatus.PAUSED);
        assertThat(offers.list(LAGOS, null, null, 0, 100).items()).isNotEmpty();
        // ... but not for the GB seller, nor for "every organisation".
        assertDenied(() -> offers.update(gbOffer.offer().id(), priceTo(1)));
        assertDenied(() -> offers.get(gbOffer.offer().id()));
        assertDenied(() -> offers.list(MAMATEE, null, null, 0, 20));
        assertDenied(() -> offers.list(null, null, null, 0, 20));

        // A US support agent reads US offers (view only: RBAC offers:read and no catalog grant).
        TestActors.as(staff("us-support", OffersPermissions.READ));
        assertThat(offers.get(usOffer.offer().id()).offer().sellerOrgId()).isEqualTo(LAGOS);
        assertDenied(() -> offers.get(gbOffer.offer().id()));

        // A platform-wide grant spans markets and may list every organisation's offers.
        TestActors.as(staff("global-support", OffersPermissions.READ));
        assertThat(offers.get(gbOffer.offer().id()).offer().sellerOrgId()).isEqualTo(MAMATEE);
        assertThat(offers.list(null, null, null, 0, 100).items()).extracting(v -> v.offer().sellerOrgId())
                .contains(LAGOS, MAMATEE);
    }

    @Test
    void revokingAMembershipTakesEffectOnTheNextCheck() {
        OfferView mamateeOffer = offerFor(MAMATEE, "MAM-3");
        Actor owner = new Actor("owner-mamatee", PrincipalType.SELLER_MEMBER, Optional.of(MAMATEE),
                Set.of("seller.owner"), Set.of(OffersPermissions.READ, OffersPermissions.WRITE));
        TestActors.as(owner);
        assertThat(offers.get(mamateeOffer.offer().id()).offer().id()).isEqualTo(mamateeOffer.offer().id());

        RelationshipTuple membership = RelationshipTuple.of("user:owner-mamatee", "owner",
                FgaObject.organization(MAMATEE));
        tuples.revoke(membership);
        try {
            // The token still says org=MAMATEE with offers:write; the relationship is gone.
            assertDenied(() -> offers.get(mamateeOffer.offer().id()));
        } finally {
            tuples.grant(membership);
        }
    }

    private OfferView offerFor(String org, String skuPrefix) {
        TestActors.reset();
        CreateOfferCommand cmd = new CreateOfferCommand(atinLiveFor(org), null, org, skuPrefix + "-" + atin(), null,
                OfferCondition.NEW, 1500, null, "USD", 0, Fulfilment.SELLER, 5, 2, HOUSTON);
        return offers.create(cmd);
    }

    private static String atinLiveFor(String org) {
        String variant = atin();
        StubCatalog.live(atin(), variant, null, org);
        return variant;
    }

    private static CreateOfferCommand offer(String atin, String sku) {
        return new CreateOfferCommand(atin, null, null, sku, null, OfferCondition.NEW, 999, null, "USD", 0,
                Fulfilment.SELLER, 3, 2, HOUSTON);
    }

    private static UpdateOfferCommand priceTo(long price) {
        return new UpdateOfferCommand(null, price, null, null, null, null, null, null, null);
    }

    private static Actor staff(String subject, String... permissions) {
        return new Actor(subject, PrincipalType.STAFF, Optional.empty(), Set.of("platform.catalog_manager"),
                Set.of(permissions));
    }

    private static void assertDenied(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(ForbiddenActionException.class)
                .hasFieldOrPropertyWithValue("code", "auth.relationship-denied");
    }

    private HttpResponse<String> patch(String path, String json) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
