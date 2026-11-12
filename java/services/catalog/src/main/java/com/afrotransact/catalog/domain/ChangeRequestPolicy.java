package com.afrotransact.catalog.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** What a change request must carry for its type (S385). Pure rules; the services add who may ask. */
public final class ChangeRequestPolicy {

    public static final int MAX_TITLE = 200;
    public static final int MIN_TITLE = 3;
    public static final int MAX_ATTRIBUTE_VALUE = 500;
    public static final int MAX_BRAND = 120;
    private static final Pattern ATIN = Pattern.compile("AT[0-9A-Z]{8}");
    private static final Pattern ATTRIBUTE_KEY = Pattern.compile("[a-z][a-z0-9_]{0,59}");

    private ChangeRequestPolicy() {
    }

    /**
     * Why the request cannot be filed, by field; empty when it can.
     *
     * @param noteMax the longest note allowed (a setting)
     */
    public static Map<String, String> problems(ChangeRequestType type, Map<String, Object> payload, String note,
                                               int noteMax, String pageAtin) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (note != null && note.length() > noteMax) {
            errors.put("note", "At most " + noteMax + " characters");
        }
        switch (type) {
            case TITLE -> {
                String v = text(payload, "value");
                if (v.length() < MIN_TITLE || v.length() > MAX_TITLE) {
                    errors.put("payload.value", "Between " + MIN_TITLE + " and " + MAX_TITLE + " characters");
                }
            }
            case DESCRIPTION -> {
                String v = text(payload, "value");
                if (v.isEmpty() || v.length() > ListingDetails.MAX_DESCRIPTION) {
                    errors.put("payload.value", "Between 1 and " + ListingDetails.MAX_DESCRIPTION + " characters");
                }
            }
            case BRAND -> {
                String v = text(payload, "value");
                if (v.isEmpty() || v.length() > MAX_BRAND) {
                    errors.put("payload.value", "A brand name of at most " + MAX_BRAND + " characters");
                }
            }
            case ATTRIBUTE -> {
                if (!ATTRIBUTE_KEY.matcher(text(payload, "key")).matches()) {
                    errors.put("payload.key", "The name of the detail, for example material");
                }
                String v = text(payload, "value");
                if (v.isEmpty() || v.length() > MAX_ATTRIBUTE_VALUE) {
                    errors.put("payload.value", "Between 1 and " + MAX_ATTRIBUTE_VALUE + " characters");
                }
            }
            case CATEGORY -> {
                try {
                    UUID.fromString(text(payload, "categoryId"));
                } catch (IllegalArgumentException ex) {
                    errors.put("payload.categoryId", "The id of the category it belongs in");
                }
            }
            case IMAGE_ADD, IMAGE_REPLACE -> imageProblems(type, payload, errors);
            case NEW_VARIANT -> {
                if (!(payload.get("options") instanceof Map<?, ?> options) || options.isEmpty()) {
                    errors.put("payload.options", "Say which size, colour or other option is missing");
                }
            }
            case WRONG_MATCH -> {
                if (!ATIN.matcher(text(payload, "variantAtin").toUpperCase(Locale.ROOT)).matches()) {
                    errors.put("payload.variantAtin", "The ATIN of the product you were shown");
                }
                if (text(payload, "gtin").isEmpty()) {
                    errors.put("payload.gtin", "The barcode you typed");
                }
            }
            case DUPLICATE_PAGE -> {
                String other = text(payload, "duplicateOfAtin").toUpperCase(Locale.ROOT);
                if (!ATIN.matcher(other).matches()) {
                    errors.put("payload.duplicateOfAtin", "The ATIN of the other page");
                } else if (other.equals(pageAtin)) {
                    errors.put("payload.duplicateOfAtin", "That is the same page");
                }
            }
            default -> {
            }
        }
        return errors;
    }

    /** The field a request is stored under: the attribute key, the image slot, else empty. */
    public static String fieldOf(ChangeRequestType type, String requested, Map<String, Object> payload) {
        return switch (type) {
            case ATTRIBUTE -> text(payload, "key");
            case IMAGE_ADD, IMAGE_REPLACE -> "MAIN".equalsIgnoreCase(requested) ? "MAIN" : "OTHER";
            default -> "";
        };
    }

    /** The picture of an image request, as the page keeps it. */
    public static ProductImage imageOf(Map<String, Object> payload) {
        return new ProductImage(text(payload, "url"), number(payload, "width"), number(payload, "height"),
                number(payload, "bytes"), text(payload, "contentType"), null, null);
    }

    private static void imageProblems(ChangeRequestType type, Map<String, Object> payload, Map<String, String> errors) {
        String url = text(payload, "url");
        if (!url.startsWith("https://") || url.length() > 2000) {
            errors.put("payload.url", "A link that starts with https");
        }
        ProductImage image = new ProductImage(url.isEmpty() ? "x" : url, number(payload, "width"),
                number(payload, "height"), number(payload, "bytes"), text(payload, "contentType"));
        if (!image.allowedType()) {
            errors.put("payload.contentType", "Use a JPG, PNG or WebP file");
        }
        if (image.bytes() <= 0 || image.bytes() > ProductImage.MAX_BYTES) {
            errors.put("payload.bytes", "At most 8 MB");
        }
        if (image.width() <= 0 || image.height() <= 0) {
            errors.put("payload.width", "The width and height of the picture in pixels");
        }
        if (type == ChangeRequestType.IMAGE_REPLACE && number(payload, "position") < 0) {
            errors.put("payload.position", "Which picture to replace (0 is the main picture)");
        }
    }

    /** A text value of the payload, trimmed; empty when absent. */
    public static String text(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v == null ? "" : v.toString().strip();
    }

    /** A whole number of the payload; 0 when absent, -1 when it is not a number. */
    public static int number(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().strip());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
