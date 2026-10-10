package com.afrotransact.retail.domain;

/** How a sent PO reaches the supplier. */
public enum DispatchChannel {
    /** PDF stored in media; notifications emails the supplier contact a signed link. */
    EMAIL,
    /** X12 850 through the EDI transport (EDI-enabled suppliers only). */
    EDI
}
