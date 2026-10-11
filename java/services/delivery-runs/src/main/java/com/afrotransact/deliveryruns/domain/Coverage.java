package com.afrotransact.deliveryruns.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Platform settings plus coverage areas.
 *
 * @param settings master switch, fallback, placements and payment
 * @param areas    coverage switches
 */
public record Coverage(PreorderSettings settings, List<CoverageArea> areas) {

    public Coverage {
        settings = settings == null ? PreorderSettings.DEFAULT : settings;
        areas = areas == null ? List.of() : List.copyOf(areas);
    }

    /** {@code at-preorder.js#areaOn}: a matching city row wins; otherwise the region row; otherwise off. */
    public boolean areaOn(String country, String region, String city) {
        return areaOn(areas, country, region, city);
    }

    public static boolean areaOn(List<CoverageArea> areas, String country, String region, String city) {
        if (country == null || region == null) {
            return false;
        }
        if (city != null && !city.isBlank()) {
            for (CoverageArea a : areas) {
                AreaKey k = a.key();
                if (k.isCity() && k.country().equalsIgnoreCase(country) && k.region().equalsIgnoreCase(region)
                        && k.city().equalsIgnoreCase(city.trim())) {
                    return a.on();
                }
            }
        }
        for (CoverageArea a : areas) {
            AreaKey k = a.key();
            if (!k.isCity() && k.country().equalsIgnoreCase(country) && k.region().equalsIgnoreCase(region)) {
                return a.on();
            }
        }
        return false;
    }

    /**
     * Whether a window target is switched on: a city target by {@link #areaOn}, a region target only by its own
     * region row ({@code openList} in the design).
     */
    public boolean targetOn(AreaKey target) {
        if (target.isCity()) {
            return areaOn(target.country(), target.region(), target.city());
        }
        return areas.stream().anyMatch(a -> a.key().sameAs(target) && a.on());
    }

    /** Whether every target of the window is off ("Area off" in the staff list). */
    public boolean allTargetsOff(List<AreaKey> targets) {
        return !targets.isEmpty() && targets.stream().noneMatch(this::targetOn);
    }

    /** Rows of one region ("Every city" first, then cities by name). */
    public List<CoverageArea> region(AreaKey region) {
        AreaKey r = region.regionKey();
        return areas.stream().filter(a -> a.key().country().equals(r.country()) && a.key().region().equals(r.region()))
                .sorted(Comparator.comparing((CoverageArea a) -> a.key().isCity() ? 1 : 0)
                        .thenComparing(a -> a.key().isCity() ? a.key().city().toLowerCase(Locale.ROOT) : ""))
                .toList();
    }

    /**
     * The optimistic-lock version of one region's coverage (its ETag): a fingerprint of the region's rows with their
     * switches and row versions, so adding, removing or switching any of them (by anyone) changes it.
     */
    public long regionVersion(AreaKey region) {
        long h = 0xcbf29ce484222325L;
        for (CoverageArea a : region(region)) {
            String row = a.key().key().toLowerCase(Locale.ROOT) + "|" + a.on() + "|" + a.version() + ";";
            for (byte b : row.getBytes(StandardCharsets.UTF_8)) {
                h ^= b & 0xff;
                h *= 0x100000001b3L;
            }
        }
        return h & Long.MAX_VALUE;
    }

    /** The stored row for exactly this key (city ignoring case). */
    public Optional<CoverageArea> find(AreaKey key) {
        return areas.stream().filter(a -> a.key().sameAs(key)).findFirst();
    }

    /** What turning the region's rows into {@code desired} adds, removes and switches. */
    public AreaChanges changes(RegionCoverage desired) {
        List<CoverageArea> current = region(desired.region());
        List<CoverageArea> want = desired.areas();
        List<CoverageArea> added = new ArrayList<>();
        List<CoverageArea> switched = new ArrayList<>();
        for (CoverageArea w : want) {
            Optional<CoverageArea> have = current.stream().filter(a -> a.key().sameAs(w.key())).findFirst();
            if (have.isEmpty()) {
                added.add(w);
            } else if (have.get().on() != w.on()) {
                switched.add(new CoverageArea(have.get().key(), w.on(), have.get().version()));
            }
        }
        List<CoverageArea> removed = current.stream()
                .filter(a -> want.stream().noneMatch(w -> w.key().sameAs(a.key()))).toList();
        return new AreaChanges(added, removed, switched);
    }

    /** This coverage with {@code changes} applied (row versions of switched rows bumped). */
    public Coverage apply(AreaChanges changes) {
        List<CoverageArea> next = new ArrayList<>();
        for (CoverageArea a : areas) {
            if (changes.removed().stream().anyMatch(r -> r.key().sameAs(a.key()))) {
                continue;
            }
            Optional<CoverageArea> sw = changes.switched().stream().filter(x -> x.key().sameAs(a.key())).findFirst();
            next.add(sw.map(x -> new CoverageArea(a.key(), x.on(), a.version() + 1)).orElse(a));
        }
        next.addAll(changes.added());
        return new Coverage(settings, next);
    }

    /**
     * Whether some row decides {@code target}'s switch: its own row, or for a city its region's "Every city" row.
     * A target nothing governs is off and cannot be switched on without adding it back.
     */
    public boolean governs(AreaKey target) {
        if (find(target).isPresent()) {
            return true;
        }
        return target.isCity() && find(target.regionKey()).isPresent();
    }

    /**
     * Targets of live windows that {@code after} would no longer cover at all (their row, and for a city its region
     * row, removed): removing a place an open or scheduled pre-order sells to is refused; switching it off is not.
     */
    public List<AreaKey> stranded(Coverage after, Collection<AreaKey> liveTargets) {
        return liveTargets.stream().filter(t -> governs(t) && !after.governs(t)).distinct().toList();
    }
}
