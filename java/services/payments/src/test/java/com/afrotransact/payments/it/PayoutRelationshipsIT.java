package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.FgaObject;
import com.afrotransact.platform.security.relationship.RelationshipTuple;
import com.afrotransact.platform.security.relationship.RelationshipWriter;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * S248: payments' relationship checks against a real OpenFGA (local image {@code openfga/openfga:v1.10.0}) loaded with
 * {@code contracts/openfga/model.json}. An organisation's balance, statement, label charges and payouts are refused
 * to members of other organisations even with the permissions and a forged {@code org} claim, and to members whose
 * role never sees payouts; Finance staff grants are scoped by market; revocation applies at once.
 */
class PayoutRelationshipsIT extends PaymentsIntegrationTest {

    /** LAGOS sells in the US, MAMATEE in Great Britain (see the seeded tuples). */
    private static final String LAGOS = "org_lagostextilehub00001";
    private static final String MAMATEE = "org_mamateespantry000001";

    private static final GenericContainer<?> OPENFGA = OpenFgaTestContainer.create();
    private static final String STORE_ID;

    static {
        OPENFGA.start();
        STORE_ID = OpenFgaTestContainer.createStoreWithModel(OPENFGA);
    }

    private RelationshipWriter tuples;

    @DynamicPropertySource
    static void openFga(DynamicPropertyRegistry registry) {
        registry.add("at.security.fga.enabled", () -> "true");
        registry.add("at.security.fga.api-url", () -> OpenFgaTestContainer.apiUrl(OPENFGA));
        registry.add("at.security.fga.store-id", () -> STORE_ID);
        registry.add("at.security.fga.timeout", () -> "5s");
    }

    @BeforeEach
    void seedRelationships(@Autowired RelationshipWriter relationshipWriter,
                           @Autowired ResourceAccessChecker access) {
        this.tuples = relationshipWriter;
        assertThat(access.relationshipsEnabled()).isTrue();
        FgaObject platform = FgaObject.PLATFORM;
        FgaObject us = FgaObject.of("market", "US");
        FgaObject gb = FgaObject.of("market", "GB");
        FgaObject lagos = FgaObject.organization(LAGOS);
        FgaObject mamatee = FgaObject.organization(MAMATEE);
        tuples.grant(
                RelationshipTuple.of(platform, "platform", us), RelationshipTuple.of(platform, "platform", gb),
                RelationshipTuple.of(platform, "platform", lagos), RelationshipTuple.of(us, "market", lagos),
                RelationshipTuple.of(platform, "platform", mamatee), RelationshipTuple.of(gb, "market", mamatee),
                RelationshipTuple.of("user:fin-lagos", "finance", lagos),
                RelationshipTuple.of("user:cat-lagos", "catalog", lagos),
                RelationshipTuple.of("user:owner-mamatee", "owner", mamatee),
                RelationshipTuple.of("user:local-dev", "super_admin", platform),
                RelationshipTuple.of("user:fin-1", "finance", platform),
                RelationshipTuple.of("user:us-finance", "finance", us));
    }

    @Test
    void anotherOrganisationsPayoutsStayClosedEvenWithAForgedOrgClaim() {
        // fin-lagos is LAGOS's finance member; the token claims MAMATEE and carries the payout permissions.
        TestSupport.as(member("fin-lagos", MAMATEE, "seller.finance"));
        problem(send("GET", "/v1/payouts/balance", null, null), 403, "auth.relationship-denied");
        problem(send("GET", "/v1/payouts/statement", null, null), 403, "auth.relationship-denied");
        problem(send("GET", "/v1/payouts/label-charges", null, null), 403, "auth.relationship-denied");
        problem(send("GET", "/v1/payouts/provider-fees", null, null), 403, "auth.relationship-denied");
        problem(send("POST", "/v1/payouts/requests", Map.of("all", true), "forged-" + UUID.randomUUID()), 403,
                "auth.relationship-denied");

        // With its real claim it sees its own organisation's balance.
        TestSupport.as(member("fin-lagos", LAGOS, "seller.finance"));
        assertThat(ok(send("GET", "/v1/payouts/balance", null, null)).path("orgId").asString()).isEqualTo(LAGOS);

        // A catalog member of LAGOS never sees payouts, whatever permissions its token carries.
        TestSupport.as(member("cat-lagos", LAGOS, "seller.catalog"));
        problem(send("GET", "/v1/payouts/balance", null, null), 403, "auth.relationship-denied");
    }

