package com.afrotransact.inventory.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Cubic-feet-days per owner organisation and day, which seller reads for metered billing over HTTP today
 * ({@code GET /v1/storage/usage}, scope {@code inventory:storage.read}), entered in-process under the calling module's
 * principal. A caller without the scope is refused with {@code ModuleApiException} status 403.
 */
public interface StorageUsageApi {

    /** Inclusive range of at most 93 days, every owner. */
    List<StorageDay> usage(LocalDate from, LocalDate to);

    record StorageDay(String ownerOrg, LocalDate day, BigDecimal cubicFeet) {
    }
}
