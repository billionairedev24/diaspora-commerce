package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.port.ConnectedAccountRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ConnectedAccountServiceImpl implements ConnectedAccountService {

    private final ConnectedAccountRepository accounts;
    private final PaymentsAccess access;
    private final Clock clock;

    ConnectedAccountServiceImpl(ConnectedAccountRepository accounts, PaymentsAccess access, Clock clock) {
        this.accounts = accounts;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void register(String orgId, String stripeAccountId) {
        access.accountRegistrar();
        if (stripeAccountId == null || !stripeAccountId.matches("acct_[A-Za-z0-9]{6,64}")) {
            throw new PaymentsRuleViolation("payments.invalid-account", "Not a Stripe connected account id",
                    Map.of("stripeAccountId", "Must look like acct_…"));
        }
        accounts.upsert(orgId, stripeAccountId, Instant.now(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public String accountOf(String orgId) {
        access.accountRegistrar();
        return accounts.find(orgId).orElseThrow(() -> new NotFoundException("payments.no-connected-account",
                "Organisation " + orgId + " has no connected account"));
    }
}
