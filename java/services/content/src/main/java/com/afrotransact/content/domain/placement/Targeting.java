package com.afrotransact.content.domain.placement;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * Who sees a placement. An empty set means "any". Location matches when the ZIP starts with one of the
 * prefixes or the DMA is listed. Sensitive categories are never targetable: only market, community (a shopping
 * interest the visitor picked), location, device, new vs returning and the page's category exist.
 *
 * @param markets     ISO country codes (US, CA…)
 * @param communities community keys (nigerian, ghanaian…)
 * @param zipPrefixes ZIP prefixes of 1–5 digits
 * @param dmas        Nielsen DMA codes
 * @param devices     device classes
 * @param audience    new vs returning visitors
 * @param categories  category ids or slugs (category banners)
 */
public record Targeting(Set<String> markets, Set<String> communities, Set<String> zipPrefixes, Set<String> dmas,
                        Set<Device> devices, Audience audience, Set<String> categories) {

    private static final Pattern MARKET = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern ZIP_PREFIX = Pattern.compile("^\\d{1,5}$");
    private static final Pattern DMA = Pattern.compile("^\\d{3}$");

    public Targeting {
        markets = upper(markets);
        communities = lower(communities);
        zipPrefixes = trimmed(zipPrefixes);
        dmas = trimmed(dmas);
        devices = devices == null ? Set.of() : Set.copyOf(devices);
        audience = audience == null ? Audience.ALL : audience;
        categories = lower(categories);
    }

    public static Targeting everyone() {
        return new Targeting(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Audience.ALL, Set.of());
    }

    /** Field errors for malformed entries (empty when valid). */
    public Map<String, String> problems() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!markets.stream().allMatch(m -> MARKET.matcher(m).matches())) {
            errors.put("targeting.markets", "Use two-letter ISO country codes");
        }
        if (!zipPrefixes.stream().allMatch(z -> ZIP_PREFIX.matcher(z).matches())) {
            errors.put("targeting.zipPrefixes", "ZIP prefixes are 1 to 5 digits");
        }
        if (!dmas.stream().allMatch(d -> DMA.matcher(d).matches())) {
            errors.put("targeting.dmas", "DMA codes are 3 digits");
        }
        return errors;
    }

    public void validate() {
        ContentRuleViolation.throwIfAny("content.invalid-targeting", "Targeting is invalid", problems());
    }

    public boolean targetsMarket(String market) {
        return markets.isEmpty() || market != null && markets.contains(market.toUpperCase(Locale.ROOT));
    }

    /** True when every targeting dimension accepts the context. */
    public boolean matches(PlacementContext ctx) {
        return targetsMarket(ctx.market())
                && (communities.isEmpty() || ctx.communities().stream().anyMatch(communities::contains))
                && locationMatches(ctx)
                && (devices.isEmpty() || ctx.device() != null && devices.contains(ctx.device()))
                && audienceMatches(ctx.returning())
                && (categories.isEmpty() || ctx.category() != null && categories.contains(ctx.category()));
    }

    private boolean locationMatches(PlacementContext ctx) {
        if (zipPrefixes.isEmpty() && dmas.isEmpty()) {
            return true;
        }
        boolean zipHit = ctx.zip() != null && zipPrefixes.stream().anyMatch(ctx.zip()::startsWith);
        boolean dmaHit = ctx.dma() != null && dmas.contains(ctx.dma());
        return zipHit || dmaHit;
    }

    private boolean audienceMatches(Boolean returning) {
        return switch (audience) {
            case ALL -> true;
            case NEW -> Boolean.FALSE.equals(returning);
            case RETURNING -> Boolean.TRUE.equals(returning);
        };
    }

    private static Set<String> upper(Collection<String> in) {
        return in == null ? Set.of() : in.stream().filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> lower(Collection<String> in) {
        return in == null ? Set.of() : in.stream().filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> trimmed(Collection<String> in) {
        return in == null ? Set.of() : in.stream().filter(s -> s != null && !s.isBlank())
                .map(String::trim).collect(Collectors.toCollection(TreeSet::new));
    }
}
