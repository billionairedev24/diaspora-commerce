package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.domain.MembershipSettings;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.port.MembershipSettingsRepository;
import com.afrotransact.platform.security.Actor;
import org.springframework.stereotype.Service;

@Service
class MembershipSettingsServiceImpl implements MembershipSettingsService {

    private final MembershipSettingsRepository repository;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final Clock clock;

    MembershipSettingsServiceImpl(MembershipSettingsRepository repository, MoneyTransactions tx,
                                  PaymentsAccess access, Clock clock) {
        this.repository = repository;
        this.tx = tx;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public MembershipSettings get() {
        access.feeReader();
        return repository.get();
    }

    @Override
    public MembershipSettings update(UpdateCommand c) {
        Actor actor = access.configure();
        if (c.reason() == null || c.reason().trim().length() < MembershipTierServiceImpl.MIN_REASON) {
            throw new PaymentsRuleViolation("payments.reason-required", "Say why (at least "
                    + MembershipTierServiceImpl.MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        return tx.serializable(() -> {
            MembershipSettings before = repository.lock();
            if (before.version() != c.expectedVersion()) {
                throw new PaymentsStateConflict("payments.membership-settings-changed",
                        "The settings were changed by someone else; reload and try again");
            }
            MembershipSettings next = new MembershipSettings(c.refundWindowDays(), c.retryDays(), actor.subject(),
                    Instant.now(clock), before.version());
            return repository.save(next, UUID.randomUUID(), c.reason().trim(), before);
        });
    }

    @Override
    public List<MembershipSettingsRepository.Change> history(int limit) {
        access.reader();
        return repository.changes(Math.max(1, Math.min(limit, 200)));
    }
}
