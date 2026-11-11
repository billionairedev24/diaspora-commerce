package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.QuoteSettings;
import com.afrotransact.orders.domain.QuoteSettings.QuoteTarget;
import com.afrotransact.orders.port.DeliveryQuoteSettingsRepository;
import com.afrotransact.orders.port.StaffDirectory;
import com.afrotransact.orders.port.StaffDirectory.StaffPerson;
import com.afrotransact.platform.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeliveryQuoteSettingsServiceImpl implements DeliveryQuoteSettingsService {

    private static final int AUDIT_ROWS = 20;

    private final DeliveryQuoteSettingsRepository repo;
    private final StaffDirectory directory;
    private final OrdersAccess access;
    private final Clock clock;

    DeliveryQuoteSettingsServiceImpl(DeliveryQuoteSettingsRepository repo, StaffDirectory directory,
                                     OrdersAccess access, Clock clock) {
        this.repo = repo;
        this.directory = directory;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Current current() {
        access.quoteStaff(OrdersPermissions.QUOTES_VIEW, OrdersPermissions.QUOTES_AGREE,
                OrdersPermissions.QUOTES_ASSIGN);
        return describe(repo.load());
    }

    @Override
    @Transactional
    public Current update(QuoteSettings next, String reason, String actorName) {
        Actor actor = access.quoteStaff(OrdersPermissions.QUOTES_ASSIGN);
        Map<String, String> problems = new LinkedHashMap<>(next.problems());
        if (reason == null || reason.trim().length() < DeliveryQuoteDeskServiceImpl.MIN_REASON) {
            problems.put("reason", "Say why you are changing this");
        }
        checkTarget("level1", next.level1(), problems);
        checkTarget("level2", next.level2(), problems);
        if (!problems.isEmpty()) {
            throw new OrdersRuleViolation("orders.invalid-quote-settings", "Fix the highlighted settings: "
                    + String.join("; ", problems.values()));
        }
        QuoteSettings before = repo.lock();
        String by = actorName == null || actorName.isBlank() ? "Unknown user" : actorName.trim();
        // The cursor is not an edit.
        QuoteSettings stored = new QuoteSettings(next.slaBusinessDays(), next.dueSoonPercent(),
                next.unassignedAfterHours(), next.level2AfterHours(), next.autoAssign(), before.roundRobinCursor(),
                next.level1(), next.level2(), next.minAmountMinor(), next.maxAmountMinor(), next.validityHours(),
                next.requestsPerHour(), next.duplicateWindowMinutes(), by, Instant.now(clock));
        repo.save(stored, actor.subject(), by, reason.trim(), before, Instant.now(clock));
        return describe(repo.load());
    }

    private void checkTarget(String field, QuoteTarget target, Map<String, String> problems) {
        if (target.roleKey() != null && directory.withRole(target.roleKey()).isEmpty()) {
            problems.put(field, "Nobody active holds the role " + target.roleKey());
        }
        if (!target.userIds().isEmpty()) {
            List<String> found = directory.people(target.userIds()).stream().map(StaffPerson::id).toList();
            if (!found.containsAll(target.userIds())) {
                problems.put(field, "Every person must be active staff");
            }
        }
    }

    private Current describe(QuoteSettings s) {
        return new Current(s, people(s.level1()), people(s.level2()), repo.changes(AUDIT_ROWS));
    }

    private List<String> people(QuoteTarget t) {
        List<String> names = new ArrayList<>();
        try {
            if (!t.userIds().isEmpty()) {
                directory.people(t.userIds()).forEach(p -> names.add(p.name()));
            }
        } catch (RuntimeException e) {
            // names are display only
        }
        return names;
    }
}
