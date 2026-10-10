package com.afrotransact.seller.port;

import java.time.LocalDate;
import java.util.List;

/** ai-gateway {@code GET /v1/ai/usage/orgs}: AI credits per organisation and UTC day (S206). */
public interface AiUsageSource {

    /**
     * Credits of every organisation in [from, to].
     *
     * @throws GatewayException when ai-gateway is unavailable or refuses the call
     */
    List<AiCreditSample> credits(LocalDate from, LocalDate to);

    /** AI credits one organisation used on one day. */
    record AiCreditSample(String orgId, LocalDate day, long credits) {
    }
}
