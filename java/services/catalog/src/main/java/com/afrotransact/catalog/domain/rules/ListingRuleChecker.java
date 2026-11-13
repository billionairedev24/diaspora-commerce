package com.afrotransact.catalog.domain.rules;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.PackageDimensions;

/**
 * Judges a listing by the rules of its category. The one place that turns the matrix statuses into messages: the
 * product draft policy (form and API) and the bulk upload both call it, so they never disagree. Messages are plain
 * words; keys are the field paths of the API ({@code attributes.material}, {@code variants[0].packaging}).
 *
 * <p>Document slots are collected and marked for the approval engine; they never block a listing here.
 */
public final class ListingRuleChecker {

    private ListingRuleChecker() {
    }

    /** Field path to message for everything the rules refuse; empty when the listing satisfies them. */
    public static Map<String, String> check(CategoryRules rules, ListingSubmission s) {
        Map<String, String> errors = new LinkedHashMap<>();
        description(rules, s, errors);
        labelPhoto(rules, s, errors);
        dangerousGoods(rules, s, errors);
        attributes(rules, s, errors);
        sizes(rules, s, errors);
        variants(rules, s, errors);
        return errors;
    }

    private static void description(CategoryRules rules, ListingSubmission s, Map<String, String> errors) {
        if (rules.status(RuleField.DESCRIPTION).expectsAnswer()
                && (s.description() == null || s.description().isBlank())) {
            errors.put("description", "Describe the product for shoppers");
        }
    }

    private static void labelPhoto(CategoryRules rules, ListingSubmission s, Map<String, String> errors) {
        FieldStatus status = rules.status(RuleField.LABEL_PHOTO);
        if (s.labelPhotos() > 0 || !status.expectsAnswer()) {
            return;
        }
        if (status == FieldStatus.REQUIRED_OR_EXEMPTION && !s.reason(RuleField.LABEL_PHOTO).isEmpty()) {
            return;
        }
        errors.put("labelPhoto", status == FieldStatus.REQUIRED
                ? "Add a photo of the label: " + labelWhat(rules)
                : "Add a photo of the label, or say why you cannot");
    }

    private static String labelWhat(CategoryRules rules) {
        return switch (rules.kindCode()) {
            case "HLT" -> "the Drug Facts or Supplement Facts panel";
            case "BEA" -> "the ingredient list";
            default -> "the ingredients and nutrition panel";
        };
    }

    private static void dangerousGoods(CategoryRules rules, ListingSubmission s, Map<String, String> errors) {
        if (rules.status(RuleField.DANGEROUS_GOODS).expectsAnswer() && s.dangerous() == null) {
            errors.put("dangerousGoods", "Answer yes or no: is it an aerosol, flammable, battery, candle or cleaner?");
        }
    }

    private static void attributes(CategoryRules rules, ListingSubmission s, Map<String, String> errors) {
        for (FieldRule rule : rules.fields()) {
            RuleField f = rule.field();
            if (f.attributeKey() == null || f == RuleField.BRAND || !rule.status().shown()) {
                continue;
            }
            String key = f.attributeKey();
            String value = s.attributes().getOrDefault(key, "");
            value = value == null ? "" : value.strip();
            String path = "attributes." + key;
            if (value.isEmpty()) {
                if (rule.status() == FieldStatus.REQUIRED_OR_EXEMPTION && s.reason(f).isEmpty()) {
                    errors.put(path, "Required, or say why it does not apply");
                }
                continue;
            }
            String problem = valueProblem(f, value);
            if (problem != null) {
                errors.put(path, problem);
            }
        }
    }

    /** Why an answer is not acceptable for the field, or null. */
    static String valueProblem(RuleField f, String value) {
        return switch (f) {
            case SERVINGS -> value.matches("\\d{1,6}") && Integer.parseInt(value) > 0 ? null : "Enter a whole number above zero";
            case ALLERGENS -> allergenProblem(value);
            case MATERIAL, MODEL_NUMBER, AGE_GRADE ->
                    value.length() > 120 ? "At most 120 characters" : null;
            default -> null;
        };
    }

    private static String allergenProblem(String value) {
        if ("None".equalsIgnoreCase(value)) {
            return null;
        }
        List<String> parts = Arrays.stream(value.split(",")).map(String::strip).filter(p -> !p.isEmpty()).toList();
        boolean ok = !parts.isEmpty() && parts.stream().allMatch(p -> RuleField.ALLERGEN_OPTIONS.stream()
                .anyMatch(o -> o.equalsIgnoreCase(p)));
        return ok ? null : "Tick the allergens it contains, or choose None";
    }

    private static void sizes(CategoryRules rules, ListingSubmission s, Map<String, String> errors) {
        if (s.sizedType() && rules.status(RuleField.SIZES) == FieldStatus.REQUIRED && !s.sizeChart()) {
            errors.put("sizeChartId", "Choose the size chart for these sizes");
        }
    }

    private static void variants(CategoryRules rules, ListingSubmission s, Map<String, String> errors) {
        FieldStatus id = rules.status(RuleField.PRODUCT_ID);
        for (int i = 0; i < s.variants().size(); i++) {
            ListingSubmission.VariantFacts v = s.variants().get(i);
            String p = "variants[" + i + "].";
            barcode(id, s.identifier(), v, p, errors);
            if (rules.physical()) {
                packaging(v.packaging(), p, errors);
            }
        }
    }

    private static void barcode(FieldStatus status, IdentifierRule rule, ListingSubmission.VariantFacts v, String p,
                                Map<String, String> errors) {
        if (!status.shown()) {
            return;
        }
        String digits = Gtin.digits(v.barcode());
        if (rule == IdentifierRule.ISBN) {
            if (!Gtin.isValid(digits) || digits.length() != 13 || !(digits.startsWith("978") || digits.startsWith("979"))) {
                errors.put(p + "gtin", "ISBN-13 required (978/979 prefix, valid check digit)");
            }
            return;
        }
        if (v.exempt()) {
            if (status == FieldStatus.REQUIRED) {
                errors.put(p + "gtin", "A barcode is required for this kind of product");
            }
            return;
        }
        if (digits.isEmpty()) {
            if (status != FieldStatus.OPTIONAL) {
                errors.put(p + "gtin", status == FieldStatus.REQUIRED ? "Required: enter the barcode"
                        : "Required, or request a barcode exemption");
            }
        } else if (!Gtin.isValid(digits)) {
            String fix = Gtin.suggestCorrection(digits);
            errors.put(p + "gtin", fix == null ? "Not a valid GTIN length" : "Invalid check digit — did you mean " + fix + "?");
        }
    }

    private static void packaging(PackageDimensions pkg, String p, Map<String, String> errors) {
        if (pkg == null) {
            errors.put(p + "packaging", "Add the package length, width, height and weight of the box as shipped");
            return;
        }
        String problem = pkg.problem();
        if (problem != null) {
            errors.put(p + "packaging", problem);
        }
    }

    /** True when the text names the field's label (used to read exemption reasons keyed by label in uploads). */
    public static boolean names(RuleField field, String label, String text) {
        String a = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        return a.equals(field.id()) || a.equals(label.toLowerCase(Locale.ROOT));
    }
}
