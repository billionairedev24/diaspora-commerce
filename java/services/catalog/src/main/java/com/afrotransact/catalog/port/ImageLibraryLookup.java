package com.afrotransact.catalog.port;

import java.util.Collection;

import com.afrotransact.catalog.domain.imports.ImageLibrary;

/**
 * The organisation's image library in media-service ({@code POST /v1/library/lookup}): per SKU the newest READY or
 * FLAGGED image of each slot, and per file name the newest image with that name. Throws
 * {@link ImageImporter.ImageServiceUnavailable} when media cannot be reached.
 */
public interface ImageLibraryLookup {

    ImageLibrary lookup(String ownerOrg, Collection<String> skus, Collection<String> fileNames);
}
