package com.afrotransact.catalog.domain.taxonomy;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.AttributeSection;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.ProductTypeTraits;

/**
 * The rules staff edits of the taxonomy must satisfy: names, codes, the three-level shape and what a product type's
 * attribute list may contain. Pure functions; every violation names the form field so the console can show it.
 */
public final class TaxonomyRules {

    public static final int MAX_NAME = 80;
    public static final int MIN_REASON = 3;
    public static final int MAX_REASON = 1000;
    public static final int MAX_ATTRIBUTES = 40;
    public static final int MAX_OPTIONS = 60;
    /** The key whose options tax-returns maps to tax codes: staff cannot change what it offers. */
    public static final String TAX_CLASS_KEY = "tax_class";
    /** Separator of the human path; a name cannot contain it. */
    public static final String PATH_SEPARATOR = " › ";

    private static final Pattern NODE_CODE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern TYPE_CODE = Pattern.compile("^[a-z][a-z0-9_]{1,39}$");
    private static final Pattern ATTRIBUTE_KEY = Pattern.compile("^[a-z][a-z0-9_]{1,39}$");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");
    private static final int MAX_CODE = 120;

    private TaxonomyRules() {
    }

    /** Lower-case words joined by hyphens, accents removed ("Crème & oils" becomes "creme-oils"). */
    public static String slug(String text) {
        String folded = DIACRITICS.matcher(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD))
                .replaceAll("").toLowerCase(Locale.ROOT);
        String slug = NON_WORD.matcher(folded).replaceAll("-").replaceAll("^-+|-+$", "");
        return slug.length() > MAX_CODE ? slug.substring(0, MAX_CODE).replaceAll("-+$", "") : slug;
    }

    /** The trimmed reason, 3 to 1000 characters; every taxonomy change carries one. */
    public static String reason(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.length() < MIN_REASON) {
            throw new CatalogRuleViolation("Say why you are making this change",
                    Map.of("reason", "At least " + MIN_REASON + " characters"));
        }
        if (r.length() > MAX_REASON) {
            throw new CatalogRuleViolation("The reason is too long", Map.of("reason", "At most " + MAX_REASON + " characters"));
        }
        return r;
    }

    /** The trimmed display name: 2 to 80 characters, no path separator or control characters. */
    public static String name(String name, String field) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.length() < 2 || n.length() > MAX_NAME) {
            throw new CatalogRuleViolation("Names have 2 to " + MAX_NAME + " characters",
                    Map.of(field, "2 to " + MAX_NAME + " characters"));
        }
        if (n.contains("›") || n.chars().anyMatch(Character::isISOControl)) {
            throw new CatalogRuleViolation("That name has characters that cannot be used",
                    Map.of(field, "Remove › and control characters"));
        }
        return n;
    }

    public static String nodeCode(String code) {
        String c = code == null ? "" : code.trim();
        if (c.length() > MAX_CODE || !NODE_CODE.matcher(c).matches()) {
            throw new CatalogRuleViolation("Codes are lower-case words joined by hyphens",
                    Map.of("code", "Use a-z, 0-9 and hyphens, for example fashion-women"));
        }
        return c;
    }

    public static String typeCode(String code) {
        String c = code == null ? "" : code.trim();
        if (!TYPE_CODE.matcher(c).matches()) {
            throw new CatalogRuleViolation("Product type codes are lower-case letters, digits and underscores",
                    Map.of("code", "Start with a letter; a-z, 0-9 and _ (2 to 40 characters)"));
        }
        return c;
    }

    /** The code a product type gets from its label when staff do not choose one ("Hair care" becomes "hair_care"). */
    public static String typeCodeFrom(String label) {
        String code = slug(label).replace('-', '_');
        if (code.isEmpty() || !Character.isLetter(code.charAt(0))) {
            code = "type_" + code;
        }
        return code.length() > 40 ? code.substring(0, 40).replaceAll("_+$", "") : code;
    }

    /** The level a node of {@code level} sits under: null for departments. */
    public static CategoryLevel parentLevel(CategoryLevel level) {
        return switch (level) {
            case DEPARTMENT -> null;
            case CATEGORY -> CategoryLevel.DEPARTMENT;
            case PRODUCT_TYPE -> CategoryLevel.CATEGORY;
        };
    }

    /** The path of a node: the parent's path and its name. */
    public static String pathOf(String parentPath, String name) {
        return parentPath == null ? name : parentPath + PATH_SEPARATOR + name;
    }

    /** Whether the trait combination makes sense: perishable food is food. */
    public static ProductTypeTraits traits(ProductTypeTraits traits) {
        ProductTypeTraits t = traits == null ? ProductTypeTraits.NONE : traits;
        if (t.perishable() && !t.food()) {
            throw new CatalogRuleViolation("Only food can be perishable", Map.of("traits", "Perishable needs food"));
        }
        return t;
    }

    /**
     * Checks a product type's attribute list and returns it normalised. Keys are unique, enumerated attributes have
     * allowed values, units belong to numbers, FOOD and REGULATED sections need the matching trait, and the tax
     * question's options (mapped to tax codes by tax-returns) are not edited here.
     *
     * @param current the stored list (null for a new type), to protect the tax question
     */
    public static List<AttributeDefinition> attributes(List<AttributeDefinition> attributes, ProductTypeTraits traits,
                                                       List<AttributeDefinition> current) {
        if (attributes == null) {
            throw new CatalogRuleViolation("Attributes are missing", Map.of("attributes", "Required"));
        }
        if (attributes.size() > MAX_ATTRIBUTES) {
            throw new CatalogRuleViolation("A product type has at most " + MAX_ATTRIBUTES + " attributes",
                    Map.of("attributes", "At most " + MAX_ATTRIBUTES));
        }
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        List<AttributeDefinition> out = new ArrayList<>();
        for (int i = 0; i < attributes.size(); i++) {
            AttributeDefinition a = attributes.get(i);
            String at = "attributes[" + i + "].";
            if (a.key() == null || !ATTRIBUTE_KEY.matcher(a.key()).matches()) {
                errors.put(at + "key", "Lower-case letters, digits and _ (2 to 40), starting with a letter");
            } else if (!seen.add(a.key())) {
                errors.put(at + "key", "Used twice");
            }
            String label = a.label() == null ? "" : a.label().trim();
            if (label.isEmpty() || label.length() > 120) {
                errors.put(at + "label", "1 to 120 characters");
            }
            List<String> options = a.options().stream().map(String::trim).filter(o -> !o.isEmpty()).toList();
            if (a.kind() == AttributeKind.ENUM && options.isEmpty()) {
                errors.put(at + "options", "Add the values to choose from");
            } else if (a.kind() != AttributeKind.ENUM && !options.isEmpty()) {
                errors.put(at + "options", "Only choice attributes have values");
            } else if (options.size() > MAX_OPTIONS || options.stream().distinct().count() != options.size()
                    || options.stream().anyMatch(o -> o.length() > 80)) {
                errors.put(at + "options", "Up to " + MAX_OPTIONS + " different values of at most 80 characters");
            }
            if (a.kind() != AttributeKind.NUMBER && !a.units().isEmpty()) {
                errors.put(at + "units", "Only numbers have units");
            } else if (a.units().stream().anyMatch(u -> u.isBlank() || u.length() > 20) || a.units().size() > 8) {
                errors.put(at + "units", "Up to 8 units of at most 20 characters");
            }
            if (a.applies()) {
                if (a.section() == AttributeSection.FOOD && !traits.food()) {
                    errors.put(at + "section", "The food section needs the food trait");
                }
                if (a.section() == AttributeSection.REGULATED && !traits.regulated()) {
                    errors.put(at + "section", "The regulated section needs the regulated trait");
                }
            }
            String help = a.help() == null ? null : a.help().trim();
            if (help != null && help.length() > 200) {
                errors.put(at + "help", "At most 200 characters");
            }
            out.add(new AttributeDefinition(a.key(), label, options, a.applies() && a.required(),
                    a.kind() == AttributeKind.MULTILINE, a.kind(), a.units(), a.section(), help, a.applies()));
        }
        protectTaxQuestion(out, current, errors);
        if (!errors.isEmpty()) {
            throw new CatalogRuleViolation("The attributes have " + errors.size() + " problem(s)", errors);
        }
        return List.copyOf(out);
    }

    private static void protectTaxQuestion(List<AttributeDefinition> next, List<AttributeDefinition> current,
                                           Map<String, String> errors) {
        if (current == null) {
            return;
        }
        for (AttributeDefinition before : current) {
            if (!TAX_CLASS_KEY.equals(before.key())) {
                continue;
            }
            AttributeDefinition after = next.stream().filter(a -> TAX_CLASS_KEY.equals(a.key())).findFirst().orElse(null);
            if (after == null) {
                errors.put("attributes", "The sales-tax question (" + TAX_CLASS_KEY + ") cannot be removed; stop asking it instead");
            } else if (!after.options().equals(before.options()) || after.kind() != before.kind()
                    || !after.label().equals(before.label())) {
                errors.put("attributes", "The sales-tax question's wording and choices are set by tax-returns");
            }
        }
    }
}
