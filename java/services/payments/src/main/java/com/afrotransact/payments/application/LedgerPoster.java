package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.port.LedgerRepository;
import org.springframework.stereotype.Component;

/** Posts balanced journals, exactly once per business key, only inside SERIALIZABLE transactions. */
@Component
class LedgerPoster {

    private final LedgerRepository ledger;
    private final Clock clock;

    LedgerPoster(LedgerRepository ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    /** @return false when a journal with the same key was already posted (idempotent replay) */
    boolean post(Journal journal, String postedBy) {
        if (!MoneyTransactions.inSerializable()) {
            throw new IllegalStateException("Ledger postings must run in a SERIALIZABLE transaction");
        }
        if (ledger.exists(journal.key())) {
            return false;
        }
        ledger.insert(journal, postedBy, Instant.now(clock));
        return true;
    }
}
