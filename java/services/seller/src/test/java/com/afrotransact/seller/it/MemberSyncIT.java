package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.seller.application.OrgMemberSyncService;
import com.afrotransact.seller.application.OrgMemberSyncService.RoleChange;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Offering;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S236: seller's member list mirrors identity (source of truth); S215: organisations that sell products and services
 * publish kind BOTH.
 */
class MemberSyncIT extends SellerIntegrationTest {

    OrgMemberSyncService sync;

    @BeforeEach
    void wireSync(@Autowired OrgMemberSyncService memberSync) {
        this.sync = memberSync;
    }

    private String role(String org, String user) {
        return jdbc.sql("select role_key from org_member where org_id = :o and user_id = :u").param("o", org)
                .param("u", user).query(String.class).optional().orElse(null);
    }

    @Test
    void identityRoleEventsKeepTheMemberListConsistent() {
        String owner = unique("sync-owner");
        String org = approvedSeller(owner, "seller_essentials", Cadence.MONTHLY);
        String user = UUID.randomUUID().toString();
        Instant t0 = Instant.now();

        UUID granted = UUID.randomUUID();
        assertThat(sync.granted(new RoleChange(granted, user, "seller.catalog", org, owner, t0))).isTrue();
        assertThat(role(org, user)).isEqualTo("seller.catalog");
        // Redelivery (same event id) is a no-op.
        assertThat(sync.granted(new RoleChange(granted, user, "seller.catalog", org, owner, t0))).isFalse();

        // A role change arrives as revoked(old) then granted(new): the member keeps the new role.
        assertThat(sync.revoked(new RoleChange(UUID.randomUUID(), user, "seller.catalog", org, owner,
                t0.plusSeconds(10)))).isTrue();
        assertThat(sync.granted(new RoleChange(UUID.randomUUID(), user, "seller.finance", org, owner,
                t0.plusSeconds(10)))).isTrue();
        assertThat(role(org, user)).isEqualTo("seller.finance");
        // The new role gives access to billing reads through the member list (AccessGuard).
        TestActors.as(TestActors.applicant(user));
        assertThat(get("/v1/orgs/" + org + "/billing").status()).isEqualTo(200);

        // A stale grant (older than the stored change) and a revoke of a role the member no longer holds are ignored.
        assertThat(sync.granted(new RoleChange(UUID.randomUUID(), user, "seller.catalog", org, owner,
                t0.plusSeconds(5)))).isFalse();
        assertThat(sync.revoked(new RoleChange(UUID.randomUUID(), user, "seller.catalog", org, owner,
                t0.plusSeconds(20)))).isFalse();
        assertThat(role(org, user)).isEqualTo("seller.finance");

        // Removal from the team.
        assertThat(sync.revoked(new RoleChange(UUID.randomUUID(), user, "seller.finance", org, owner,
                t0.plusSeconds(30)))).isTrue();
        assertThat(role(org, user)).isNull();
        assertThat(get("/v1/orgs/" + org + "/billing").status()).isEqualTo(403);

        // Unknown organisations (e.g. AfroTransact Retail) and non-organisation roles are ignored.
        assertThat(sync.granted(new RoleChange(UUID.randomUUID(), user, "seller.catalog", "org_not_here", owner,
                t0))).isFalse();
        assertThat(sync.granted(new RoleChange(UUID.randomUUID(), user, "account.customer", org, owner, t0)))
                .isFalse();
        // The owner granted by identity on approval stays the owner.
        assertThat(role(org, owner)).isEqualTo("seller.owner");
    }

    @Test
    void organisationsOfferingProductsAndServicesPublishKindBoth() {
        String both = approvedSeller(unique("both"), "seller_essentials", Cadence.MONTHLY, Offering.BOTH);
        var approved = lastEvent("seller.org.approved", both);
        assertThat(approved.path("kind").asString()).isEqualTo("BOTH");
        assertThat(approved.path("primaryKind").asString()).isEqualTo("SELLER");
        assertThat(approved.path("offering").asString()).isEqualTo("BOTH");

        String products = approvedSeller(unique("products"), "seller_essentials", Cadence.MONTHLY);
        var plain = lastEvent("seller.org.updated", products);
        assertThat(plain.path("kind").asString()).isEqualTo("SELLER");
        assertThat(plain.path("offering").asString()).isEqualTo("PRODUCTS");
        TestActors.reset();
        assertThat(get("/v1/orgs/" + both).body().path("offering").asString()).isEqualTo("BOTH");
    }
}
