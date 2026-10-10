package com.afrotransact.trustops.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ModerationItemTest {

    private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");
    private final SlaPolicy policy = new DefaultSlaPolicy(SlaTargets.defaults());
    private final ProductSubmission product = new ProductSubmission(UUID.randomUUID(), "AT0000ABCD", "org_ade",
            "SELLER", "Bitter kola that cures diabetes", "herbal");

    private static TriageHint hint(String risk) {
        return new TriageHint(risk, "Unapproved health claim in the title",
                List.of(new AiFlag("HEALTH_CLAIM", "Claims to cure a disease", List.of("title"))), "model-x");
    }

    @Test
    void queuedWithoutAiHasNormalPriorityAndTheDefaultSla() {
        ModerationItem item = ModerationItem.queue(UUID.randomUUID(), product, null, T0, policy);

        assertThat(item.status()).isEqualTo(ModerationStatus.PENDING);
        assertThat(item.triageStatus()).isEqualTo(TriageStatus.UNAVAILABLE);
        assertThat(item.priority()).isEqualTo(ModerationPriority.NORMAL);
        assertThat(item.sla().dueAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
    }

    @Test
    void highRiskHintRaisesPriorityAndShortensTheSla() {
        ModerationItem item = ModerationItem.queue(UUID.randomUUID(), product, hint("HIGH"), T0, policy);

        assertThat(item.priority()).isEqualTo(ModerationPriority.HIGH);
        assertThat(item.triageStatus()).isEqualTo(TriageStatus.COMPLETED);
        assertThat(item.sla().dueAt()).isEqualTo(T0.plus(Duration.ofHours(4)));

        ModerationItem late = ModerationItem.queue(UUID.randomUUID(), product, null, T0, policy)
                .withTriage(hint("critical"), policy);
        assertThat(late.priority()).isEqualTo(ModerationPriority.HIGH);
        assertThat(late.sla().dueAt()).isEqualTo(T0.plus(Duration.ofHours(4)));
    }

    @Test
    void escalationStartsTheEscalationTimerAndClearsTheAssignee() {
        Instant t1 = T0.plus(Duration.ofHours(2));
        ModerationItem item = ModerationItem.queue(UUID.randomUUID(), product, null, T0, policy)
                .assign("reviewer-1", T0);
        ModerationItem escalated = item.escalate("Needs legal review", t1, policy);

        assertThat(escalated.status()).isEqualTo(ModerationStatus.ESCALATED);
        assertThat(escalated.assignee()).isNull();
        assertThat(escalated.sla().dueAt()).isEqualTo(t1.plus(Duration.ofHours(8)));
        assertThatThrownBy(() -> escalated.escalate("again", t1, policy)).isInstanceOf(IllegalStateException.class);
        assertThat(escalated.withTriage(hint("HIGH"), policy).sla()).isEqualTo(escalated.sla());
    }

    @Test
    void rejectionNeedsAReasonCodeAndANoteForOther() {
        ModerationItem item = ModerationItem.queue(UUID.randomUUID(), product, null, T0, policy);

        assertThatThrownBy(() -> item.reject("r", null, "x", T0)).isInstanceOf(TrustRuleViolation.class);
        assertThatThrownBy(() -> item.reject("r", ModerationReasonCode.OTHER, null, T0))
                .isInstanceOf(TrustRuleViolation.class);
        ModerationItem rejected = item.reject("r", ModerationReasonCode.UNAPPROVED_HEALTH_CLAIM, "Remove the claim",
                T0);
        assertThat(rejected.status()).isEqualTo(ModerationStatus.REJECTED);
        assertThat(rejected.catalogNote()).isEqualTo("[UNAPPROVED_HEALTH_CLAIM] Unapproved health or medical claim: "
                + "Remove the claim");
    }

    @Test
    void decidedItemsAreFinal() {
        ModerationItem approved = ModerationItem.queue(UUID.randomUUID(), product, null, T0, policy)
                .approve("r", null, T0);
        assertThat(approved.open()).isFalse();
        assertThatThrownBy(() -> approved.reject("r", ModerationReasonCode.COUNTERFEIT, null, T0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.assign("r", T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.closeUpstream("LIVE", T0)).isInstanceOf(IllegalStateException.class);
    }
}
