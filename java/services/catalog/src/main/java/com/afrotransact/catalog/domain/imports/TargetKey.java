package com.afrotransact.catalog.domain.imports;

import java.util.Locale;

import com.afrotransact.catalog.domain.Gtin;

/**
 * How an offer row names its page: a barcode, an ATIN, or a brand with a part number (S393). One of the three is set; the
 * barcode wins over the ATIN, and the ATIN over the brand and part number.
 *
 * @param gtin  barcode digits as typed (spaces and dashes dropped); null when none
 * @param atin  variant ATIN, upper case; null when none
 */
public record TargetKey(String gtin, String atin, String brand, String mpn) {

    public static TargetKey of(String gtinCell, String atinCell, String brandCell, String mpnCell) {
        String gtin = Gtin.digits(gtinCell);
        String atin = atinCell == null ? "" : atinCell.strip().toUpperCase(Locale.ROOT);
        String brand = brandCell == null ? "" : brandCell.strip();
        String mpn = mpnCell == null ? "" : mpnCell.strip();
        return new TargetKey(gtin.isEmpty() ? null : gtin, atin.isEmpty() ? null : atin, brand.isEmpty() ? null : brand,
                mpn.isEmpty() ? null : mpn);
    }

    /** Whether the row names a page at all. */
    public boolean present() {
        return gtin != null || atin != null || (brand != null && mpn != null);
    }

    /** A stable text key for maps: the form of naming that is used. */
    public String id() {
        if (gtin != null) {
            return "gtin:" + gtin;
        }
        if (atin != null) {
            return "atin:" + atin;
        }
        if (brand != null && mpn != null) {
            return "mpn:" + brand.toLowerCase(Locale.ROOT) + "|" + mpn.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        }
        return "";
    }
}
