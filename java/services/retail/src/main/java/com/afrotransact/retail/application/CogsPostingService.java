package com.afrotransact.retail.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.retail.domain.JournalEntry;

/** Posts COGS at lot landed cost when 1P units ship (S073: Dr 5000 / Cr 1300). */
public interface CogsPostingService {

    /**
     * Handles {@code inventory.reservation.committed}; only SKUs in the retail item master count. De-duplicated on the
     * event id.
     */
    Optional<JournalEntry> onShipped(UUID eventId, String orderId, List<ShippedLine> lines);
}
