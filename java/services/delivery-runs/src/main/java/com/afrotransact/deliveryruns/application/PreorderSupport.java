package com.afrotransact.deliveryruns.application;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.Coverage;
import com.afrotransact.deliveryruns.domain.Markets;
import com.afrotransact.deliveryruns.domain.Money;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.PaymentSettings;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.WindowDetails;
import com.afrotransact.deliveryruns.domain.WindowSwitch;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository.WindowTotals;
import com.afrotransact.deliveryruns.port.PreorderSettingsRepository;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/** Shared pre-order plumbing: loading, version checks, coverage and views. */
@Component
class PreorderSupport {

    private final PreorderWindowRepository windows;
    private final PreorderSettingsRepository settings;
    private final PreorderOrderRepository orders;
    private final String pageUrl;
    private final int defaultMinOrders;
    private final FeeBook fees;

    PreorderSupport(PreorderWindowRepository windows, PreorderSettingsRepository settings,
                    PreorderOrderRepository orders, DeliveryRunsSettings runSettings, PreorderOptions options,
                    FeeBook fees) {
        this.fees = fees;
        this.defaultMinOrders = options.defaultMinOrders();
        this.windows = windows;
        this.settings = settings;
        this.orders = orders;
        this.pageUrl = runSettings.publicWebUrl() + options.pagePath();
    }

    /** Minimum order count a new window starts with. */
    int defaultMinOrders() {
        return defaultMinOrders;
    }

    /** Storefront pre-order page, the base of the tracked SMS link ({@code <PUBLIC_WEB_URL>/pre-orders}). */
    String pageUrl() {
        return pageUrl;
    }

    Coverage coverage() {
        return new Coverage(settings.settings(), settings.areas(), fees.current());
    }

    PreorderWindow find(UUID id) {
        return windows.findById(id).orElseThrow(PreorderSupport::notFound);
    }

    PreorderWindow lock(UUID id, Long expectedVersion) {
        PreorderWindow w = windows.lockById(id).orElseThrow(PreorderSupport::notFound);
        requireVersion(w.version(), expectedVersion, "Pre-order");
        return w;
    }

    static void requireVersion(long actual, Long expected, String what) {
        if (expected != null && expected != actual) {
            throw new VersionMismatchException(what + " changed since version " + expected + " (now " + actual
                    + "); reload and retry", actual);
        }
    }

    static NotFoundException notFound() {
        return new NotFoundException("delivery-runs.preorder-not-found", "Pre-order not found");
    }

    WindowView view(PreorderWindow w, Coverage coverage, Map<UUID, WindowTotals> totals, Instant now) {
        PaymentSettings payment = coverage.settings().payment();
        WindowTotals t = totals.getOrDefault(w.id(), new WindowTotals(0, 0));
        boolean areaOff = !coverage.settings().enabled() || coverage.allTargetsOff(w.details().targets());
        FeeSnapshot door = w.details().door() ? w.doorFee(coverage.fees()).orElse(null) : null;
        return new WindowView(w, w.status(now), w.publishChecks(coverage.fees(), pageUrl), door,
                w.pickupFee(coverage.fees()), payment.pickupWindow(), t.orders(),
                t.revenueMinor(), areaOff, WindowSwitch.of(w, coverage, now));
    }

    WindowView view(PreorderWindow w, Instant now) {
        return view(w, coverage(), orders.totals(List.of(w.id())), now);
    }

    /**
     * Builds validated details from a command; null flags take the wizard's defaults and a null minimum takes
     * {@code defaultMin} (the configured default on create, the window's current minimum on edit).
     */
    static WindowDetails details(WindowCommand c, int defaultMin) {
        List<AreaKey> targets = c.targets() == null ? List.of()
                : c.targets().stream().map(AreaKey::parse).toList();
        String currency = Markets.currencyOf(targets.isEmpty() ? null : targets.get(0).country());
        ZoneId zone;
        try {
            zone = c.timeZone() == null || c.timeZone().isBlank() ? ZoneId.of("America/Chicago")
                    : ZoneId.of(c.timeZone().trim());
        } catch (DateTimeException e) {
            throw new RuleViolation("delivery-runs.invalid-time-zone", "Unknown time zone " + c.timeZone());
        }
        return new WindowDetails(c.name(), targets, c.opensAt(), c.closesAt(), c.deliversOn(), zone, c.ribbon(),
                c.headline(), c.smsCopy(), c.categories(), c.door() == null || c.door(),
                c.pickup() != null && c.pickup(), c.pickupPointId(), c.pickupAddress(),
                c.showCount() == null || c.showCount(),
                new Money(c.socialBudgetMinor() == null ? 0 : c.socialBudgetMinor(), currency), c.comms(),
                c.doorFeeOverrideMinor() == null ? null : new Money(c.doorFeeOverrideMinor(), currency),
                c.pickupFeeOverrideMinor() == null ? null : new Money(c.pickupFeeOverrideMinor(), currency),
                c.locationId(), c.minOrders() == null ? defaultMin : c.minOrders());
    }
}
