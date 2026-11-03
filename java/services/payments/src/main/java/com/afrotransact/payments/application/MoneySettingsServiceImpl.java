package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.domain.MoneySettings;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.port.MoneySettingsRepository;
import com.afrotransact.platform.security.Actor;
import org.springframework.stereotype.Service;

@Service
class MoneySettingsServiceImpl implements MoneySettingsService {

    private static final int MIN_REASON = 15;

    private final MoneySettingsRepository repository;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final Clock clock;

    MoneySettingsServiceImpl(MoneySettingsRepository repository, MoneyTransactions tx, PaymentsAccess access,
                             Clock clock) {
        this.repository = repository;
        this.tx = tx;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public MoneySettings get() {
        access.reader();
        return repository.get();
    }

    @Override
    public MoneySettings update(UpdateCommand c) {
        Actor actor = access.configure();
        if (c.reason() == null || c.reason().trim().length() < MIN_REASON) {
            throw new PaymentsRuleViolation("payments.reason-required",
                    "Say why (at least " + MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        return tx.serializable(() -> {
            MoneySettings before = repository.lock();
            if (before.version() != c.expectedVersion()) {
                throw new PaymentsStateConflict("payments.money-settings-changed",
                        "The settings were changed by someone else; reload and try again");
            }
            MoneySettings next = new MoneySettings(c.giftCardMinMinor(), c.giftCardMaxMinor(), c.giftCardPresets(),
                    c.giftCardTerms() == null ? Map.of() : c.giftCardTerms(), c.creditApprovalLimitMinor(),
                    actor.subject(), Instant.now(clock), before.version());
            return repository.save(next, UUID.randomUUID(), c.reason().trim(), before);
        });
    }

    @Override
    public List<MoneySettingsRepository.Change> history(int limit) {
        access.reader();
        return repository.changes(Math.max(1, Math.min(limit, 200)));
    }
}
