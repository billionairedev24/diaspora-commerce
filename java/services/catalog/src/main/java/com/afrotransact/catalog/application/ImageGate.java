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
 *
 * <p>A picture can also be named by media asset id ({@link ProductImage#isHeld()}): a photo held for review has no
 * public URL, so the listing refers to it by id. The gate resolves each such reference server-side through media: a
 * cleared photo becomes an ordinary picture with its public URL, a held one is kept as a reference (the listing can be
 * saved, but {@link Outcome#held()} tells the writer it must not go live), a rejected, missing or foreign one is
 * refused. Only the listing's own pictures (and the label photo) can be given by id; a variant's pictures cannot. This resolution is not a rule that can run in shadow mode: it always applies.
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
    default Outcome check(List<ProductImage> images, List<List<ProductImage>> variantImages, String productTypeCode,
                          Set<String> alreadyAccepted) {
        return check(images, variantImages, productTypeCode, alreadyAccepted, null);
    }

    /**
     * Judges the pictures of a listing owned by an organisation; picture references by asset id must belong to it.
     *
     * @param ownerOrgId the organisation that owns the listing (null: not checked, for internal callers only)
     */
    Outcome check(List<ProductImage> images, List<List<ProductImage>> variantImages, String productTypeCode,
                  Set<String> alreadyAccepted, String ownerOrgId);

    /**
     * Asks media again about the photos a stored listing still holds (a held photo may have been cleared since).
     *
     * @return the pictures as they are now: cleared photos carry their public URL, still-held ones are unchanged;
     *         an empty result when the listing holds no photo
     */
    Resolved resolveHeld(List<ProductImage> images, String ownerOrgId);

    /**
     * What media answered for the held photos of a stored listing.
     *
     * @param images    the listing's pictures with cleared photos published
     * @param stillHeld true when at least one photo is still held for review
     * @param rejected  true when media rejected a held photo (it will never be cleared)
     */
    record Resolved(List<ProductImage> images, boolean stillHeld, boolean rejected) {
    }

    /**
     * The result of the gate.
     *
     * @param errors problems by field, empty when the pictures may be used
     * @param images the listing's pictures with media's measurements where media knew them (a held photo stays a
     *               reference by asset id)
     * @param held   true when a picture is held for review: the listing may be saved and submitted but must not go
     *               live until the photo is cleared
     */
    record Outcome(Map<String, String> errors, List<ProductImage> images, boolean held) {

        public Outcome {
            errors = Map.copyOf(errors);
            images = List.copyOf(images);
        }

        public Outcome(Map<String, String> errors, List<ProductImage> images) {
            this(errors, images, false);
        }
    }
}
