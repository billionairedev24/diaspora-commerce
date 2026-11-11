package com.afrotransact.orders.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.orders.port.StaffDirectory.StaffPerson;

/**
 * The staff side: the queue, who has each case, the decision and the escalation. It copies the application review
 * workflow (claim, assign, release, escalate, SLA, names on the timeline); see docs/runbooks/delivery-quotes.md.
 */
public interface DeliveryQuoteDeskService {

    /**
     * The queue. {@code queue}: MY, UNASSIGNED, WAITING (with the customer), CLOSED or ALL. Filters: {@code overdue},
     * {@code unassigned}, {@code escalated}, {@code assigneeId}, free text (reference, assignee, place).
     */
    QuoteCaseView.Page queue(String queue, String assigneeId, boolean unassigned, boolean overdue, boolean escalated,
                             String text, int page, int size);

    QuoteCaseView detail(String id);

    QuoteCaseView claim(String id, String actorName);

    QuoteCaseView assign(String id, String assigneeId, String note, String actorName);

    QuoteCaseView release(String id, String note, String actorName);

    QuoteCaseView escalate(String id, String reason, String actorName);

    /** Agrees {@code amountMinor} (within the settings' bounds); the reason is required and audited. */
    QuoteCaseView agree(String id, long amountMinor, String reason, String actorName);

    QuoteCaseView decline(String id, String reason, String actorName);

    /** People the case can be assigned to now. */
    List<StaffPerson> candidates(String id);

    /**
     * Internal: a request was just created; applies the auto-assignment setting (round robin or least loaded) in the
     * caller's transaction. Never fails the request: without staff to give it to, it waits in the pool.
     */
    void handOut(String id);

    /** The job: raises the escalation of one request when it is due. True when it moved up. Idempotent. */
    boolean escalateIfDue(String id, Instant now);

    /** The job: expires a ready quote whose validity ended. True when it changed. Idempotent. */
    boolean expireIfDue(String id, Instant now);
}