    @Test
    void financeStaffGrantsAreScopedByMarket() {
        UUID mamateePayout = payoutRequest(MAMATEE);
        UUID lagosPayout = payoutRequest(LAGOS);

        Actor usFinance = new Actor("us-finance", PrincipalType.STAFF, Optional.empty(), Set.of("platform.finance"),
                Set.of("payments:read", "payouts:approve"));
        TestSupport.as(usFinance);
        assertThat(ok(send("GET", "/v1/payouts/balance?orgId=" + LAGOS, null, null)).path("orgId").asString())
                .isEqualTo(LAGOS);
        problem(send("GET", "/v1/payouts/balance?orgId=" + MAMATEE, null, null), 403, "auth.relationship-denied");
        assertThat(ok(send("GET", "/v1/payouts/requests?orgId=" + LAGOS, null, null))).isNotEmpty();
        problem(send("GET", "/v1/payouts/requests?orgId=" + MAMATEE, null, null), 403, "auth.relationship-denied");
        problem(send("GET", "/v1/payouts/requests", null, null), 403, "auth.relationship-denied");
        problem(send("GET", "/v1/ledger/balances", null, null), 403, "auth.relationship-denied");
        problem(send("POST", "/v1/payouts/requests/" + mamateePayout + "/hold", Map.of("reason", "Check"), null), 403,
                "auth.relationship-denied");
        assertThat(ok(send("POST", "/v1/payouts/requests/" + lagosPayout + "/hold", Map.of("reason", "Check"),
                null)).path("status").asString()).isEqualTo("HELD");
        assertThat(status(mamateePayout)).isEqualTo("REQUESTED");

        // Platform-wide Finance spans markets and organisations.
        TestSupport.as(TestSupport.FINANCE);
        assertThat(ok(send("GET", "/v1/payouts/requests", null, null))).isNotEmpty();
        ok(send("GET", "/v1/ledger/balances", null, null));
        assertThat(ok(send("POST", "/v1/payouts/requests/" + mamateePayout + "/hold", Map.of("reason", "Check"),
                null)).path("status").asString()).isEqualTo("HELD");
    }

    @Test
    void revokingAMembershipTakesEffectOnTheNextCheck() {
        TestSupport.as(member("owner-mamatee", MAMATEE, "seller.owner"));
        ok(send("GET", "/v1/payouts/balance", null, null));
        RelationshipTuple membership = RelationshipTuple.of("user:owner-mamatee", "owner",
                FgaObject.organization(MAMATEE));
        tuples.revoke(membership);
        try {
            problem(send("GET", "/v1/payouts/balance", null, null), 403, "auth.relationship-denied");
        } finally {
            tuples.grant(membership);
        }
    }

    private static Actor member(String subject, String org, String role) {
        return new Actor(subject, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of(role),
                Set.of("payouts:read", "payouts:request"));
    }

    /** A REQUESTED payout (inserted directly: the balance rules are covered by SettlementPayoutIT). */
    private UUID payoutRequest(String org) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("""
                insert into payout_request (id, org_id, currency, amount_minor, status, requested_by, requested_at,
                    request_day, version)
                values (:id, :org, 'USD', 5000, 'REQUESTED', 'someone', :now, :day, 0)
                """).param("id", id).param("org", org).param("now", Timestamp.from(now))
                .param("day", Date.valueOf(LocalDate.now().minusDays(Math.abs(id.hashCode() % 3000) + 1)))
                .update();
        return id;
    }

    private String status(UUID payout) {
        return jdbc.sql("select status from payout_request where id = :id").param("id", payout)
                .query(String.class).single();
    }
}
