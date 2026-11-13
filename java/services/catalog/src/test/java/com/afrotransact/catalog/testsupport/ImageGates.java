package com.afrotransact.catalog.testsupport;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.catalog.application.ImageGate;
import com.afrotransact.catalog.domain.ProductImage;

/** Image gates for tests that are not about the image rules. */
public final class ImageGates {

    private ImageGates() {
    }

    /** Lets every picture through unchanged, as the gate does when the image rules are off. */
    public static ImageGate passthrough() {
        return new ImageGate() {
            @Override
            public Outcome check(List<ProductImage> images, List<List<ProductImage>> variantImages, String productTypeCode,
                                 Set<String> alreadyAccepted, String ownerOrgId) {
                return new Outcome(Map.of(), images);
            }

            @Override
            public Resolved resolveHeld(List<ProductImage> images, String ownerOrgId) {
                return new Resolved(images, false, false);
            }
        };
    }
}
