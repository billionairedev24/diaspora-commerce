package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.catalog.domain.ProductImage;

/**
 * Defence in depth for the image rules (S390): every path that stores a listing's pictures (the product form, the API,
 * a bulk upload, an image library attach) goes through the product writer, which asks this gate. The gate has media
 * judge each new picture for the slot it is used in (the first picture is the main image, the rest are additional,
 * the label photo is a label) and ignores the width and height a client sent: the numbers it hands back are media's.
 *
 * <p>With the mode {@code ENFORCE} a main image that is rejected, held for staff or unknown to media is refused with the
 * plain message media gave. Other pictures are refused when media rejects or holds them. In {@code SHADOW} mode the
 * verdicts are only counted. In {@code OFF} mode nothing is asked and the images pass unchanged. When media cannot be
 * asked while enforcing, the gate throws the image service's "unavailable" exception: nothing is stored and the seller
 * can try again.
 */
public interface ImageGate {

    /**
     * Judges the pictures of a listing.
     *
     * @param images          the listing's pictures in order (label photos have the role LABEL)
     * @param variantImages   the pictures of each variant (may be empty)
     * @param productTypeCode the product type (apparel may show a model when the rules allow it)
     * @param alreadyAccepted URLs the listing already has: a grandfathered picture is not judged again when only
     *                        other things change
     * @return the problems keyed like the product form's fields ({@code images[0]} is the main image) and the pictures
     *         with media's own size, bytes and type
     */
    Outcome check(List<ProductImage> images, List<List<ProductImage>> variantImages, String productTypeCode,
                  Set<String> alreadyAccepted);

    /**
     * The result of the gate.
     *
     * @param errors problems by field, empty when the pictures may be used
     * @param images the listing's pictures with media's measurements where media knew them
     */
    record Outcome(Map<String, String> errors, List<ProductImage> images) {

        public Outcome {
            errors = Map.copyOf(errors);
            images = List.copyOf(images);
        }
    }
}
