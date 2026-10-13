package com.afrotransact.catalog.domain.imports;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The library images an upload or an "attach matching images" run can use: the newest image per SKU and slot, and per
 * file name. Image cells may name a library file instead of a link ({@code ankara-front.jpg}); rows without images
 * use the library images named after their SKU or Parent SKU. Library images are stored in the product as
 * {@code library:<assetId>} until the import resolves them to the published image.
 */
public record ImageLibrary(List<LibraryImage> images) {

    public static final ImageLibrary EMPTY = new ImageLibrary(List.of());
    public static final String REF_PREFIX = "library:";
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    public ImageLibrary {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** An image cell entry that names a library file rather than a link: no scheme or path, an image extension. */
    public static boolean isFileName(String entry) {
        if (entry == null || entry.contains("://") || entry.contains("/") || entry.contains("\\")) {
            return false;
        }
        int dot = entry.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(entry.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static String ref(LibraryImage image) {
        return REF_PREFIX + image.assetId();
    }

    public static boolean isRef(String value) {
        return value != null && value.startsWith(REF_PREFIX);
    }

    public Optional<LibraryImage> byFileName(String fileName) {
        return images.stream().filter(i -> i.fileName() != null && i.fileName().equalsIgnoreCase(fileName))
                .findFirst();
    }

    public Optional<LibraryImage> byRef(String ref) {
        if (!isRef(ref)) {
            return Optional.empty();
        }
        String id = ref.substring(REF_PREFIX.length());
        return images.stream().filter(i -> id.equals(i.assetId())).findFirst();
    }

    /** MAIN then PT01–PT08 of a SKU (any status), one per slot. */
    public List<LibraryImage> productImages(String sku) {
        if (sku == null || sku.isBlank()) {
            return List.of();
        }
        String key = sku.strip().toUpperCase(Locale.ROOT);
        return images.stream().filter(i -> key.equals(i.sku()) && i.productSlot())
                .sorted(Comparator.comparing(LibraryImage::slot)).toList();
    }

    public Optional<LibraryImage> slot(String sku, String slot) {
        if (sku == null) {
            return Optional.empty();
        }
        String key = sku.strip().toUpperCase(Locale.ROOT);
        return images.stream().filter(i -> key.equals(i.sku()) && slot.equals(i.slot())).findFirst();
    }
}
