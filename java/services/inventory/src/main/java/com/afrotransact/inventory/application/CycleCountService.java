package com.afrotransact.inventory.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.domain.CycleCount;
import com.afrotransact.inventory.domain.CycleCountReason;
import com.afrotransact.inventory.port.CycleCountRepository;
import com.afrotransact.inventory.port.Page;

/**
 * Cycle counts (S281): start a count of a node's lots (narrowed to a SKU and / or a bin prefix), record what was
 * counted, review the variances and post them as audited ADJUST movements with reason codes. Reading needs
 * {@code inventory:read} (sellers see counts of their own stock); every change needs {@code inventory:adjust}.
 */
public interface CycleCountService {

    CycleCount start(StartCount command);

    CycleCount get(UUID id);

    Page<CycleCount> list(CycleCountRepository.CountQuery query);

    /** Records counted quantities (a line can be recounted until the count is posted). */
    CycleCount record(UUID id, List<CountedLine> lines, Long expectedVersion);

    /**
     * Posts every variance as an ADJUST of its lot by (counted − expected), so movements made while the count ran
     * are kept; idempotent per count and line. Lines without a variance are confirmed without a movement.
     */
    CycleCount post(UUID id, Long expectedVersion);

    CycleCount cancel(UUID id, Long expectedVersion);

    /** Start a count; {@code blind} hides the system quantity from the counter. */
    record StartCount(String nodeId, String sku, String binPrefix, String ownerOrg, boolean blind, String note) {
    }

    /** A counted quantity of one line, with the variance reason when it differs from the system. */
    record CountedLine(int lineNo, int countedQty, CycleCountReason reasonCode, String note) {
    }
}
