package com.afrotransact.seller.adapter.out.payments;

import com.afrotransact.seller.port.EarningsWithholdingGateway;

/**
 * Collection from earnings switched off (the kill switch COLLECT_PLAN_FEES_FROM_EARNINGS=false): plan fees are always
 * charged to the payment method and the choice is hidden.
 */
public class DisabledEarningsWithholding implements EarningsWithholdingGateway {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Withholding withhold(String orgId, String invoiceId, String invoiceNumber, long amountCents,
                                String currency) {
        return new Withholding(false, 0, null, "Collection from earnings is switched off");
    }

    @Override
    public long release(String orgId, String invoiceId, long amountCents, String reason, String idempotencyKey) {
        return 0;
    }
}
