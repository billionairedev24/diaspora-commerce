package com.afrotransact.trustops.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ClaimTest {

    private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");
    private final SlaPolicy policy = new DefaultSlaPolicy(SlaTargets.defaults());

    static ClaimDraft draft() {
        return new ClaimDraft(ClaimType.ITEM_NOT_RECEIVED, "ord-1", "AT-2026-7KQ3MD", "shp-1", "org_ade",
                "cust-1", 4_999, "usd", "Tracking says delivered but nothing arrived", List.of("asset_photo1"));
    }

    private Claim open() {
        return Claim.open(UUID.randomUUID(), "AZ-10001", draft(), T0, policy);
    }

    @Test
    void openStartsTheSellerResponseWindow() {
        Claim c = open();

        assertThat(c.status()).isEqualTo(ClaimStatus.AWAITING_SELLER);
        assertThat(c.currency()).isEqualTo("USD");
        assertThat(c.sla().dueAt()).isEqualTo(T0.plus(Duration.ofHours(72)));
        assertThat(c.evidence()).singleElement().satisfies(e -> {
            assertThat(e.assetId()).isEqualTo("asset_photo1");
            assertThat(e.party()).isEqualTo(PartyRole.CUSTOMER);
        });
    }

    @Test
    void sellerResponseMovesToReviewWithDecisionTimer() {
        Instant t1 = T0.plus(Duration.ofHours(5));
        Claim reviewed = open().sellerRespond("seller-1", "Shipped with UPS, see proof", List.of("asset_pod"), t1,
                policy);

        assertThat(reviewed.status()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(reviewed.sellerResponse()).isEqualTo("Shipped with UPS, see proof");
        assertThat(reviewed.sla().dueAt()).isEqualTo(t1.plus(Duration.ofDays(5)));
        assertThat(reviewed.evidence()).extracting(Evidence::party).containsExactly(PartyRole.CUSTOMER,
                PartyRole.SELLER);
    }

    @Test
    void lapsedSellerWindowGoesToReviewOnlyAfterTheDeadline() {
        Claim c = open();
        assertThatThrownBy(() -> c.sellerWindowLapsed(T0.plus(Duration.ofHours(10)), policy))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("still open");

        Claim lapsed = c.sellerWindowLapsed(T0.plus(Duration.ofHours(72)), policy);
        assertThat(lapsed.status()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(lapsed.sellerResponse()).isNull();
    }

    @Test
    void refundDecisionIsBoundedByTheClaimedAmount() {
        Claim review = open().sellerRespond("seller-1", "Sorry", List.of(), T0, policy);

        assertThatThrownBy(() -> review.decideRefund("staff-1", 5_000, "re_1", null, T0))
                .isInstanceOf(TrustRuleViolation.class);
        Claim refunded = review.decideRefund("staff-1", 4_999, "re_1", "Carrier lost it", T0);
        assertThat(refunded.status()).isEqualTo(ClaimStatus.RESOLVED_REFUND);
        assertThat(refunded.outcome()).isEqualTo(ClaimOutcome.REFUND);
        assertThat(refunded.refundMinor()).isEqualTo(4_999L);
        assertThat(refunded.refundId()).isEqualTo("re_1");
    }

    @Test
    void denialNeedsAReason() {
        Claim review = open().sellerRespond("seller-1", "Delivered", List.of(), T0, policy);

        assertThatThrownBy(() -> review.deny("staff-1", " ", T0)).isInstanceOf(TrustRuleViolation.class);
        assertThat(review.deny("staff-1", "Proof of delivery with photo", T0).status())
                .isEqualTo(ClaimStatus.RESOLVED_DENIED);
    }

    @Test
    void claimsCannotBeDecidedWhileWaitingForTheSeller() {
        assertThatThrownBy(() -> open().deny("staff-1", "no", T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> open().decideRefund("staff-1", 100, "re", null, T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void escalationResetsTheTimerAndAllowsADecision() {
        Instant t1 = T0.plus(Duration.ofHours(1));
        Claim escalated = open().escalate("Seller unreachable", t1, policy);

        assertThat(escalated.status()).isEqualTo(ClaimStatus.ESCALATED);
        assertThat(escalated.escalated()).isTrue();
        assertThat(escalated.sla().dueAt()).isEqualTo(t1.plus(Duration.ofHours(48)));
        assertThatThrownBy(() -> escalated.escalate("again", t1, policy)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> escalated.sellerRespond("s", "late", List.of(), t1, policy))
                .isInstanceOf(IllegalStateException.class);
        assertThat(escalated.decideRefund("staff-1", 1_000, "re_2", null, t1).status())
                .isEqualTo(ClaimStatus.RESOLVED_REFUND);
    }

    @Test
    void closedClaimsAreFinal() {
        Claim withdrawn = open().withdraw("cust-1", T0);
        assertThat(withdrawn.status()).isEqualTo(ClaimStatus.WITHDRAWN);
        assertThatThrownBy(() -> withdrawn.addEvidence(PartyRole.CUSTOMER, "cust-1", "a2", null, T0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withdrawn.escalate("x", T0, policy)).isInstanceOf(IllegalStateException.class);
        for (ClaimStatus terminal : EnumSet.of(ClaimStatus.RESOLVED_REFUND, ClaimStatus.RESOLVED_DENIED,
                ClaimStatus.WITHDRAWN)) {
            assertThat(terminal.next()).isEmpty();
            assertThat(terminal.open()).isFalse();
        }
    }

    @Test
    void transitionTableMatchesTheStateMachine() {
        assertThat(ClaimStatus.AWAITING_SELLER.next()).containsExactlyInAnyOrder(ClaimStatus.UNDER_REVIEW,
                ClaimStatus.ESCALATED, ClaimStatus.WITHDRAWN);
        assertThat(ClaimStatus.UNDER_REVIEW.next()).containsExactlyInAnyOrder(ClaimStatus.ESCALATED,
                ClaimStatus.RESOLVED_REFUND, ClaimStatus.RESOLVED_DENIED, ClaimStatus.WITHDRAWN);
        assertThat(ClaimStatus.ESCALATED.next()).containsExactlyInAnyOrder(ClaimStatus.RESOLVED_REFUND,
                ClaimStatus.RESOLVED_DENIED, ClaimStatus.WITHDRAWN);
        assertThat(ClaimStatus.AWAITING_SELLER.decidable()).isFalse();
        assertThat(ClaimStatus.UNDER_REVIEW.decidable()).isTrue();
    }

    @Test
    void evidenceIsDeduplicatedAndCapped() {
        Claim c = open().addEvidence(PartyRole.CUSTOMER, "cust-1", "asset_photo1", null, T0);
        assertThat(c.evidence()).hasSize(1);
        for (int i = 1; i < Claim.MAX_EVIDENCE; i++) {
            c = c.addEvidence(PartyRole.STAFF, "staff", "asset_" + i, null, T0);
        }
        Claim full = c;
        assertThat(full.evidence()).hasSize(Claim.MAX_EVIDENCE);
        assertThatThrownBy(() -> full.addEvidence(PartyRole.SELLER, "s", "one-more", null, T0))
                .isInstanceOf(TrustRuleViolation.class);
    }

    @Test
    void openValidatesTheDraft() {
        ClaimDraft bad = new ClaimDraft(ClaimType.WRONG_ITEM, "ord-1", null, null, "org", "c", 0, "USD", "x",
                List.of());
        assertThatThrownBy(() -> Claim.open(UUID.randomUUID(), "AZ-1", bad, T0, policy))
                .isInstanceOf(TrustRuleViolation.class).hasMessageContaining("positive");
        ClaimDraft noType = new ClaimDraft(null, "ord-1", null, null, "org", "c", 10, "USD", "x", List.of());
        assertThatThrownBy(() -> Claim.open(UUID.randomUUID(), "AZ-1", noType, T0, policy))
                .isInstanceOf(TrustRuleViolation.class);
    }
}
