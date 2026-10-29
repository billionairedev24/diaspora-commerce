package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Our mirror of a Stripe connected account: requirements, derived status, payout hold and listing visibility.
 * Stripe is the source of truth; we apply its snapshots in order of {@code requirementsVersion} (event time).
 */
public record ConnectAccount(String accountId, String orgId, RequirementsSnapshot requirements,
                             ComplianceStatus status, long requirementsVersion, boolean payoutsPaused,
                             PayoutHold holdReason, boolean listingsHidden, boolean deauthorized, int remindersSent,
                             Instant lastReminderAt, Map<String, String> persons, String externalAccountStatus,
                             Instant updatedAt, long version) {

    public ConnectAccount {
        Objects.requireNonNull(accountId, "accountId");
        requirements = requirements == null ? RequirementsSnapshot.empty() : requirements;
        persons = persons == null ? Map.of() : Map.copyOf(persons);
    }

    public static ConnectAccount open(String accountId, String orgId, Instant now) {
        RequirementsSnapshot empty = RequirementsSnapshot.empty();
        return new ConnectAccount(accountId, orgId, empty, ComplianceStatus.VERIFYING, 0, false, null,
                false, false, 0, null, Map.of(), null, now, 0);
    }

    /**
     * Applies a Stripe snapshot and decides enforcement. Older versions are ignored by the caller. A snapshot without
     * the payout bank account (a payload that did not list external accounts) keeps the one already mirrored.
     */
    public ComplianceUpdate apply(RequirementsSnapshot snapshot, long eventVersion, Instant now) {
        RequirementsSnapshot next = snapshot.payoutBank() == null && requirements.payoutBank() != null
                ? snapshot.withPayoutBank(requirements.payoutBank()) : snapshot;
        ComplianceStatus nextStatus = ComplianceStatusPolicy.derive(next, now);
        List<String> fresh = new ArrayList<>(next.open());
        fresh.removeAll(requirements.open());
        Enforcement payouts = Enforcement.NONE;
        boolean paused = payoutsPaused;
        PayoutHold hold = holdReason;
        if (!next.pastDue().isEmpty() && !payoutsPaused) {
            payouts = Enforcement.PAUSE_PAYOUTS;
            paused = true;
            hold = PayoutHold.AUTO_PAST_DUE;
        } else if (next.pastDue().isEmpty() && payoutsPaused && holdReason == PayoutHold.AUTO_PAST_DUE) {
            payouts = Enforcement.RESUME_PAYOUTS;
            paused = false;
            hold = null;
        }
        Enforcement listings = Enforcement.NONE;
        boolean hidden = listingsHidden;
        if (!next.chargesEnabled() && requirements.chargesEnabled() && !listingsHidden) {
            listings = Enforcement.HIDE_LISTINGS;
            hidden = true;
        } else if (next.chargesEnabled() && listingsHidden && !deauthorized) {
            listings = Enforcement.RESTORE_LISTINGS;
            hidden = false;
        }
        ConnectAccount updated = new ConnectAccount(accountId, orgId, next, nextStatus,
                Math.max(requirementsVersion, eventVersion), paused, hold, hidden, deauthorized, remindersSent,
                lastReminderAt, persons, externalAccountStatus, now, version);
        return new ComplianceUpdate(updated, !next.sameRequirements(requirements), nextStatus != status, fresh,
                payouts, listings);
    }

    public ConnectAccount withCapability(String capability, String capabilityStatus, Instant now) {
        return new ConnectAccount(accountId, orgId, requirements.withCapability(capability, capabilityStatus), status,
                requirementsVersion, payoutsPaused, holdReason, listingsHidden, deauthorized, remindersSent,
                lastReminderAt, persons, externalAccountStatus, now, version);
    }

    public ConnectAccount withPerson(String personId, String verificationStatus, Instant now) {
        Map<String, String> p = new TreeMap<>(persons);
        p.put(personId, verificationStatus == null ? "unverified" : verificationStatus);
        return new ConnectAccount(accountId, orgId, requirements, status, requirementsVersion, payoutsPaused,
                holdReason, listingsHidden, deauthorized, remindersSent, lastReminderAt, p, externalAccountStatus,
                now, version);
    }

    /** account.external_account.created / updated: the payout bank account Stripe holds now. */
    public ConnectAccount withPayoutBank(PayoutBank bank, Instant now) {
        return new ConnectAccount(accountId, orgId, requirements.withPayoutBank(bank), status, requirementsVersion,
                payoutsPaused, holdReason, listingsHidden, deauthorized, remindersSent, lastReminderAt, persons,
                bank == null ? null : bank.status(), now, version);
    }

    /** account.external_account.deleted: forgets the mirrored bank account when it is the one deleted. */
    public ConnectAccount withoutPayoutBank(String externalAccountId, Instant now) {
        PayoutBank current = requirements.payoutBank();
        if (current == null || current.id() != null && !current.id().equals(externalAccountId)) {
            return this;
        }
        return withPayoutBank(null, now);
    }

    public ConnectAccount reminded(Instant now) {
        return new ConnectAccount(accountId, orgId, requirements, status, requirementsVersion, payoutsPaused,
                holdReason, listingsHidden, deauthorized, remindersSent + 1, now, persons, externalAccountStatus, now,
                version);
    }

    /** Staff pause (Ops, Finance, Trust & Safety). Returns this unchanged when already paused. */
    public ConnectAccount pauseManually(Instant now) {
        if (payoutsPaused) {
            return this;
        }
        return withHold(true, PayoutHold.MANUAL, now);
    }

    /** Staff release (Trust & Safety). Refused while Stripe still reports past-due requirements. */
    public ConnectAccount resumeManually(Instant now) {
        if (!payoutsPaused) {
            return this;
        }
        if (!requirements.pastDue().isEmpty() || deauthorized) {
            throw new SellerStateConflict("seller.payout-hold-required",
                    "Payouts stay paused while Stripe reports past-due requirements or the account is disconnected");
        }
        return withHold(false, null, now);
    }

    /** The seller disconnected the platform: restrict everything. */
    public ComplianceUpdate deauthorize(Instant now) {
        RequirementsSnapshot r = requirements;
        RequirementsSnapshot off = new RequirementsSnapshot(r.currentlyDue(), r.eventuallyDue(), r.pastDue(),
                r.pendingVerification(), r.errors(), r.currentDeadline(), "platform_deauthorized", false, false,
                r.detailsSubmitted(), r.individualVerificationStatus(), r.capabilities());
        ConnectAccount next = new ConnectAccount(accountId, orgId, off, ComplianceStatus.RESTRICTED,
                requirementsVersion, true, payoutsPaused ? holdReason : PayoutHold.DEAUTHORIZED, true, true,
                remindersSent, lastReminderAt, persons, externalAccountStatus, now, version);
        return new ComplianceUpdate(next, true, status != ComplianceStatus.RESTRICTED, List.of(),
                payoutsPaused ? Enforcement.NONE : Enforcement.PAUSE_PAYOUTS,
                listingsHidden ? Enforcement.NONE : Enforcement.HIDE_LISTINGS);
    }

    public boolean hasOpenItems() {
        return !requirements.open().isEmpty();
    }

    private ConnectAccount withHold(boolean paused, PayoutHold hold, Instant now) {
        return new ConnectAccount(accountId, orgId, requirements, status, requirementsVersion, paused, hold,
                listingsHidden, deauthorized, remindersSent, lastReminderAt, persons, externalAccountStatus, now,
                version);
    }
}
