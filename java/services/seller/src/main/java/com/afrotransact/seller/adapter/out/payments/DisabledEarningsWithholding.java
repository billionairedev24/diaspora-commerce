package com.afrotransact.seller.adapter.out.payments;

import com.afrotransact.seller.port.EarningsWithholdingGateway;

/** Collection from earnings switched off (the default): plan fees are always charged to the payment method. */
public class DisabledEarningsWithholding implements EarningsWithholdingGateway {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Withholding withhold(String orgId, String invoiceId, long amountCents, String currency) {
        return new Withholding(false, null, "Collection from earnings is switched off");
    }
}
