package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.OrdersStateConflict;
import com.afrotransact.orders.domain.ShippingSettings;
import com.afrotransact.orders.domain.ShippingSettingsChange;
import com.afrotransact.orders.port.ShippingSettingsRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShippingSettingsServiceImpl implements ShippingSettingsService {

    static final int MIN_REASON = 10;
    static final int MAX_REASON = 500;
    private static final int MAX_CHANGES = 200;

    private final ShippingSettingsRepository settings;
    private final PermissionChecker permissions;
    private final Clock clock;

    ShippingSettingsServiceImpl(ShippingSettingsRepository settings, PermissionChecker permissions, Clock clock) {
        this.settings = settings;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ShippingSettings current(String currency) {
        return settings.find(code(currency)).orElseThrow(() -> new IllegalStateException(
                "No shipping settings for " + code(currency) + ": delivery cannot be priced"));
    }

    @Override
    @Transactional(readOnly = true)
    public ShippingSettings view(String currency) {
        return current(currency);
    }

    @Override
    @Transactional
    public ShippingSettings replace(String currency, ShippingSettingsCommand c, Long expectedVersion) {
        Actor actor = permissions.require(OrdersPermissions.SHIPPING_EDIT);
        String code = code(currency);
        String reason = c.reason() == null ? "" : c.reason().trim();
        if (reason.length() < MIN_REASON) {
            throw new OrdersRuleViolation("orders.reason-required", "Say why: give a reason of at least " + MIN_REASON
                    + " characters. It is logged with your name.");
        }
        if (reason.length() > MAX_REASON) {
            throw new OrdersRuleViolation("orders.invalid-reason", "The reason is at most " + MAX_REASON
                    + " characters");
        }
        Instant now = Instant.now(clock);
        ShippingSettings current = settings.lock(code).orElse(null);
        long seen = current == null ? 0 : current.version();
        if (expectedVersion != null && expectedVersion != seen) {
            throw new OrdersStateConflict("orders.shipping-settings-version-mismatch",
                    "The shipping settings changed since you opened them (now version " + seen + "). Reload and try "
                            + "again.");
        }
        if (current != null && current.sameAs(c.flatFeeMinor(), c.freeAboveMinor(), c.sellerCarrierShippingEnabled())) {
            return current;
        }
        ShippingSettings next = current == null
                ? new ShippingSettings(code, c.flatFeeMinor(), c.freeAboveMinor(), c.sellerCarrierShippingEnabled(),
                        1, now, actor.subject(), reason)
                : current.next(c.flatFeeMinor(), c.freeAboveMinor(), c.sellerCarrierShippingEnabled(),
                        actor.subject(), reason, now);
        if (current == null) {
            settings.insert(next);
        } else {
            settings.update(next);
        }
        settings.log(new ShippingSettingsChange(UUID.randomUUID(), code, next.version(), current, next,
                actor.subject(), reason, now));
        return next;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingSettingsChange> changes(String currency, int limit) {
        permissions.require(OrdersPermissions.SHIPPING_VIEW);
        return settings.changes(code(currency), Math.min(Math.max(limit, 1), MAX_CHANGES));
    }

    private static String code(String currency) {
        return currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase(Locale.ROOT);
    }
}
