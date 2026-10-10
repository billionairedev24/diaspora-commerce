package com.afrotransact.taxreturns.port;

import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.returns.ReturnLabel;

/** carrier-gateway {@code POST /v1/labels}: buys the return label. Throws {@link UpstreamException}. */
public interface CarrierLabels {

    ReturnLabel buyReturnLabel(LabelRequest request, String idempotencyKey);

    /**
     * @param reference  RMA number (carrier tracking events carry it back)
     * @param originNode seller org id or FC id: selects the carrier account
     * @param from       customer's address
     * @param to         seller or FC return address
     * @param weightLb   parcel weight estimate
     */
    record LabelRequest(String reference, String originNode, ReturnAddress from, ReturnAddress to, double weightLb) {
    }
}
