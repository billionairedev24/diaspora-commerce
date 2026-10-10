package com.afrotransact.inventory.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.MarkdownFlag;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.Page;

/** Lot views and staff operations: quarantine, release, write-off, cycle count and transfer (inventory:adjust). */
public interface LotService {

    Lot get(UUID id);

    Page<Lot> search(LotRepository.LotQuery query);

    /** Sellable lots flagged by the nightly job: ≤ 60 days ({@code WITHIN_60}) or ≤ 30 days ({@code WITHIN_30}). */
    List<Lot> markdown(MarkdownFlag minimum);

    Lot quarantine(UUID id, String reason);

    Lot release(UUID id, String reason);

    /** Writes off {@code qty} unreserved units (all of them when null). */
    Lot writeOff(UUID id, Integer qty, String reason, String idempotencyKey);

    /** Cycle count: sets the counted quantity with a reason. Can never go below what is reserved. */
    Lot adjust(UUID id, int countedQty, String reason, String idempotencyKey);

    /** Moves unreserved sellable units of a lot to another node (same lot number there). */
    TransferResult transfer(UUID id, TransferCommand command, String idempotencyKey);

    /** Transfer request. */
    record TransferCommand(String toNodeId, String toBin, int qty, String reason) {
    }

    /** Source and destination lots after a transfer. */
    record TransferResult(String reference, Lot from, Lot to) {
    }
}
