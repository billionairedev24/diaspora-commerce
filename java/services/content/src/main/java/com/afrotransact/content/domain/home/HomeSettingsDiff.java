package com.afrotransact.content.domain.home;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** The changed fields between two versions, named like {@code modules.communities.minProducts}. */
public final class HomeSettingsDiff {

    /** One changed field. */
    public record Change(String field, Object from, Object to) {
    }

    private HomeSettingsDiff() {
    }

    public static List<Change> between(HomeSettings before, HomeSettings after) {
        List<Change> out = new ArrayList<>();
        add(out, "dynamic", before.dynamic(), after.dynamic());
        for (String key : new TreeSet<>(union(before.modules(), after.modules()))) {
            ModuleRule a = before.modules().get(key);
            ModuleRule b = after.modules().get(key);
            String p = "modules." + key + ".";
            add(out, p + "enabled", a == null ? null : a.enabled(), b == null ? null : b.enabled());
            add(out, p + "minProducts", a == null ? null : a.minProducts(), b == null ? null : b.minProducts());
            add(out, p + "minSellers", a == null ? null : a.minSellers(), b == null ? null : b.minSellers());
            add(out, p + "minUnits", a == null ? null : a.minUnits(), b == null ? null : b.minUnits());
            add(out, p + "unitMinProducts", a == null ? null : a.unitMinProducts(), b == null ? null : b.unitMinProducts());
            add(out, p + "unitMinSellers", a == null ? null : a.unitMinSellers(), b == null ? null : b.unitMinSellers());
            add(out, p + "maxAgeDays", a == null ? null : a.maxAgeDays(), b == null ? null : b.maxAgeDays());
            add(out, p + "weight", a == null ? null : a.weight(), b == null ? null : b.weight());
            add(out, p + "show", a == null ? null : a.show(), b == null ? null : b.show());
            add(out, p + "band", a == null ? null : a.band(), b == null ? null : b.band());
        }
        HomeCaps x = before.caps();
        HomeCaps y = after.caps();
        add(out, "caps.maxModules", x.maxModules(), y.maxModules());
        add(out, "caps.minModules", x.minModules(), y.minModules());
        add(out, "caps.sellerShare", x.sellerShare(), y.sellerShare());
        add(out, "caps.sponsoredPerOrganic", x.sponsoredPerOrganic(), y.sponsoredPerOrganic());
        add(out, "caps.poolMultiplier", x.poolMultiplier(), y.poolMultiplier());
        add(out, "caps.noveltyLoads", x.noveltyLoads(), y.noveltyLoads());
        add(out, "caps.noveltyPenalty", x.noveltyPenalty(), y.noveltyPenalty());
        add(out, "caps.visitWindowMinutes", x.visitWindowMinutes(), y.visitWindowMinutes());
        add(out, "caps.hysteresis", x.hysteresis(), y.hysteresis());
        add(out, "caps.dealMinPercent", x.dealMinPercent(), y.dealMinPercent());
        add(out, "caps.navBarSize", x.navBarSize(), y.navBarSize());
        for (String key : new TreeSet<>(union(before.departmentLabels(), after.departmentLabels()))) {
            add(out, "departmentLabels." + key, before.departmentLabels().get(key), after.departmentLabels().get(key));
        }
        return out;
    }

    /** The changes as JSON-friendly maps (for the audit log and the version history). */
    public static List<Map<String, Object>> asMaps(List<Change> changes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Change c : changes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", c.field());
            m.put("from", c.from());
            m.put("to", c.to());
            out.add(m);
        }
        return out;
    }

    private static void add(List<Change> out, String field, Object from, Object to) {
        if (!Objects.equals(from, to)) {
            out.add(new Change(field, from, to));
        }
    }

    private static <V> Set<String> union(Map<String, V> a, Map<String, V> b) {
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }
}
