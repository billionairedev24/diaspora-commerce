package com.afrotransact.catalog.domain.imports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** The images of upload rows for the preview: what each image cell entry or SKU-named library file resolves to. */
public final class RowImages {

    private static final Pattern SPLIT = Pattern.compile("[\\s|]+");
    private static final int MAX_SLOTS = 9;

    private RowImages() {
    }

    /** The entries of an image cell, in order. */
    public static List<String> entries(String cell) {
        if (cell == null || cell.isBlank()) {
            return List.of();
        }
        return Arrays.stream(SPLIT.split(cell.strip())).map(String::strip).filter(s -> !s.isEmpty()).distinct()
                .toList();
    }

    /** The library file names an image cell names. */
    public static List<String> fileNames(String cell) {
        return entries(cell).stream().filter(ImageLibrary::isFileName).toList();
    }

    /**
     * A row's images: the image cell's entries by position (MAIN, PT01 …), or, when the cell is empty, the library
     * images named after the row's SKU, else its Parent SKU (product images and swatch).
     */
    public static List<RowImage> of(ImageLibrary library, String cell, String sku, String parentSku) {
        List<String> entries = entries(cell);
        List<RowImage> out = new ArrayList<>();
        if (!entries.isEmpty()) {
            for (int i = 0; i < entries.size() && i < MAX_SLOTS; i++) {
                String slot = i == 0 ? "MAIN" : "PT0" + i;
                String e = entries.get(i);
                if (ImageLibrary.isFileName(e)) {
                    LibraryImage img = library.byFileName(e).orElse(null);
                    out.add(img == null
                            ? new RowImage(slot, "FILE", e, "MISSING", null, null, null, "Not in your image library")
                            : found(slot, "FILE", e, img));
                } else {
                    String problem = ImportValidator.urlProblem(e);
                    out.add(new RowImage(slot, "LINK", e, problem == null ? "LINK" : "BAD_LINK", null, null, null,
                            problem));
                }
            }
            return out;
        }
        for (String key : new String[] {sku, parentSku}) {
            List<LibraryImage> images = new ArrayList<>(library.productImages(key));
            library.slot(key, "SWCH").ifPresent(images::add);
            if (!images.isEmpty()) {
                images.forEach(img -> out.add(found(img.slot(), "SKU", img.fileName(), img)));
                return out;
            }
        }
        return out;
    }

    private static RowImage found(String slot, String source, String ref, LibraryImage img) {
        if (img.ready()) {
            return new RowImage(slot, source, ref, "FOUND", img.url(), img.width(), img.height(), null);
        }
        return new RowImage(slot, source, ref, "HELD", null, null, null, "Held for review by moderation");
    }
}
