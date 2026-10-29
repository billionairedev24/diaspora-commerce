package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** A refund Stripe would not take back from a seller's transfer: the debt, its recovery and its four-eyes closure. */
class TransferRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String REASON = "The seller wired the money on Friday";

    private static TransferRecovery open(long minor) {
        return TransferRecovery.open(UUID.randomUUID(), UUID.randomUUID(), "ORD-1", "org_s", UUID.randomUUID(),
                "tr_1", Money.of(minor, "USD"), "balance_insufficient", "Insufficient funds in the account",
                UUID.randomUUID(), NOW);
    }

    private static List<String> lines(Journal j) {
        return j.lines().stream().map(l -> l.side() + " " + l.account().display() + " " + l.amount().minor() + " "
                + l.orgId() + " " + l.orderId()).toList();
    }

    @Test
    void openingMovesTheRefundDebitFromTheOrderToTheOrganisationsUnattributedPayable() {
        TransferRecovery r = open(1840);
        Journal j = Postings.transferRecoveryOpened(UUID.randomUUID(), NOW, "k", r);

        assertThat(j.type()).isEqualTo(JournalType.TRANSFER_RECOVERY_OPENED);
        assertThat(j.orderId()).as("the debt is the organisation's, not an order's").isNull();
        assertThat(lines(j)).containsExactly("DEBIT 2000:org_s 1840 org_s null", "CREDIT 2000:org_s 1840 org_s ORD-1");
        assertThat(Journal.imbalance(j.lines())).isEmpty();
        assertThat(j.description()).contains("tr_1", "balance_insufficient", "org_s");
        assertThat(r.status()).isEqualTo(TransferRecoveryStatus.OPEN);
        assertThat(r.outstanding()).isEqualTo(Money.of(1840, "USD"));
    }

    @Test
    void laterTransfersRecoverItInPartsUntilItIsRecovered() {
        TransferRecovery r = open(1000).settledBy(Money.of(400, "USD"), NOW);
        assertThat(r.status()).isEqualTo(TransferRecoveryStatus.OPEN);
        assertThat(r.outstanding()).isEqualTo(Money.of(600, "USD"));

        TransferRecovery done = r.settledBy(Money.of(600, "USD"), NOW);
        assertThat(done.status()).isEqualTo(TransferRecoveryStatus.RECOVERED);
        assertThat(done.outstanding().isZero()).isTrue();
        assertThat(done.recovered()).isEqualTo(Money.of(1000, "USD"));
    }

    @Test
    void settlementJournalTakesTheAmountFromTheOrderBeingTransferred() {
        Journal j = Postings.transferRecoverySettled(UUID.randomUUID(), NOW, "k", "ORD-2", open(1000),
                Money.of(400, "USD"));
        assertThat(j.type()).isEqualTo(JournalType.TRANSFER_RECOVERY_SETTLED);
        assertThat(lines(j)).containsExactly("DEBIT 2000:org_s 400 org_s ORD-2", "CREDIT 2000:org_s 400 org_s null");
        assertThat(Journal.imbalance(j.lines())).isEmpty();
    }

    @Test
    void cannotRecoverMoreThanIsOutstanding() {
        assertThatThrownBy(() -> open(1000).settledBy(Money.of(1001, "USD"), NOW))
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> open(1000).settledBy(Money.zero("USD"), NOW))
                .isInstanceOf(PaymentsRuleViolation.class);
    }

    @Test
    void writeOffNeedsAReasonAndADifferentApprover() {
        TransferRecovery open = open(1840);
        assertThatThrownBy(() -> open.propose(RecoveryResolution.WRITE_OFF, "too short", null, "alice", NOW))
                .isInstanceOf(PaymentsRuleViolation.class);

        TransferRecovery proposed = open.propose(RecoveryResolution.WRITE_OFF, "Seller closed the account and "
                + "cannot be reached", null, "alice", NOW);
        assertThat(proposed.pending().kind()).isEqualTo(RecoveryResolution.WRITE_OFF);
        assertThat(proposed.status()).as("nothing changes until a second person approves")
                .isEqualTo(TransferRecoveryStatus.OPEN);
        assertThatThrownBy(() -> proposed.approve("alice", null, NOW)).isInstanceOf(PaymentsForbidden.class)
                .hasMessageContaining("different person");

        TransferRecovery written = proposed.approve("bob", "Approved", NOW);
        assertThat(written.status()).isEqualTo(TransferRecoveryStatus.WRITTEN_OFF);
        assertThat(written.resolved()).isEqualTo(Money.of(1840, "USD"));
        assertThat(written.outstanding().isZero()).isTrue();
        assertThat(written.pending()).isNull();
        assertThat(written.decidedBy()).isEqualTo("bob");

        Journal j = Postings.transferRecoveryWrittenOff(UUID.randomUUID(), NOW, "k", written, Money.of(1840, "USD"));
        assertThat(lines(j)).containsExactly("DEBIT 5300 1840 org_s null", "CREDIT 2000:org_s 1840 org_s null");
        assertThat(Journal.imbalance(j.lines())).isEmpty();
    }

    @Test
    void moneyReceivedOutsideStripeNeedsTheBankReference() {
        TransferRecovery open = open(1840);
        assertThatThrownBy(() -> open.propose(RecoveryResolution.RECOVERED, REASON, " ", "alice", NOW))
                .isInstanceOf(PaymentsRuleViolation.class).hasMessageContaining("bank reference");

        TransferRecovery received = open.propose(RecoveryResolution.RECOVERED, REASON, "WIRE-991", "alice", NOW)
                .approve("bob", null, NOW);
        assertThat(received.status()).isEqualTo(TransferRecoveryStatus.RECOVERED);
        assertThat(received.resolution()).isEqualTo(RecoveryResolution.RECOVERED);
        assertThat(received.resolutionRef()).isEqualTo("WIRE-991");

        Journal j = Postings.transferRecoveryReceived(UUID.randomUUID(), NOW, "k", received, Money.of(1840, "USD"),
                "WIRE-991");
        assertThat(lines(j)).containsExactly("DEBIT 1050 1840 org_s null", "CREDIT 2000:org_s 1840 org_s null");
        assertThat(j.description()).contains("WIRE-991");
    }

    @Test
    void closingAfterPartialRecoveryResolvesOnlyWhatIsLeft() {
        TransferRecovery partly = open(1000).settledBy(Money.of(400, "USD"), NOW);
        TransferRecovery written = partly.propose(RecoveryResolution.WRITE_OFF, "Seller is insolvent, do not chase",
                null, "alice", NOW).approve("bob", null, NOW);
        assertThat(written.resolved()).isEqualTo(Money.of(600, "USD"));
        assertThat(written.recovered()).isEqualTo(Money.of(400, "USD"));
        assertThat(written.outstanding().isZero()).isTrue();
    }

    @Test
    void rejectingReturnsItToPlainOpenAndNeedsAReason() {
        TransferRecovery proposed = open(1840).propose(RecoveryResolution.WRITE_OFF, "Seller is insolvent, do not "
                + "chase", null, "alice", NOW);
        assertThatThrownBy(() -> proposed.reject("bob", " ", NOW)).isInstanceOf(PaymentsRuleViolation.class);
        TransferRecovery back = proposed.reject("bob", "Chase the seller first", NOW);
        assertThat(back.status()).isEqualTo(TransferRecoveryStatus.OPEN);
        assertThat(back.pending()).isNull();
        assertThat(back.decisionNote()).isEqualTo("Chase the seller first");
        // The proposer may withdraw their own proposal, and a new one can follow.
        assertThat(proposed.reject("alice", "Wrong recovery", NOW).pending()).isNull();
        assertThat(back.propose(RecoveryResolution.RECOVERED, REASON, "WIRE-1", "carol", NOW).pending()).isNotNull();
    }

    @Test
    void aClosedRecoveryCannotBeProposedOrSettledAgain() {
        TransferRecovery written = open(1840).propose(RecoveryResolution.WRITE_OFF, "Seller is insolvent, do not "
                + "chase", null, "alice", NOW).approve("bob", null, NOW);
        assertThatThrownBy(() -> written.propose(RecoveryResolution.WRITE_OFF, "Seller is insolvent again", null,
                "alice", NOW)).isInstanceOf(PaymentsStateConflict.class);
        assertThatThrownBy(() -> written.settledBy(Money.of(1, "USD"), NOW))
                .isInstanceOf(PaymentsStateConflict.class);
        assertThatThrownBy(() -> open(1840).approve("bob", null, NOW)).isInstanceOf(PaymentsStateConflict.class)
                .hasMessageContaining("waits for approval");
    }

    @Test
    void onlyOneProposalWaitsAtATime() {
        TransferRecovery proposed = open(1840).propose(RecoveryResolution.WRITE_OFF, "Seller is insolvent, do not "
                + "chase", null, "alice", NOW);
        assertThatThrownBy(() -> proposed.propose(RecoveryResolution.RECOVERED, REASON, "WIRE-1", "carol", NOW))
                .isInstanceOf(PaymentsStateConflict.class);
    }
}
