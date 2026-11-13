package com.afrotransact.catalog.domain.imports;

import java.util.Locale;

import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.matching.PackSize;
import com.afrotransact.catalog.domain.matching.ProductNames;

/**
 * How an offer row names its page: a barcode, an ATIN, or the product's name with an optional brand, size or pack and
 * category (S393). One of the three ways is used; the barcode wins over the ATIN, and the ATIN over the name.
 *
 * @param gtin     barcode digits as typed (spaces and dashes dropped); null when none
 * @param atin     variant ATIN, upper case; null when none
 * @param name     the product name as typed (the size and the brand may be inside it); null when none
 * @param brand    optional
 * @param size     optional size or pack ("5 kg", "6 pack")
 * @param category optional category path
 */
public record TargetKey(String gtin, String atin, String name, String brand, String size, String category) {

    public static TargetKey of(String gtinCell, String atinCell, String nameCell, String brandCell, String sizeCell,
                               String categoryCell) {
        String gtin = Gtin.digits(gtinCell);
        String atin = atinCell == null ? "" : atinCell.strip().toUpperCase(Locale.ROOT);
        return new TargetKey(gtin.isEmpty() ? null : gtin, atin.isEmpty() ? null : atin, blank(nameCell), blank(brandCell),
                blank(sizeCell), blank(categoryCell));
    }

    /** A key that names a page by its barcode only. */
    public static TargetKey ofBarcode(String digits) {
        return new TargetKey(digits, null, null, null, null, null);
    }

    private static String blank(String cell) {
        String v = cell == null ? "" : cell.strip();
        return v.isEmpty() ? null : v;
    }

    /** Whether the row names a page at all. */
    public boolean present() {
        return gtin != null || atin != null || name != null;
    }

    /** The name and the size as one text, the way the search reads it. */
    public String searchText() {
        return size == null ? name : name + " " + size;
    }

    /** A stable text key for maps: the form of naming that is used. */
    public String id() {
        if (gtin != null) {
            return "gtin:" + gtin;
        }
        if (atin != null) {
            return "atin:" + atin;
        }
        if (name != null) {
            PackSize.Found found = PackSize.find(searchText()).orElse(null);
            String text = found == null ? ProductNames.normalise(searchText()) : ProductNames.normalise(found.rest());
            return "name:" + text + "|" + (found == null ? "" : found.size().key()) + "|" + ProductNames.normalise(brand)
                    + "|" + ProductNames.normalise(category);
        }
        return "";
    }
}
