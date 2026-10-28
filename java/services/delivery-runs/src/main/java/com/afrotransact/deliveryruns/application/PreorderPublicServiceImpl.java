package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Coverage;
import com.afrotransact.deliveryruns.domain.PreorderResolver;
import com.afrotransact.deliveryruns.domain.PreorderResolver.Eligibility;
import com.afrotransact.deliveryruns.domain.PreorderResolver.ProductFacts;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Resolution;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.port.CatalogGateway;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PreorderPublicServiceImpl implements PreorderPublicService {

    private static final int MAX_ATINS = 100;

    private final PreorderWindowRepository windows;
    private final PreorderOrderRepository orders;
    private final CatalogGateway catalog;
    private final PreorderSupport support;
    private final PreorderOptions options;
    private final PermissionChecker permissions;
    private final Clock clock;
    private final PreorderResolver resolver = new PreorderResolver();

    PreorderPublicServiceImpl(PreorderWindowRepository windows, PreorderOrderRepository orders,
                              CatalogGateway catalog, PreorderSupport support, PreorderOptions options,
                              PermissionChecker permissions, Clock clock) {
        this.windows = windows;
        this.orders = orders;
        this.catalog = catalog;
        this.support = support;
        this.options = options;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Resolution resolve(String country, String region, String city) {
        return resolveAt(country, region, city, Instant.now(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenWindow> open() {
        Instant now = Instant.now(clock);
        Coverage coverage = support.coverage();
        return resolver.openList(windows.findPublished(), coverage, now).stream()
                .map(w -> new OpenWindow(w, w.status(now),
                        w.details().door() ? w.doorFee(coverage.fees()).orElse(null) : null,
                        w.details().pickup() ? w.pickupFee(coverage.fees()) : null))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EligibilityResult eligibility(String country, String region, String city, List<String> atins) {
        return eligibility(country, region, city, atins, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public EligibilityResult eligibility(String country, String region, String city, List<String> atins,
                                         int earlyAccessHours) {
        permissions.require(DeliveryRunsPermissions.RESERVE);
        Set<String> wanted = new LinkedHashSet<>();
        for (String a : atins == null ? List.<String>of() : atins) {
            if (a != null && !a.isBlank()) {
                wanted.add(a.trim());
            }
        }
        if (wanted.isEmpty() || wanted.size() > MAX_ATINS) {
            throw new RuleViolation("delivery-runs.invalid-atins", "Send between 1 and " + MAX_ATINS + " ATINs");
        }
        Instant now = Instant.now(clock);
        Resolution r = resolveAt(country, region, city, now).forEarlyAccess(now, earlyAccessHours);
        Map<String, ProductFacts> facts = r.on() && r.window() != null ? catalog.facts(wanted) : Map.of();
        List<Eligibility> items = wanted.stream().map(atin -> {
            ProductFacts f = facts.get(atin);
            return f == null ? new Eligibility(atin, false, null, null) : resolver.eligibility(r, f);
        }).toList();
        return new EligibilityResult(r, items);
    }

    private Resolution resolveAt(String country, String region, String city, Instant now) {
        if (country == null || country.isBlank() || region == null || region.isBlank()) {
            throw new RuleViolation("delivery-runs.invalid-place", "country and region are required");
        }
        List<PreorderWindow> published = windows.findPublished();
        Coverage coverage = support.coverage();
        Map<UUID, Integer> counts = new HashMap<>();
        orders.totals(published.stream().map(PreorderWindow::id).toList())
                .forEach((id, t) -> counts.put(id, t.orders()));
        return resolver.resolve(country, region, city, published, coverage, counts,
                options.metro(country, region, city), now);
    }
}
