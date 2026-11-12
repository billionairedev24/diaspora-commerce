package com.afrotransact.catalog.testsupport;

import java.util.Map;

import com.afrotransact.catalog.application.ImageGate;

/** Image gates for tests that are not about the image rules. */
public final class ImageGates {

    private ImageGates() {
    }

    /** Lets every picture through unchanged, as the gate does when the image rules are off. */
    public static ImageGate passthrough() {
        return (images, variantImages, productTypeCode, alreadyAccepted) -> new ImageGate.Outcome(Map.of(), images);
    }
}
