package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.ConnectAccount;
import com.afrotransact.seller.domain.OrgSuspension;
import com.afrotransact.seller.domain.Organisation;

/**
 * An organisation's enforcement state: the Trust and Safety suspension hold (null when not suspended) next to the
 * Stripe compliance holds on its connected account (null when it has none yet).
 *
 * @param history suspensions, newest first
 */
public record EnforcementView(Organisation organisation, OrgSuspension suspension, ConnectAccount account,
                              List<OrgSuspension> history) {

    public EnforcementView {
        history = List.copyOf(history);
    }

    public boolean suspended() {
        return suspension != null;
    }

    /** A Stripe compliance payout hold (past due, staff hold, disconnected account) independent of suspension. */
    public boolean compliancePayoutHold() {
        return account != null && account.payoutsPaused();
    }

    /** Listings hidden by Stripe compliance (charges disabled, disconnected account) independent of suspension. */
    public boolean complianceListingsHidden() {
        return account != null && account.listingsHidden();
    }

    /** Payouts are held for any reason. */
    public boolean payoutsPaused() {
        return suspended() || compliancePayoutHold();
    }

    /** Listings are hidden for any reason. */
    public boolean listingsHidden() {
        return suspended() || complianceListingsHidden();
    }
}
