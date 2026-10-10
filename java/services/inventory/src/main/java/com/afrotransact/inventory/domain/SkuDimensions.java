package com.afrotransact.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Outer dimensions of one sellable unit of a SKU, in inches, used to bill FBA storage by volume (S206).
 *
 * @param sku      SKU
 * @param lengthIn length in inches (&gt; 0, at most 2 decimals)
 * @param widthIn  width in inches
 * @param heightIn height in inches
 */
public record SkuDimensions(String sku, BigDecimal lengthIn, BigDecimal widthIn, BigDecimal heightIn) {

    private static final BigDecimal CUBIC_INCHES_PER_CUBIC_FOOT = BigDecimal.valueOf(1728);
    private static final BigDecimal MAX_SIDE_IN = BigDecimal.valueOf(999);

    public SkuDimensions {
        Objects.requireNonNull(sku, "sku");
        Map<String, String> errors = new LinkedHashMap<>();
        check(errors, "lengthIn", lengthIn);
        check(errors, "widthIn", widthIn);
        check(errors, "heightIn", heightIn);
        if (!errors.isEmpty()) {
            throw new InventoryRuleViolation("inventory.invalid-dimensions", "Dimensions must be between 0 and 999 "
                    + "inches with at most 2 decimals", errors);
        }
    }

    /** Volume of one unit in cubic feet (6 decimals, rounded up so a tiny item never stores for free). */
    public BigDecimal unitCubicFeet() {
        return lengthIn.multiply(widthIn).multiply(heightIn)
                .divide(CUBIC_INCHES_PER_CUBIC_FOOT, 6, RoundingMode.CEILING);
    }

    private static void check(Map<String, String> errors, String field, BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(MAX_SIDE_IN) > 0 || value.stripTrailingZeros().scale() > 2) {
            errors.put(field, "Enter inches above 0 and up to 999, with at most 2 decimals");
        }
    }
}
