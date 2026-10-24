package com.afrotransact.deliveryruns.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.DeliveryFeeChange;
import com.afrotransact.deliveryruns.domain.DeliveryFeeRule;
import com.afrotransact.deliveryruns.domain.DeliveryFees;
import com.afrotransact.deliveryruns.domain.FeeBand;
import com.afrotransact.deliveryruns.domain.FeeMethod;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.port.DeliveryFeeRuleRepository;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeliveryFeeServiceImpl implements DeliveryFeeService {

    /** Longest reason, as for window decisions. */
    static final int MAX_REASON = 500;
    private static final int MAX_CHANGES = 200;

    private final DeliveryFeeRuleRepository rules;
    private final PreorderWindowRepository windows;
    private final FeeBook book;
    private final PermissionChecker permissions;
    private final Clock clock;

    DeliveryFeeServiceImpl(DeliveryFeeRuleRepository rules, PreorderWindowRepository windows, FeeBook book,
                           PermissionChecker permissions, Clock clock) {
        this.rules = rules;
        this.windows = windows;
        this.book = book;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryFeeRule> rules() {
        permissions.require(DeliveryRunsPermissions.READ);
        return rules.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryFeeRule> publicRules(String currency) {
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        return rules.findAll().stream().filter(r -> r.currency().equals(code)).toList();
    }

    @Override
    @Transactional
    public DeliveryFeeRule replace(FeeMethod method, String currency, FeeRuleCommand c, Long expectedVersion) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        String reason = c.reason() == null ? "" : c.reason().trim();
        if (reason.isEmpty()) {
            throw new RuleViolation("delivery-runs.reason-required", "Say why: the reason is logged with your name");
        }
        if (reason.length() > MAX_REASON) {
            throw new RuleViolation("delivery-runs.invalid-reason", "The reason is at most " + MAX_REASON
                    + " characters");
        }
        Instant now = Instant.now(clock);
        Optional<DeliveryFeeRule> current = rules.lock(method, code);
        long seen = current.map(DeliveryFeeRule::version).orElse(0L);
        if (expectedVersion != null && expectedVersion != seen) {
            throw new VersionMismatchException("The " + method + " fee in " + code + " changed since version "
                    + expectedVersion + " (now " + seen + "); reload and retry", seen);
        }
        List<FeeBand> bands = c.bands() == null ? List.of() : c.bands();
        long fee = orZero(c.feeMinor());
        long handling = orZero(c.handlingMinor());
        long fallback = orZero(c.fallbackMinor());
        DeliveryFeeRule next;
        if (current.isPresent() && current.get().sameNumbers(bands, fee, handling, fallback, c.freeAboveMinor())) {
            return current.get();
        }
        if (current.isPresent()) {
            next = current.get().next(bands, fee, handling, fallback, c.freeAboveMinor(), actor.subject(), reason,
                    now);
            rules.update(next);
        } else {
            next = new DeliveryFeeRule(UUID.randomUUID(), method, code, bands, fee, handling, fallback,
                    c.freeAboveMinor(), 1, now, actor.subject(), reason);
            rules.insert(next);
        }
        rules.log(new DeliveryFeeChange(UUID.randomUUID(), method, code, next.version(), current.orElse(null), next,
                actor.subject(), reason, now));
        return next;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryFeeChange> changes(FeeMethod method, String currency, int limit) {
        permissions.require(DeliveryRunsPermissions.READ);
        return rules.changes(method, currency == null || currency.isBlank() ? null : currency.trim().toUpperCase(
                Locale.ROOT), Math.min(Math.max(limit, 1), MAX_CHANGES));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FeeSnapshot> preview(FeePreviewCommand c) {
        permissions.require(DeliveryRunsPermissions.READ);
        return resolve(c);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FeeSnapshot> carrier(String currency, Long carrierQuoteMinor, long itemsMinor) {
        permissions.require(DeliveryRunsPermissions.RESERVE);
        return book.current().carrier(currency.trim().toUpperCase(Locale.ROOT), carrierQuoteMinor, itemsMinor);
    }

    private Optional<FeeSnapshot> resolve(FeePreviewCommand c) {
        if (c.method() == null || c.currency() == null || c.currency().isBlank()) {
            throw new RuleViolation("delivery-runs.invalid-fee-preview", "Choose a method and a currency");
        }
        String currency = c.currency().trim().toUpperCase(Locale.ROOT);
        DeliveryFees fees = book.current();
        PreorderWindow window = c.windowId() == null ? null : windows.findById(c.windowId()).orElseThrow(
                () -> new NotFoundException("delivery-runs.preorder-not-found", "Pre-order not found"));
        return switch (c.method()) {
            case AFROTRANSACT_DELIVERY -> {
                if (window != null && c.distanceMiles() == null) {
                    yield fees.door(window);
                }
                BigDecimal miles = c.distanceMiles();
                if (miles != null && miles.signum() < 0) {
                    throw new RuleViolation("delivery-runs.invalid-fee-preview", "The distance cannot be negative");
                }
                yield fees.door(currency, miles);
            }
            case PICKUP -> Optional.of(window != null ? fees.pickup(window) : fees.pickup(currency));
            case CARRIER -> fees.carrier(currency, c.carrierQuoteMinor(), c.itemsMinor() == null ? 0 : c.itemsMinor());
        };
    }

    private static long orZero(Long v) {
        return v == null ? 0 : v;
    }
}
