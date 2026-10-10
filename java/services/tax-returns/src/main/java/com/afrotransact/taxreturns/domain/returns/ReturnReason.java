package com.afrotransact.taxreturns.domain.returns;

/** Why the customer returns an item; decides fault (shipping refund, restocking fee) and whether photos are needed. */
public enum ReturnReason {
    DAMAGED(true, true),
    DEFECTIVE(true, true),
    WRONG_ITEM(true, true),
    NOT_AS_DESCRIBED(true, false),
    MISSING_PARTS(true, false),
    EXPIRED_OR_SPOILED(true, true),
    ARRIVED_LATE(true, false),
    NO_LONGER_NEEDED(false, false),
    ORDERED_BY_MISTAKE(false, false),
    BETTER_PRICE_AVAILABLE(false, false);

    private final boolean sellerFault;
    private final boolean evidenceRequired;

    ReturnReason(boolean sellerFault, boolean evidenceRequired) {
        this.sellerFault = sellerFault;
        this.evidenceRequired = evidenceRequired;
    }

    /** The seller (or AfroTransact as fulfiller) is at fault: shipping is refunded and no restocking fee applies. */
    public boolean sellerFault() {
        return sellerFault;
    }

    /** At least one evidence photo (media asset id) is required. */
    public boolean evidenceRequired() {
        return evidenceRequired;
    }
}
