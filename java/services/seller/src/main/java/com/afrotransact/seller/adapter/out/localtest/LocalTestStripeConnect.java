package com.afrotransact.seller.adapter.out.localtest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.seller.domain.PayoutBank;
import com.afrotransact.seller.domain.RequirementsSnapshot;
import com.afrotransact.seller.port.AccountSession;
import com.afrotransact.seller.port.CreateConnectedAccount;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.StripeConnectGateway;

/**
 * Local stand-in for Stripe Connect (local profile without a Stripe key only; see LocalTestStripePolicy). Connected
 * accounts get {@code acct_local_…} ids, Account Sessions are flagged {@code localTest} so Seller Center shows its
 * labelled local verification instead of Stripe's component, and an account reads as verified (details submitted,
 * charges and payouts enabled, with Stripe's test bank account ••6789 on file, as Stripe's own onboarding would
 * collect it) once the applicant completes that local screen. No call leaves the machine.
 */
public class LocalTestStripeConnect implements StripeConnectGateway {

    static final String ACCOUNT_PREFIX = "acct_local_";
    static final String SESSION_SECRET_PREFIX = "local_test_session_";
    static final String TEST_BANK = "STRIPE TEST BANK (local)";
    private static final Duration SESSION_TTL = Duration.ofHours(1);

    private final Clock clock;

    public LocalTestStripeConnect(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String createAccount(CreateConnectedAccount request) {
        return ACCOUNT_PREFIX + UUID.nameUUIDFromBytes(request.orgId().getBytes(StandardCharsets.UTF_8)).toString().replace("-", "")
                .substring(0, 16);
    }

    @Override
    public AccountSession createAccountSession(String accountId, Set<String> components) {
        local(accountId);
        return new AccountSession(accountId, SESSION_SECRET_PREFIX + UUID.randomUUID(),
                Instant.now(clock).plus(SESSION_TTL), true);
    }

    @Override
    public RequirementsSnapshot retrieveAccount(String accountId) {
        local(accountId);
        return new RequirementsSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), null, null, true, true,
                true, "verified", Map.of("card_payments", "active", "transfers", "active"),
                new PayoutBank("ba_local_" + accountId.substring(ACCOUNT_PREFIX.length()), TEST_BANK, "6789", "new"));
    }

    /** Accounts created by real Stripe can't be served by the stand-in. */
    private static void local(String accountId) {
        if (accountId == null || !accountId.startsWith(ACCOUNT_PREFIX)) {
            throw new GatewayException("seller.stripe-local-test", "Account " + accountId
                    + " was created with Stripe; configure STRIPE_SECRET_KEY to use it", false, null);
        }
    }
}
