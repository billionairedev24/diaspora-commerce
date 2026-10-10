package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which pre-order a shopper sees ({@code at-preorder.js#resolve} and {@code #openList}). Framework-free; the
 * application loads the windows, coverage and counts.
 */
public final class PreorderResolver {

    private static final List<PreorderStatus> ORDER = List.of(PreorderStatus.OPEN, PreorderStatus.UPCOMING,
            PreorderStatus.CLOSED);

    /**
     * Product facts the eligibility check needs (from catalog).
     *
     * @param atin             variant ATIN
     * @param category         catalog category name
     * @param shipsNow         always ships (e.g. shea butter, fabric)
     * @param preorderEligible catalog flag (default true for food categories)
     */
    public record ProductFacts(String atin, String category, boolean shipsNow, boolean preorderEligible) {
    }

    /**
     * Eligibility of one product at a place.
     *
     * @param atin     variant ATIN
     * @param preorder the product is a pre-order product here
     * @param window   the window (null when not pre-order)
     * @param stage    window stage
     */
    public record Eligibility(String atin, boolean preorder, PreorderWindow window, PreorderStatus stage) {
    }

    /**
     * Resolves a place: windows that are not drafts and cover it, the first OPEN, then UPCOMING, then CLOSED one;
     * {@code on = window != null && enabled && active && areaOn(place)}. Metro cities never show pre-order.
     *
     * @param counts live pre-order counts per window
     * @param metro  the place is a next-day metro city
     */
    public Resolution resolve(String country, String region, String city, List<PreorderWindow> windows,
                              Coverage coverage, Map<UUID, Integer> counts, boolean metro, Instant now) {
        AreaKey place = new AreaKey(country, region, city);
        PreorderSettings settings = coverage.settings();
        if (metro) {
            return new Resolution(place, false, null, null, null, null, settings.fallback(), true, null, null,
                    settings);
        }
        List<PreorderWindow> covering = windows.stream()
                .filter(w -> !w.draft() && w.covers(place.country(), place.region(), place.city())).toList();
        PreorderWindow win = null;
        PreorderStatus stage = null;
        for (PreorderStatus want : ORDER) {
            for (PreorderWindow w : covering) {
                if (w.status(now) == want) {
                    win = w;
                    stage = want;
                    break;
                }
            }
            if (win != null) {
                break;
            }
        }
        if (win == null) {
            return new Resolution(place, false, null, null, null, null, settings.fallback(), false, null, null,
                    settings);
        }
        boolean on = settings.enabled() && win.active()
                && coverage.areaOn(place.country(), place.region(), place.city());
        Integer count = win.details().showCount() && stage != PreorderStatus.UPCOMING
                ? counts.getOrDefault(win.id(), 0) : null;
        PaymentSettings payment = settings.payment();
        Money door = win.details().door() ? win.doorFee(payment).orElse(null) : null;
        Money pickup = win.details().pickup() ? win.pickupFee(payment) : null;
        return new Resolution(place, on, stage, win, win.countdownTarget(stage), count, settings.fallback(), false,
                door, pickup, settings);
    }

    /**
     * Open and upcoming windows for the {@code /pre-orders} page: published, active, at least one target switched on;
     * open ones first, then by name. Empty when the master switch is off.
     */
    public List<PreorderWindow> openList(List<PreorderWindow> windows, Coverage coverage, Instant now) {
        if (!coverage.settings().enabled()) {
            return List.of();
        }
        Set<PreorderStatus> listed = Set.of(PreorderStatus.OPEN, PreorderStatus.UPCOMING);
        return windows.stream()
                .filter(w -> listed.contains(w.status(now)))
                .filter(w -> w.details().targets().stream().anyMatch(coverage::targetOn))
                .sorted(Comparator.comparing((PreorderWindow w) -> w.status(now) == PreorderStatus.OPEN ? 0 : 1)
                        .thenComparing(w -> w.details().name()))
                .toList();
    }

    /**
     * A product is a pre-order product where pre-order is on and its category is in the window's categories, unless
     * catalog flags it {@code shipsNow} or not pre-order eligible.
     */
    public Eligibility eligibility(Resolution r, ProductFacts product) {
        boolean preorder = r.on() && r.window() != null && product.preorderEligible() && !product.shipsNow()
                && r.window().details().includesCategory(product.category());
        return new Eligibility(product.atin(), preorder, preorder ? r.window() : null, preorder ? r.stage() : null);
    }
}
