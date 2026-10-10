package com.afrotransact.catalog.domain.prefill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keyword rules from Open Food Facts category tags (and GS1 GPC segments) to the AfroTransact taxonomy seeded by
 * {@code tools/seed/catalog_taxonomy.py}. Tags are compared as words with the language prefix and dashes removed
 * ({@code en:tomato-pastes} → "tomato pastes"): a keyword matches the whole tag, its last words (the head noun) or a
 * multi-word phrase inside it. The most specific tag (last in Open Food Facts order) is ranked first.
 * Paths that no longer exist in the tree are dropped by the caller.
 */
public class DefaultCategoryHintPolicy implements CategoryHintPolicy {

    private static final String PANTRY = "Groceries › Pantry › ";
    private static final Map<String, List<String>> RULES = rules();
    /** GS1 Global Product Classification segments (first two digits of the brick code) → department. */
    private static final Map<String, String> GPC_SEGMENTS = Map.of("50", "Groceries", "53", "Beauty", "60", "Books",
            "67", "Fashion");

    private static Map<String, List<String>> rules() {
        Map<String, List<String>> r = new LinkedHashMap<>();
        r.put(PANTRY + "Spices & seasonings", List.of("spices", "spice", "seasonings", "seasoning", "condiments",
                "bouillon cubes", "stock cubes", "bouillons", "herbs", "dried herbs", "salts", "curry powders",
                "pepper powders", "ground peppers", "cameroon peppers", "suya spices", "crayfish"));
        r.put(PANTRY + "Sauces & pastes", List.of("sauces", "sauce", "tomato pastes", "tomato concentrates",
                "pastes", "spreads", "pepper sauces", "hot sauces", "ketchup", "palm oils", "red palm oils",
                "vegetable oils", "cooking oils", "groundnut oils", "stews", "soups"));
        r.put(PANTRY + "Snacks", List.of("snacks", "salty snacks", "sweet snacks", "chips", "crisps",
                "plantain chips", "chin chin", "biscuits", "cookies", "crackers", "nuts", "peanuts", "roasted peanuts",
                "confectioneries", "candies", "chocolates", "popcorn"));
        r.put(PANTRY + "Grains & rice", List.of("rices", "rice", "long grain rices", "basmati rices", "jasmine rices",
                "cereal grains", "grains", "cereals", "flours", "flour", "cassava flours", "yam flours", "plantain flours",
                "semolinas", "semolina", "garri", "gari", "fufu", "eba", "couscous", "millets", "fonio", "sorghum",
                "maize flours", "corn flours", "oats", "beans", "dried beans", "black eyed peas", "legumes", "pulses",
                "lentils", "pastas", "noodles", "instant noodles"));
        r.put("Groceries › Fresh › Frozen & perishables", List.of("frozen foods", "frozen", "meats", "meat",
                "fishes", "fish", "seafood", "smoked fishes", "dried fishes", "poultries", "chicken", "goat meats",
                "dairies", "cheeses", "yogurts", "fresh vegetables", "fresh fruits", "plantains", "yams"));
        r.put("Groceries › Drinks › Teas & hibiscus", List.of("teas", "tea", "black teas", "green teas", "hibiscus",
                "hibiscus teas", "zobo", "bissap", "infusions", "herbal teas", "malt drinks"));
        r.put("Groceries › Drinks › Coffee", List.of("coffees", "coffee", "ground coffees", "coffee beans",
                "instant coffees"));
        r.put("Health › Wellness › Herbal teas & remedies", List.of("herbal teas", "herbal remedies",
                "dietary supplements", "food supplements", "moringa", "bitter kola"));
        r.put("Beauty › Skin care › Butters & oils", List.of("shea butters", "shea butter", "cocoa butters",
                "body butters", "body oils", "skin care", "moisturizers", "creams"));
        r.put("Beauty › Skin care › Soaps", List.of("soaps", "soap", "black soaps", "african black soaps"));
        r.put("Beauty › Hair care › Hair oils", List.of("hair oils", "hair care", "hair creams"));
        r.put("Books › Books › Cookbooks", List.of("cookbooks", "cook books", "recipe books"));
        return r;
    }

    @Override
    public List<Match> match(List<String> sourceCategories, int limit) {
        List<Match> out = new ArrayList<>();
        for (int i = sourceCategories.size() - 1; i >= 0 && out.size() < limit; i--) {
            String raw = sourceCategories.get(i);
            if (raw == null) {
                continue;
            }
            String gpc = gpcDepartment(raw);
            if (gpc != null) {
                add(out, new Match(gpc, raw), limit);
                continue;
            }
            String label = normalise(raw);
            for (Map.Entry<String, List<String>> rule : RULES.entrySet()) {
                if (rule.getValue().stream().anyMatch(k -> matches(label, k))) {
                    add(out, new Match(rule.getKey(), raw), limit);
                }
            }
        }
        return List.copyOf(out);
    }

    /** Whole label, its last words (the head noun: "long grain rices" → rices) or a multi-word phrase inside it. */
    private static boolean matches(String label, String keyword) {
        return label.equals(keyword) || label.endsWith(" " + keyword)
                || keyword.contains(" ") && (" " + label + " ").contains(" " + keyword + " ");
    }

    private static void add(List<Match> out, Match m, int limit) {
        if (out.size() < limit && out.stream().noneMatch(x -> x.path().equals(m.path()))) {
            out.add(m);
        }
    }

    /** Department path for a {@code gpc:<code>} category, else null. */
    private static String gpcDepartment(String raw) {
        if (!raw.startsWith("gpc:") || raw.length() < 6) {
            return null;
        }
        return GPC_SEGMENTS.get(raw.substring(4, 6));
    }

    /** {@code en:tomato-pastes} → "tomato pastes"; free text is lower-cased with punctuation as spaces. */
    static String normalise(String raw) {
        String s = raw.toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon > 0 && colon <= 3) {
            s = s.substring(colon + 1);
        }
        return s.replaceAll("[^a-z0-9]+", " ").strip();
    }
}
