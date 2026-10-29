package com.afrotransact.catalog.domain.imports;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The image columns of the upload template: {@code main_image_url}, {@code other_image_url_1..8} and
 * {@code swatch_image_url}, each a public link or an image library file name. They are folded into the one
 * {@code image_urls} cell (main, others, swatch; then anything already in that cell) before validation, so the rest of
 * the import reads one list.
 */
public final class ImageColumns {

    public static final String MAIN = "main_image_url";
    public static final String OTHER_PREFIX = "other_image_url_";
    public static final int OTHER_COUNT = 8;
    public static final String SWATCH = "swatch_image_url";

    private ImageColumns() {
    }

    /** The template columns, in order. */
    public static List<ImportColumn> template() {
        List<ImportColumn> out = new ArrayList<>();
        out.add(new ImportColumn(MAIN, "Main image URL", "The main image: a public https link to a JPG, PNG or WebP "
                + "(at least 1000 × 1000 px), or a file name from your image library. Leave all image columns empty to "
                + "use the library images named after the SKU (SKU.MAIN.jpg, SKU.PT01.jpg …).", false, ColumnKind.URLS,
                List.of(), "https://example.com/ankara-front.jpg", true));
        for (int i = 1; i <= OTHER_COUNT; i++) {
            out.add(new ImportColumn(OTHER_PREFIX + i, "Other image URL " + i, i == 1
                    ? "More images: a public https link or an image library file name (e.g. SKU123.PT01.jpg), one "
                            + "per column, up to eight." : "Another image link or library file name.", false,
                    ColumnKind.URLS, List.of(), i == 1 ? "https://example.com/ankara-back.jpg" : "", true));
        }
        out.add(new ImportColumn(SWATCH, "Swatch image URL", "A small picture of this colour or pattern: a link or a "
                + "library file name (SKU.SWCH.jpg).", false, ColumnKind.URLS, List.of(), "", true));
        return out;
    }

    /** True when the header has one of the dedicated image columns. */
    public static boolean present(List<String> header) {
        return header.stream().anyMatch(h -> slot(h));
    }

    /**
     * Folds the dedicated image columns into {@code image_urls} (added when missing) and drops them; a sheet without
     * them is returned as is.
     */
    public static ParsedSheet fold(ParsedSheet sheet) {
        List<String> header = sheet.header();
        if (!present(header)) {
            return sheet;
        }
        List<Integer> slots = new ArrayList<>();
        int target = -1;
        for (int i = 0; i < header.size(); i++) {
            if (slot(header.get(i))) {
                slots.add(i);
            } else if (target < 0 && isImageCell(header.get(i))) {
                target = i;
            }
        }
        List<String> newHeader = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            if (!slots.contains(i)) {
                newHeader.add(header.get(i));
            }
        }
        int imageCol = target < 0 ? newHeader.size() : newHeader.indexOf(header.get(target));
        if (target < 0) {
            newHeader.add(ImportColumns.IMAGES);
        }
        List<SheetRow> rows = new ArrayList<>();
        for (SheetRow r : sheet.rows()) {
            Set<String> entries = new LinkedHashSet<>();
            slots.forEach(i -> entries.addAll(RowImages.entries(r.cell(i))));
            if (target >= 0) {
                entries.addAll(RowImages.entries(r.cell(target)));
            }
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < header.size(); i++) {
                if (!slots.contains(i)) {
                    cells.add(r.cell(i));
                }
            }
            while (cells.size() <= imageCol) {
                cells.add("");
            }
            cells.set(imageCol, String.join(" ", entries));
            rows.add(new SheetRow(r.rowNumber(), cells));
        }
        return new ParsedSheet(newHeader, rows);
    }

    private static boolean isImageCell(String header) {
        String h = ImportColumns.normalise(header);
        return h.equals(ImportColumns.IMAGES) || h.equals("image urls");
    }

    private static boolean slot(String header) {
        String h = ImportColumns.normalise(header);
        if (h.equals(MAIN) || h.equals(SWATCH) || h.equals("main image url") || h.equals("swatch image url")) {
            return true;
        }
        String number = h.startsWith(OTHER_PREFIX) ? h.substring(OTHER_PREFIX.length())
                : h.startsWith("other image url ") ? h.substring("other image url ".length()) : null;
        return number != null && number.matches("[1-8]");
    }
}
