package com.afrotransact.content.domain.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Differences between the draft and the latest published snapshot.
 *
 * @param changedCountries  countries whose status, note or any region switch differs
 * @param newlyLive         countries that become LIVE with this publication (launch-list notifications)
 * @param disabledRegions   regions ({@code US-TX}) whose marketplace switches off
 * @param enabledRegions    regions whose marketplace switches on
 */
public record MarketChanges(List<String> changedCountries, List<String> newlyLive, List<String> disabledRegions,
                            List<String> enabledRegions) {

    public static MarketChanges between(List<Country> published, List<Country> draft) {
        Map<String, Country> before = published.stream().collect(Collectors.toMap(Country::code, Function.identity()));
        List<String> changed = new ArrayList<>();
        List<String> live = new ArrayList<>();
        List<String> off = new ArrayList<>();
        List<String> on = new ArrayList<>();
        for (Country now : draft) {
            Country was = before.get(now.code());
            if (was == null || !was.equals(now)) {
                changed.add(now.code());
            }
            if (now.live() && (was == null || !was.live())) {
                live.add(now.code());
            }
            for (Region r : now.regions()) {
                boolean wasOn = was != null && was.region(r.code()).map(x -> x.capabilities().marketplace()).orElse(false);
                if (wasOn && !r.capabilities().marketplace()) {
                    off.add(now.code() + "-" + r.code());
                } else if (!wasOn && r.capabilities().marketplace()) {
                    on.add(now.code() + "-" + r.code());
                }
            }
        }
        return new MarketChanges(changed, live, off, on);
    }

    public boolean empty() {
        return changedCountries.isEmpty();
    }
}
