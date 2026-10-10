package com.afrotransact.seller.application;

import java.util.List;
import java.util.Map;

import com.afrotransact.seller.domain.ComplianceStatus;
import com.afrotransact.seller.port.StripeEvent;

/** Stripe Connect compliance sync, Account health for sellers and the staff compliance console. */
public interface ComplianceService {

    /** Connect webhook handler (inside the webhook transaction). Returns the outcome recorded in the inbox. */
    String handle(StripeEvent event);

    /**
     * Owner / finance of the organisation; staff or service clients (SERVICE principal) with compliance:remind,
     * compliance:hold or sellers:enforce.
     */
    AccountHealthView accountHealth(String orgId);

    /** Validates an answer, uploads documents to Stripe Files, forwards to Accounts / Persons update. */
    AccountHealthView answer(String orgId, String requirement, Map<String, String> values, String ip,
                             String userAgent);

    /** Staff: accounts, optionally only with open items or one status. */
    List<AccountHealthView> accounts(ComplianceStatus status, boolean openOnly);

    AccountHealthView account(String accountId);

    AccountHealthView remindNow(String accountId);

    AccountHealthView pausePayouts(String accountId, String note);

    AccountHealthView resumePayouts(String accountId, String note);

    /** Job: reminders 7, 3 and 1 day(s) before current_deadline (idempotent per threshold). */
    int sendDueReminders();
}
