package com.afrotransact.retail.domain;

import java.util.List;

/** Journal lines for each purchasing step (S073/S074). Amounts are base-currency minor units. */
public interface GlPostingRules {

    /** Title transfer at departure: Dr 1310 In transit / Cr 2110 GR/IR for the FOB value. */
    List<JournalLine> titleTransfer(long fobMinor);

    /**
     * Completed goods receipt: Dr 1300 sellable and Dr 1320 quarantined landed cost; Cr 1310 for the FOB value when
     * it was in transit (or Cr 2110 GR/IR when received without a shipment); Cr 2120 for duty and other charges.
     */
    List<JournalLine> goodsReceived(long sellableMinor, long quarantineMinor, long fobMinor, long accruedMinor,
                                    boolean wasInTransit);

    /**
     * Matched supplier invoice: Dr 2110 GR/IR at PO price and PO rate, purchase price variance to 5010, the
     * remaining FX difference to 7100, Cr 2100 accounts payable at the invoice rate.
     */
    List<JournalLine> invoiceMatched(long grirMinor, long priceVarianceMinor, long payableMinor);

    /** Shipped 1P units at lot landed cost: Dr 5000 COGS / Cr 1300 Inventory. */
    List<JournalLine> costOfGoodsSold(long amountMinor);
}
