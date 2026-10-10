package com.afrotransact.deliveryruns.domain;

import java.util.UUID;

/**
 * An uploaded media-service asset used in a creative slot.
 *
 * @param assetId media-service asset id
 * @param url     public URL
 * @param width   measured width (px)
 * @param height  measured height (px)
 */
public record CreativeRef(UUID assetId, String url, int width, int height) {

    public CreativeRef {
        if (assetId == null) {
            throw new RuleViolation("delivery-runs.invalid-creative", "A creative needs a media asset id");
        }
    }

    /** Warning flag {@code RATIO_OFF}: the image is more than 12 % off the slot's aspect ratio. */
    public boolean ratioOff(CreativeSlot slot) {
        return slot.ratioOff(width, height);
    }
}
