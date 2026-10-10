package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class CutoffPolicyTest {

    private final CutoffDecisionPolicy policy = new DefaultCutoffDecisionPolicy();

    @Test
    void minimumMetConfirms() {
        Run run = Runs.withOrders(Runs.open(3, Capacity.UNLIMITED, CutoffPolicy.CANCEL), 3).cutOff(Runs.CUTOFF);
        assertThat(policy.decide(run, Runs.CUTOFF)).isEqualTo(CutoffDecision.confirm());
    }

    @Test
    void shortRunExtendsOnceThenCancels() {
        Run run = Runs.withOrders(Runs.open(3, Capacity.UNLIMITED, CutoffPolicy.EXTEND), 2).cutOff(Runs.CUTOFF);
        CutoffDecision first = policy.decide(run, Runs.CUTOFF);
        assertThat(first.outcome()).isEqualTo(CutoffDecision.Outcome.EXTEND);
        assertThat(first.reason()).isEqualTo(Run.REASON_MINIMUM_NOT_MET);

        Run extended = run.extendByPolicy(Runs.CUTOFF);
        Run cutAgain = extended.cutOff(extended.terms().cutoffAt());
        assertThat(policy.decide(cutAgain, extended.terms().cutoffAt()).outcome())
                .isEqualTo(CutoffDecision.Outcome.CANCEL);
    }

    @Test
    void cancelPolicyCancelsStraightAway() {
        Run run = Runs.open(3, Capacity.UNLIMITED, CutoffPolicy.CANCEL).cutOff(Runs.CUTOFF);
        assertThat(policy.decide(run, Runs.CUTOFF)).isEqualTo(CutoffDecision.cancel(Run.REASON_MINIMUM_NOT_MET));
    }

    @Test
    void anExtensionThatWouldAlreadyBeOverCancels() {
        Run run = Runs.open(3, Capacity.UNLIMITED, CutoffPolicy.EXTEND).cutOff(Runs.CUTOFF);
        assertThat(policy.decide(run, Runs.CUTOFF.plus(Duration.ofDays(8))).outcome())
                .isEqualTo(CutoffDecision.Outcome.CANCEL);
    }

    @Test
    void staffExtensionCountsAsTheOneExtension() {
        Run open = Runs.open(3, Capacity.UNLIMITED, CutoffPolicy.EXTEND);
        Run staffExtended = open.extend(Runs.CUTOFF.plus(Duration.ofDays(1)), Runs.WINDOW.shift(Duration.ofDays(1)),
                null, Runs.NOW);
        Run cut = staffExtended.cutOff(staffExtended.terms().cutoffAt());
        assertThat(policy.decide(cut, staffExtended.terms().cutoffAt()).outcome())
                .isEqualTo(CutoffDecision.Outcome.CANCEL);
    }
}
