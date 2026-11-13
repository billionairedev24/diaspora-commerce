package com.afrotransact.content.domain.home;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * The staff-editable settings of the dynamic home: module thresholds, page and variety caps, the mode and staff
 * department labels. Version 0 means nothing is stored and the defaults apply.
 *
 * @param version          stored version (0 = defaults)
 * @param dynamic          off, shadow, on, or null to follow the environment default (on in local, dev, uat and trial;
 *                         off in prod until the owner switches it)
 * @param modules          rule per registry module key
 * @param caps             limits
 * @param departmentLabels staff display-label overrides by department key and locale
 * @param updatedAt        when this version was saved
 * @param updatedBy        who saved it
 */
public record HomeSettings(int version, String dynamic, Map<String, ModuleRule> modules, HomeCaps caps,
                           Map<String, Map<String, String>> departmentLabels, Instant updatedAt, String updatedBy) {

    public static final String HERO = "hero";
    public static final String PERSONAL = "personal";
    public static final String DISCOVERY = "discovery";
    public static final String PROMO = "promo";
    public static final String BRAND = "brand";

    private static final Set<String> BANDS = Set.of(HERO, PERSONAL, DISCOVERY, PROMO, BRAND);
    private static final Set<String> MODES = Set.of("off", "shadow", "on");
    private static final Set<String> LOCALES = Set.of("en", "fr", "es", "ar", "yo");

    public HomeSettings {
        modules = modules == null ? Map.of() : Map.copyOf(modules);
        caps = caps == null ? HomeCaps.defaults() : caps;
        departmentLabels = departmentLabels == null ? Map.of() : Map.copyOf(departmentLabels);
    }

    /** The registry of module keys with their documented defaults; Go's DefaultHomeRules must say the same. */
    public static Map<String, ModuleRule> defaultModules() {
        Map<String, ModuleRule> m = new TreeMap<>();
        m.put("hero", new ModuleRule(true, 0, 0, 0, 0, 0, 0, 100, 5, HERO));
        m.put("brand-hero", new ModuleRule(true, 0, 0, 0, 0, 0, 0, 99, 1, HERO));
        m.put("continue-shopping", new ModuleRule(true, 1, 0, 0, 0, 0, 0, 95, 12, PERSONAL));
        m.put("recently-viewed", new ModuleRule(true, 1, 0, 0, 0, 0, 0, 90, 12, PERSONAL));
        m.put("deals", new ModuleRule(true, 6, 2, 0, 0, 0, 0, 85, 8, DISCOVERY));
        m.put("department-tiles", new ModuleRule(true, 0, 0, 2, 1, 0, 0, 80, 8, DISCOVERY));
        m.put("groceries-rail", new ModuleRule(true, 6, 0, 0, 0, 0, 0, 75, 8, DISCOVERY));
        m.put("services-near-you", new ModuleRule(true, 1, 0, 0, 0, 0, 0, 70, 6, DISCOVERY));
        m.put("new-arrivals", new ModuleRule(true, 8, 0, 0, 0, 0, 30, 60, 8, DISCOVERY));
        m.put("top-sellers", new ModuleRule(true, 8, 2, 0, 0, 0, 0, 55, 8, DISCOVERY));
        m.put("department-rail", new ModuleRule(true, 6, 0, 1, 6, 0, 0, 52, 8, DISCOVERY));
        m.put("preorder", new ModuleRule(true, 1, 0, 0, 0, 0, 0, 50, 6, PROMO));
        m.put("communities", new ModuleRule(true, 0, 0, 3, 8, 2, 0, 40, 6, DISCOVERY));
        m.put("plus", new ModuleRule(true, 0, 0, 0, 0, 0, 0, 10, 1, PROMO));
        m.put("sell-band", new ModuleRule(true, 0, 0, 0, 0, 0, 0, 5, 1, BRAND));
        return m;
    }

    public static Set<String> moduleKeys() {
        return defaultModules().keySet();
    }

    /** What loads when no row exists. */
    public static HomeSettings defaults() {
        return new HomeSettings(0, null, defaultModules(), HomeCaps.defaults(), Map.of(), null, null);
    }

    /** This settings with every missing module row filled from the defaults. */
    public HomeSettings withDefaultsFilled() {
        Map<String, ModuleRule> merged = new TreeMap<>(defaultModules());
        merged.putAll(modules);
        return new HomeSettings(version, dynamic, merged, caps, departmentLabels, updatedAt, updatedBy);
    }

    /** Field messages for everything invalid: negatives, a weight outside 0 to 100, unknown modules, caps out of range. */
    public Map<String, String> problems() {
        Map<String, String> bad = new LinkedHashMap<>();
        if (dynamic != null && !MODES.contains(dynamic)) {
            bad.put("dynamic", "Must be off, shadow or on");
        }
        new TreeMap<>(modules).forEach((key, r) -> moduleProblems(bad, key, r));
        range(bad, "maxModules", caps.maxModules(), 3, 24);
        range(bad, "minModules", caps.minModules(), 0, 12);
        range(bad, "sellerShare", caps.sellerShare(), 1, 24);
        range(bad, "sponsoredPerOrganic", caps.sponsoredPerOrganic(), 1, 10);
        range(bad, "poolMultiplier", caps.poolMultiplier(), 1, 10);
        range(bad, "noveltyLoads", caps.noveltyLoads(), 0, 10);
        range(bad, "visitWindowMinutes", caps.visitWindowMinutes(), 5, 240);
        range(bad, "hysteresis", caps.hysteresis(), 0, 20);
        range(bad, "dealMinPercent", caps.dealMinPercent(), 1, 90);
        range(bad, "navBarSize", caps.navBarSize(), 3, 12);
        if (caps.noveltyPenalty() < 0 || caps.noveltyPenalty() > 1) {
            bad.put("caps.noveltyPenalty", "Must be between 0 and 1");
        }
        departmentLabels.forEach((key, labels) -> {
            if (labels.get("en") == null || labels.get("en").isBlank()) {
                bad.put("departmentLabels." + key, "An English label is required");
            }
            labels.forEach((locale, label) -> {
                if (!LOCALES.contains(locale) || label.length() > 80) {
                    bad.put("departmentLabels." + key + "." + locale, "Use en, fr, es, ar or yo, up to 80 characters");
                }
            });
        });
        return bad;
    }

    private static void moduleProblems(Map<String, String> bad, String key, ModuleRule r) {
        String f = "modules." + key;
        if (!moduleKeys().contains(key)) {
            bad.put(f, "Unknown module");
            return;
        }
        nonNegative(bad, f + ".minProducts", r.minProducts());
        nonNegative(bad, f + ".minSellers", r.minSellers());
        nonNegative(bad, f + ".minUnits", r.minUnits());
        nonNegative(bad, f + ".unitMinProducts", r.unitMinProducts());
        nonNegative(bad, f + ".unitMinSellers", r.unitMinSellers());
        nonNegative(bad, f + ".maxAgeDays", r.maxAgeDays());
        if (r.weight() < 0 || r.weight() > 100) {
            bad.put(f + ".weight", "Must be between 0 and 100");
        }
        if (r.show() < 0 || r.show() > 24) {
            bad.put(f + ".show", "Must be between 1 and 24");
        }
        if (r.band() != null && !r.band().isEmpty() && !BANDS.contains(r.band())) {
            bad.put(f + ".band", "Must be hero, personal, discovery, promo or brand");
        }
    }

    private static void nonNegative(Map<String, String> bad, String field, int value) {
        if (value < 0) {
            bad.put(field, "Cannot be negative");
        }
    }

    private static void range(Map<String, String> bad, String name, int value, int min, int max) {
        if (value != 0 && (value < min || value > max)) {
            bad.put("caps." + name, "Must be between " + min + " and " + max);
        }
    }

    /** Throws a 422 with the field messages when anything is invalid. */
    public void validate() {
        Map<String, String> bad = problems();
        if (!bad.isEmpty()) {
            throw new ContentRuleViolation("content.home-settings-invalid", "Some home settings are not valid", bad);
        }
    }
}
