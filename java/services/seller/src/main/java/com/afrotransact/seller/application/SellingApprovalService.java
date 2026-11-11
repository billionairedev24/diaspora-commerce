package com.afrotransact.seller.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.afrotransact.seller.application.SellingViews.ApprovalView;
import com.afrotransact.seller.domain.SellingApplication;
import com.afrotransact.seller.domain.SellingApprovalStatus;
import com.afrotransact.seller.domain.SellingScopeType;
import com.afrotransact.seller.port.Page;

/**
 * The approvals themselves: how an application's outcome changes an organisation's approval, the lifecycle (expiry
 * warnings at 30, 14 and 7 days, 14 days grace, then the scope's listings are paused, never deleted) and staff
 * pausing, restoring or revoking an approval. Callers of the {@code record*} methods hold the application's row lock
 * and a transaction.
 */
public interface SellingApprovalService {

    /** The grace after an expiry, in days, before listings are paused. */
    int GRACE_DAYS = 14;

    void recordSubmitted(SellingApplication application);

    void recordInfoRequested(SellingApplication application);

    /** Approved: the approval runs to the earliest document expiry; listings paused for expiry come back. */
    void recordApproved(SellingApplication application);

    /** Rejected, or disqualified (not appealable, with a reapply date). */
    void recordRejected(SellingApplication application);

    /** Staff (applications:approve): lists approvals across organisations. */
    Page<ApprovalView> search(String orgId, SellingApprovalStatus status, SellingScopeType type, int page, int size);

    ApprovalView get(UUID id);

    /** Staff: pause the scope's listings for a reason such as a performance problem (10+ characters); the approval is kept. */
    ApprovalView pause(UUID id, String reason, String actorName);

    /** Staff: lift a staff pause; listings come back. */
    ApprovalView restore(UUID id, String reason, String actorName);

    /** Staff: withdraw the approval ("cannot apply", not appealable) with a reason and a reapply date. */
    ApprovalView revoke(UUID id, String reason, LocalDate reapplyAfter, String actorName);

    /** The lifecycle job: idempotent and safe on several replicas (each approval is checked under its row lock). */
    int runLifecycle(Instant now);
}
