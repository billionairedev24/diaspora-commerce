package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.ComplianceStatus;
import com.afrotransact.seller.port.StripeEvent;

/**
 * Stripe Connect compliance sync, Account health for sellers and the staff compliance console. Account health is
 * read-only: seller accounts are Stripe-collected, so sellers answer Stripe's requirements in its embedded components
 * ({@link StripeOnboardingService#accountSession(String)}), never through AfroTransact's API.
 */
public interface ComplianceService {

    /** Connect webhook handler (inside the webhook transaction). Returns the outcome recorded in the inbox. */
    String handle(StripeEvent event);

    /**
     * Owner / finance of the organisation; staff or service clients (SERVICE principal) with compliance:remind,
     * compliance:hold or sellers:enforce.
     */
    AccountHealthView accountHealth(String orgId);

    /** Staff: accounts, optionally only with open items or one status. */
    List<AccountHealthView> accounts(ComplianceStatus status, boolean openOnly);

    AccountHealthView account(String accountId);

    AccountHealthView remindNow(String accountId);

    AccountHealthView pausePayouts(String accountId, String note);

    AccountHealthView resumePayouts(String accountId, String note);

    /** Job: reminders 7, 3 and 1 day(s) before current_deadline (idempotent per threshold). */
    int sendDueReminders();
}
