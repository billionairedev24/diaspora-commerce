package com.afrotransact.content.application;

import java.util.List;

import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.content.domain.placement.Placement.PlacementContent;
import com.afrotransact.content.domain.placement.PlacementStatus;
import com.afrotransact.content.domain.placement.Slot;
import com.afrotransact.content.domain.placement.SlotKey;

/** Back-office placement editing and the review workflow (Marketing edit, approvers approve). */
public interface PlacementService {

    List<Slot> slots();

    List<Placement> list(PlacementStatus status, SlotKey slot);

    Placement get(String id);

    Placement create(SlotKey slot, PlacementContent content);

    Placement update(String id, PlacementContent content, long expectedVersion);

    Placement submit(String id);

    /** Approves; paid third-party creatives need an approver other than the author and submitter. */
    Placement approve(String id);

    Placement reject(String id, String note);

    Placement end(String id);

    /** Permanently removes a paid advert in DRAFT or ENDED (advert:delete). */
    void delete(String id);
}
