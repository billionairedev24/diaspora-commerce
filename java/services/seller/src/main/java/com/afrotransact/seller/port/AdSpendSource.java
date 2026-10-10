package com.afrotransact.seller.port;

import java.time.LocalDate;
import java.util.List;

/** content-service {@code GET /v1/ads/spend}: charged sponsored-products spend per organisation and UTC day (S206). */
public interface AdSpendSource {

    /**
     * Spend of every organisation in [from, to].
     *
     * @throws GatewayException when content is unavailable or refuses the call
     */
    List<AdSpendSample> spend(LocalDate from, LocalDate to);

    /**
     * @param spendMinor charged clicks in minor units of {@code currency}
     */
    record AdSpendSample(String orgId, LocalDate day, String currency, long spendMinor) {
    }
}
