package com.afrotransact.seller.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** inventory-service {@code GET /v1/storage/usage}: FBA storage per owner organisation and UTC day (S206). */
public interface StorageUsageSource {

    /**
     * Daily storage of every owner in [from, to].
     *
     * @throws GatewayException when inventory is unavailable or refuses the call
     */
    List<StorageSample> cubicFeet(LocalDate from, LocalDate to);

    /**
     * @param cubicFeet cubic feet held that day (= cubic-foot-days)
     */
    record StorageSample(String orgId, LocalDate day, BigDecimal cubicFeet) {
    }
}
