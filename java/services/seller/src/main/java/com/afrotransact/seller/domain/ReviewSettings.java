package com.afrotransact.seller.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Service levels, escalation chains and assignment mode of the application review.
 *
 * @param reviewSlaHours          how long a case may wait at step 1 (default 24)
 * @param approvalSlaHours        how long a case may wait at step 2 (default 24)
 * @param dueSoonPercent          share of the SLA after which a case is "due soon" (default 75)
 * @param unassignedAfterHours    a case nobody has taken for this long is escalated even before its SLA ends (default 4)
 * @param level2AfterHours        hours after the SLA ended (or after level 1) before level 2 is raised (default 24)
 * @param autoAssign              how a case entering a stage is given to a person
 * @param enforceAssignee         when true only the assignee (or a supervisor) can decide an assigned case
 * @param sendBackToReviewer      when true a case sent back by the approver goes to the original reviewer
 * @param roundRobinCursor        per stage, the last person given a case in ROUND_ROBIN mode
 */
public record ReviewSettings(int reviewSlaHours, int approvalSlaHours, int dueSoonPercent, int unassignedAfterHours,
                             int level2AfterHours, AutoAssignMode autoAssign, boolean enforceAssignee,
                             boolean sendBackToReviewer, EscalationChain reviewChain, EscalationChain approvalChain,
                             Map<ReviewStage, String> roundRobinCursor, String updatedBy, String updatedByName,
                             Instant updatedAt) {

    public static final int MAX_SLA_HOURS = 24 * 30;

    public ReviewSettings {
        autoAssign = autoAssign == null ? AutoAssignMode.NONE : autoAssign;
        reviewChain = reviewChain == null ? new EscalationChain(null, null) : reviewChain;
        approvalChain = approvalChain == null ? new EscalationChain(null, null) : approvalChain;
        roundRobinCursor = roundRobinCursor == null ? Map.of() : Map.copyOf(new TreeMap<>(roundRobinCursor));
    }

    public static ReviewSettings defaults() {
        return new ReviewSettings(24, 24, 75, 4, 24, AutoAssignMode.NONE, false, false, null, null, Map.of(), null,
                null, null);
    }

    public Duration sla(ReviewStage stage) {
        return Duration.ofHours(stage == ReviewStage.REVIEW ? reviewSlaHours : approvalSlaHours);
    }

    public EscalationChain chain(ReviewStage stage) {
        return stage == ReviewStage.REVIEW ? reviewChain : approvalChain;
    }

    /** Sensible limits and ordered levels; the keys of the result are the field names. */
    public Map<String, String> problems() {
        Map<String, String> out = new TreeMap<>();
        if (reviewSlaHours < 1 || reviewSlaHours > MAX_SLA_HOURS) {
            out.put("reviewSlaHours", "Use 1 to " + MAX_SLA_HOURS + " hours");
        }
        if (approvalSlaHours < 1 || approvalSlaHours > MAX_SLA_HOURS) {
            out.put("approvalSlaHours", "Use 1 to " + MAX_SLA_HOURS + " hours");
        }
        if (dueSoonPercent < 1 || dueSoonPercent > 99) {
            out.put("dueSoonPercent", "Use 1 to 99 percent");
        }
        if (unassignedAfterHours < 1 || unassignedAfterHours > MAX_SLA_HOURS) {
            out.put("unassignedAfterHours", "Use 1 to " + MAX_SLA_HOURS + " hours");
        }
        if (level2AfterHours < 1 || level2AfterHours > MAX_SLA_HOURS) {
            out.put("level2AfterHours", "Use 1 to " + MAX_SLA_HOURS + " hours");
        }
        if (!reviewChain.inOrder()) {
            out.put("reviewChain", "Set level 1 before level 2");
        }
        if (!approvalChain.inOrder()) {
            out.put("approvalChain", "Set level 1 before level 2");
        }
        return out;
    }

    public ReviewSettings withCursor(ReviewStage stage, String userId) {
        Map<ReviewStage, String> next = new TreeMap<>(roundRobinCursor);
        next.put(stage, userId);
        return new ReviewSettings(reviewSlaHours, approvalSlaHours, dueSoonPercent, unassignedAfterHours,
                level2AfterHours, autoAssign, enforceAssignee, sendBackToReviewer, reviewChain, approvalChain, next,
                updatedBy, updatedByName, updatedAt);
    }
}
