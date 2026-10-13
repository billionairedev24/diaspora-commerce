package com.afrotransact.catalog.domain.imports;

/**
 * One image of an upload row as the preview shows it (S310).
 *
 * @param slot   MAIN, PT01–PT08 or SWCH
 * @param source LINK (an https URL cell), FILE (a library file name in an image cell) or SKU (a library file named
 *               after the SKU)
 * @param ref    the link, the file name or the matched library file name
 * @param state  FOUND, LINK (copied when the import runs), HELD (held by moderation), MISSING or BAD_LINK
 * @param url    thumbnail of a FOUND library image
 */
public record RowImage(String slot, String source, String ref, String state, String url, Integer width,
                       Integer height, String message) {
}
