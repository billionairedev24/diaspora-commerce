package com.afrotransact.catalog.port;

import java.util.List;

import com.afrotransact.catalog.domain.images.ImageCheckResult;

/**
 * Asks media-service for the verdict of published images ({@code POST /v1/assets/verify}) and for a fresh
 * measurement of old ones ({@code POST /v1/assets/rescore}). Catalog never trusts the width and height a client
 * sends: this port is where the truth about an image comes from. Both calls answer one result per item, in order.
 * They throw {@link ImageImporter.ImageServiceUnavailable} when media cannot be reached.
 */
public interface ImageVerifier {

    /** The verdict of each image for the slot it is used in, from what media measured when it was uploaded. */
    List<ImageCheckResult> verify(List<Item> items);

    /** Measures each image again (legacy images, changed rules), stores the result at media and returns it. */
    List<ImageCheckResult> rescore(List<Item> items);

    /**
     * An image to judge.
     *
     * @param url             public URL of the image (empty when the image is asked for by asset id)
     * @param slot            {@code MAIN}, {@code OTHER}, {@code SWATCH} or {@code LABEL}
     * @param productTypeCode product type (apparel may show a model when the rules allow it); may be null
     * @param assetId         media asset id of a photo that is held for review (it has no public URL); may be null
     * @param ownerOrg        with an asset id: the organisation that must own it
     */
    record Item(String url, String slot, String productTypeCode, String assetId, String ownerOrg) {

        public Item(String url, String slot, String productTypeCode) {
            this(url, slot, productTypeCode, null, null);
        }
    }
}
