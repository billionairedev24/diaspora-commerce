package com.afrotransact.trustops.domain;

/**
 * Why an item is removed. Listing codes are sent to catalog in the moderation note and shown to the seller; review
 * codes are bookings-service's review rejection reasons (sent as {@code reasonCode}). {@link #OTHER} fits both and
 * needs a note.
 */
public enum ModerationReasonCode {
    PROHIBITED_ITEM("Prohibited item (USDA / CDC / FDA rules)", Applies.LISTINGS),
    UNAPPROVED_HEALTH_CLAIM("Unapproved health or medical claim", Applies.LISTINGS),
    RESTRICTED_INGREDIENT("Restricted or banned ingredient", Applies.LISTINGS),
    COUNTERFEIT("Possible counterfeit or trademark infringement", Applies.LISTINGS),
    IMAGE_POLICY("Image copied, watermarked or off-policy", Applies.LISTINGS),
    MISLEADING_CONTENT("Misleading title, description or details", Applies.LISTINGS),
    LABELLING("Labelling or allergen information missing", Applies.LISTINGS),
    SHIPPING_METHOD("Unsuitable shipping method (perishables, hazmat)", Applies.LISTINGS),
    DUPLICATE_LISTING("Duplicate of an existing product page", Applies.LISTINGS),
    WRONG_CATEGORY("Listed in the wrong category", Applies.LISTINGS),
    ABUSIVE("Abusive, hateful or harassing language", Applies.REVIEWS),
    PERSONAL_INFORMATION("Shares personal information", Applies.REVIEWS),
    SPAM("Spam or advertising", Applies.REVIEWS),
    OFF_TOPIC("Not about the booked service or the product", Applies.REVIEWS),
    CONFLICT_OF_INTEREST("Conflict of interest (written by or for the provider)", Applies.REVIEWS),
    OTHER("Other (see note)", Applies.ALL);

    /** Which kinds of queue items a code can remove. */
    private enum Applies {
        LISTINGS,
        REVIEWS,
        ALL
    }

    private final String label;
    private final Applies applies;

    ModerationReasonCode(String label, Applies applies) {
        this.label = label;
        this.applies = applies;
    }

    public String label() {
        return label;
    }

    /** Whether this code can remove an item of {@code type}. */
    public boolean appliesTo(ModerationItemType type) {
        return applies == Applies.ALL || (applies == Applies.REVIEWS) == type.review();
    }
}
