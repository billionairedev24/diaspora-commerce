package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.RunPickLists.PickList;

/**
 * Pick lists for the sellers a pre-order run collects from (S264): when the driver comes and, for every order on the
 * run, what to pack (the order number goes on the bag). A seller sees only its own lines and no customer details.
 */
public interface RunPickListService {

    /**
     * The seller's runs that are being collected or delivered (and those completed in the last two weeks), earliest
     * pickup first. A seller reads its own organisation; staff and services name {@code orgId}.
     */
    List<PickList> forSeller(String orgId);

    /** The seller's pick list for one run (404 when the run has none of its lines). */
    PickList forSeller(String orgId, UUID runId);

    /** Every seller's pick list of a run (staff: {@code delivery-runs:read}), largest first. */
    List<PickList> forRun(UUID runId);
}
