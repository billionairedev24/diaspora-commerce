package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.StatementLine;
import com.afrotransact.payments.port.LedgerRepository;
import org.springframework.stereotype.Service;

@Service
class SellerStatementServiceImpl implements SellerStatementService {

    private static final Duration DEFAULT_PERIOD = Duration.ofDays(90);
    private static final int MAX_LINES = 500;

    private final LedgerRepository ledger;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final PaymentsSettings settings;
    private final Clock clock;

    SellerStatementServiceImpl(LedgerRepository ledger, MoneyTransactions tx, PaymentsAccess access,
                               PaymentsSettings settings, Clock clock) {
        this.ledger = ledger;
        this.tx = tx;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public List<StatementLine> statement(String orgId, String currency, Instant from, Instant to, int limit) {
        String org = access.orgOf(orgId);
        access.balanceViewer(org);
        Instant end = to == null ? Instant.now(clock) : to;
        Instant start = from == null ? end.minus(DEFAULT_PERIOD) : from;
        if (!start.isBefore(end)) {
            throw new PaymentsRuleViolation("payments.invalid-request", "from must be before to");
        }
        String cur = currency == null || currency.isBlank() ? settings.currency()
                : currency.trim().toUpperCase(Locale.ROOT);
        int max = Math.max(1, Math.min(limit, MAX_LINES));
        return tx.serializable(() -> ledger.statement(org, cur, start, end, max));
    }
}
