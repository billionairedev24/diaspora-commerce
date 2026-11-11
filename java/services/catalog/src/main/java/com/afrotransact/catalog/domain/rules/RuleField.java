package com.afrotransact.catalog.domain.rules;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.AttributeSection;

/**
 * The 31 fields of the listing rules matrix. The id is the stable key in rules.json; the control and the storage tell
 * the form, the API and the upload template how to ask for and keep the answer, so the three never disagree.
 */
public enum RuleField {
    TITLE("title", FieldControl.TEXT, null),
    BRAND("brand", FieldControl.BRAND, "brand"),
    PRODUCT_ID("product_id", FieldControl.BARCODE, null),
    DESCRIPTION("description", FieldControl.LONG_TEXT, null),
    KEYWORDS("keywords", FieldControl.KEYWORDS, null),
    IMAGES("images", FieldControl.IMAGES, null),
    LABEL_PHOTO("label_photo", FieldControl.PHOTO, null),
    CONDITION("condition", FieldControl.CONDITION, null),
    PRICE("price", FieldControl.MONEY, null),
    QUANTITY("quantity", FieldControl.WHOLE_NUMBER, null),
    HANDLING_DAYS("handling_days", FieldControl.WHOLE_NUMBER, null),
    SHIP_FROM("ship_from", FieldControl.SHIP_FROM, null),
    PACKAGE_SIZE("package_size", FieldControl.PACKAGE, null),
    PACKAGE_WEIGHT("package_weight", FieldControl.PACKAGE, null),
    COUNTRY_OF_ORIGIN("country_of_origin", FieldControl.TEXT, "origin"),
    DANGEROUS_GOODS("dangerous_goods", FieldControl.YES_NO, null),
    NET_WEIGHT("net_weight", FieldControl.MEASURE, "net_weight"),
    SERVINGS("servings", FieldControl.WHOLE_NUMBER, "servings"),
    SHELF_LIFE("shelf_life", FieldControl.MEASURE, "shelf"),
    STORAGE("storage", FieldControl.CHOICE, "storage"),
    INGREDIENTS("ingredients", FieldControl.LONG_TEXT, "ingredients"),
    ALLERGENS("allergens", FieldControl.MULTI_CHOICE, "allergens"),
    MATERIAL("material", FieldControl.TEXT, "material"),
    MODEL_NUMBER("model_number", FieldControl.TEXT, "model"),
    AGE_GRADE("age_grade", FieldControl.TEXT, "age_grade"),
    SIZES("sizes", FieldControl.SIZES, null),
    PERMIT("permit", FieldControl.DOCUMENT, null),
    BRAND_AUTHORIZATION("brand_authorization", FieldControl.DOCUMENT, null),
    SAFETY_CERTIFICATE("safety_certificate", FieldControl.DOCUMENT, null),
    LIABILITY_INSURANCE("liability_insurance", FieldControl.DOCUMENT, null),
    AUTHENTICITY_INVOICE("authenticity_invoice", FieldControl.DOCUMENT, null);

    /** Shelf life units, the first is the default. */
    public static final List<String> SHELF_UNITS = List.of("days", "months", "years");
    /** Net weight or volume units, the first is the default. */
    public static final List<String> NET_UNITS = List.of("oz", "lb", "g", "kg", "fl oz", "ml", "l");
    public static final List<String> STORAGE_OPTIONS = List.of("Room temperature", "Cool and dry", "Refrigerate", "Freeze");
    /** The "contains" tick list; "None" stands for no listed allergen. */
    public static final List<String> ALLERGEN_OPTIONS = List.of("Contains milk", "Contains eggs", "Contains fish",
            "Contains shellfish", "Contains tree nuts", "Contains peanuts", "Contains wheat", "Contains soy",
            "Contains sesame");
    /**
     * Detail keys that older product types asked on their own (INCI, temperature, permit text ...). The rules now own
     * those questions, so these keys are no longer asked and no longer required.
     */
    public static final List<String> SUPERSEDED_KEYS = List.of("inci", "temp", "permit", "safety", "age");

    private final String id;
    private final FieldControl control;
    private final String attributeKey;

    RuleField(String id, FieldControl control, String attributeKey) {
        this.id = id;
        this.control = control;
        this.attributeKey = attributeKey;
    }

    public String id() {
        return id;
    }

    /** The label shoppers and sellers read: the matrix name without its technical suffix. */
    public String plainLabel(String matrixLabel) {
        return switch (this) {
            case INGREDIENTS -> "Ingredients";
            case ALLERGENS -> "Allergens";
            case PRODUCT_ID -> "Barcode or ISBN";
            default -> matrixLabel;
        };
    }

    public FieldControl control() {
        return control;
    }

    /** The product attribute that keeps the answer; null for fields kept elsewhere (variant, listing, images). */
    public String attributeKey() {
        return attributeKey;
    }

    /** True for the document slots reviewed at approval. */
    public boolean isDocument() {
        return control == FieldControl.DOCUMENT;
    }

    /** True for the fields answered once per variant. */
    public boolean perVariant() {
        return this == PRODUCT_ID || this == PRICE || this == QUANTITY || this == PACKAGE_SIZE || this == PACKAGE_WEIGHT;
    }

    /** The options of a CHOICE or MULTI_CHOICE field; empty otherwise. */
    public List<String> options() {
        return switch (this) {
            case STORAGE -> STORAGE_OPTIONS;
            case ALLERGENS -> ALLERGEN_OPTIONS;
            default -> List.of();
        };
    }

    /** The units of a MEASURE field; empty otherwise. */
    public List<String> units() {
        return switch (this) {
            case SHELF_LIFE -> SHELF_UNITS;
            case NET_WEIGHT -> NET_UNITS;
            default -> List.of();
        };
    }

    public static Optional<RuleField> ofId(String id) {
        return Arrays.stream(values()).filter(f -> f.id.equals(id)).findFirst();
    }

    /** The field whose answer lives in this product attribute. */
    public static Optional<RuleField> ofAttributeKey(String key) {
        return Arrays.stream(values()).filter(f -> f.attributeKey != null && f.attributeKey.equals(key)).findFirst();
    }

    /** The attribute definition of an attribute-stored field, for the status it has in a category; null when hidden. */
    public AttributeDefinition definition(String label, String help, FieldStatus status) {
        if (attributeKey == null || !status.shown() || this == BRAND) {
            return null;
        }
        boolean required = status == FieldStatus.REQUIRED;
        AttributeSection section = isFoodFact() ? AttributeSection.FOOD : AttributeSection.DETAILS;
        return switch (control) {
            case LONG_TEXT -> new AttributeDefinition(attributeKey, label, List.of(), required, true,
                    AttributeKind.MULTILINE, List.of(), section, help);
            case CHOICE -> new AttributeDefinition(attributeKey, label, options(), required, false,
                    AttributeKind.ENUM, List.of(), section, help);
            case MEASURE -> new AttributeDefinition(attributeKey, label, List.of(), required, false,
                    AttributeKind.NUMBER, units(), section, help);
            case WHOLE_NUMBER -> new AttributeDefinition(attributeKey, label, List.of(), required, false,
                    AttributeKind.INTEGER, List.of(), section, help);
            default -> new AttributeDefinition(attributeKey, label, List.of(), required, false,
                    AttributeKind.TEXT, List.of(), section, help);
        };
    }

    private boolean isFoodFact() {
        return this == NET_WEIGHT || this == SERVINGS || this == SHELF_LIFE || this == STORAGE
                || this == INGREDIENTS || this == ALLERGENS;
    }
}
