package com.afrotransact.trustops.domain;

/** Why a listing is rejected (sent to catalog in the moderation note and shown to the seller). */
public enum ModerationReasonCode {
    PROHIBITED_ITEM("Prohibited item (USDA / CDC / FDA rules)"),
    UNAPPROVED_HEALTH_CLAIM("Unapproved health or medical claim"),
    RESTRICTED_INGREDIENT("Restricted or banned ingredient"),
    COUNTERFEIT("Possible counterfeit or trademark infringement"),
    IMAGE_POLICY("Image copied, watermarked or off-policy"),
    MISLEADING_CONTENT("Misleading title, description or details"),
    LABELLING("Labelling or allergen information missing"),
    SHIPPING_METHOD("Unsuitable shipping method (perishables, hazmat)"),
    DUPLICATE_LISTING("Duplicate of an existing product page"),
    WRONG_CATEGORY("Listed in the wrong category"),
    OTHER("Other (see note)");

    private final String label;

    ModerationReasonCode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
