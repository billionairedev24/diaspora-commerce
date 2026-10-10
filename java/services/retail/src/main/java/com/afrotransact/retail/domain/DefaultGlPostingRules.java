package com.afrotransact.retail.domain;

import java.util.List;

/** Postings from the design's accounting tab (1300/1310/1320/2110/2120/5000) plus AP, price variance and FX. */
public class DefaultGlPostingRules implements GlPostingRules {

    @Override
    public List<JournalLine> titleTransfer(long fobMinor) {
        return List.of(JournalLine.debit(GlAccount.IN_TRANSIT, fobMinor), JournalLine.credit(GlAccount.GR_IR, fobMinor));
    }

    @Override
    public List<JournalLine> goodsReceived(long sellableMinor, long quarantineMinor, long fobMinor, long accruedMinor,
                                           boolean wasInTransit) {
        if (sellableMinor + quarantineMinor != fobMinor + accruedMinor) {
            throw new IllegalArgumentException("Receipt value must equal FOB plus accrued landed cost");
        }
        return List.of(JournalLine.debit(GlAccount.INVENTORY, sellableMinor),
                JournalLine.debit(GlAccount.QUARANTINE, quarantineMinor),
                JournalLine.credit(wasInTransit ? GlAccount.IN_TRANSIT : GlAccount.GR_IR, fobMinor),
                JournalLine.credit(GlAccount.ACCRUED_LANDED_COST, accruedMinor));
    }

    @Override
    public List<JournalLine> invoiceMatched(long grirMinor, long priceVarianceMinor, long payableMinor) {
        long fx = payableMinor - grirMinor - priceVarianceMinor;
        return List.of(JournalLine.debit(GlAccount.GR_IR, grirMinor),
                JournalLine.debit(GlAccount.PURCHASE_PRICE_VARIANCE, priceVarianceMinor),
                JournalLine.debit(GlAccount.FX_GAIN_LOSS, fx),
                JournalLine.credit(GlAccount.ACCOUNTS_PAYABLE, payableMinor));
    }

    @Override
    public List<JournalLine> costOfGoodsSold(long amountMinor) {
        return List.of(JournalLine.debit(GlAccount.COGS, amountMinor), JournalLine.credit(GlAccount.INVENTORY, amountMinor));
    }
}
