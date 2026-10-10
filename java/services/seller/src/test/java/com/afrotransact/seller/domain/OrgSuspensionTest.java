package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrgSuspensionTest {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    private static Organisation org(OrgStatus status) {
        return new Organisation("org_1", OrgKind.SELLER, "Store LLC", "Store", "US", null, status, NOW, NOW, 0);
    }

    @Test
    void opensOnApprovedOrganisationsAndRemembersThePreviousStatus() {
        OrgSuspension s = OrgSuspension.open(UUID.randomUUID(), org(OrgStatus.APPROVED), SuspensionReason.COUNTERFEIT,
                "  Fake prints ", "trust-ops-service", NOW);
        assertThat(s.active()).isTrue();
        assertThat(s.previousStatus()).isEqualTo(OrgStatus.APPROVED);
        assertThat(s.summary()).isEqualTo("[COUNTERFEIT] Fake prints");

        OrgSuspension lifted = s.reinstate("sara", "ok", NOW.plusSeconds(60));
        assertThat(lifted.active()).isFalse();
        assertThat(lifted.reinstatedBy()).isEqualTo("sara");
        assertThatThrownBy(() -> lifted.reinstate("sara", null, NOW)).isInstanceOf(SellerStateConflict.class);
    }

    @Test
    void enforcesReasonNoteAndStatus() {
        assertThatThrownBy(() -> OrgSuspension.open(UUID.randomUUID(), org(OrgStatus.APPROVED), null, "x", "a", NOW))
                .isInstanceOf(SellerRuleViolation.class);
        assertThatThrownBy(() -> OrgSuspension.open(UUID.randomUUID(), org(OrgStatus.APPROVED),
                SuspensionReason.OTHER, " ", "a", NOW)).isInstanceOf(SellerRuleViolation.class);
        assertThatThrownBy(() -> OrgSuspension.open(UUID.randomUUID(), org(OrgStatus.PENDING_REVIEW),
                SuspensionReason.FRAUD_RISK, null, "a", NOW)).isInstanceOf(SellerStateConflict.class);
        assertThatThrownBy(() -> OrgSuspension.open(UUID.randomUUID(), org(OrgStatus.APPROVED),
                SuspensionReason.FRAUD_RISK, "x".repeat(1001), "a", NOW)).isInstanceOf(SellerRuleViolation.class);
    }
}
