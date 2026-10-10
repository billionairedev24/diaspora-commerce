package com.afrotransact.content.domain.placement;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.ContentStateConflict;

/**
 * A placement: a payload for one slot with targeting, a schedule, a frequency cap and a priority, moving through
 * DRAFT → IN_REVIEW → SCHEDULED → LIVE → ENDED. Paid third-party creatives need a second person to approve.
 *
 * @param id           placement id ({@code plc_…})
 * @param slot         slot
 * @param name         internal name
 * @param payload      raw JSON payload, validated against the slot schema
 * @param targeting    who sees it
 * @param schedule     when
 * @param frequencyCap per-visitor cap
 * @param priority     higher first within a slot
 * @param sponsorship  paid third-party creative or not
 * @param status       workflow state
 * @param createdBy    author
 * @param submittedBy  who sent it to review
 * @param approvedBy   who approved it
 * @param reviewNote   rejection reason
 * @param version      optimistic lock
 * @param createdAt    created
 * @param updatedAt    last change
 */
public record Placement(String id, SlotKey slot, String name, String payload, Targeting targeting, Schedule schedule,
                        FrequencyCap frequencyCap, int priority, Sponsorship sponsorship, PlacementStatus status,
                        String createdBy, String submittedBy, String approvedBy, String reviewNote, long version,
                        Instant createdAt, Instant updatedAt) {

    public Placement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(status, "status");
        targeting = targeting == null ? Targeting.everyone() : targeting;
        frequencyCap = frequencyCap == null ? FrequencyCap.unlimited() : frequencyCap;
        sponsorship = sponsorship == null ? Sponsorship.none() : sponsorship;
    }

    /** A new draft; validates the editable fields. */
    public static Placement draft(String id, SlotKey slot, PlacementContent content, String actor, Instant now) {
        content.validate();
        return new Placement(id, slot, content.name(), content.payload(), content.targeting(), content.schedule(),
                content.frequencyCap(), content.priority(), content.sponsorship(), PlacementStatus.DRAFT, actor,
                null, null, null, 0, now, now);
    }

    /** Replaces the editable fields; only drafts can be edited. */
    public Placement edit(PlacementContent content, Instant now) {
        requireStatus(PlacementStatus.DRAFT, "content.placement-not-editable",
                "Only drafts can be edited; end this placement and duplicate it instead");
        content.validate();
        return new Placement(id, slot, content.name(), content.payload(), content.targeting(), content.schedule(),
                content.frequencyCap(), content.priority(), content.sponsorship(), status, createdBy, null, null,
                reviewNote, version, createdAt, now);
    }

    public Placement submit(String actor, Instant now) {
        requireStatus(PlacementStatus.DRAFT, "content.placement-not-draft", "Only drafts can be sent for review");
        if (schedule.endedBy(now)) {
            throw new ContentRuleViolation("content.schedule-ended", "The schedule has already ended",
                    Map.of("schedule.endsAt", "Already in the past"));
        }
        return with(PlacementStatus.IN_REVIEW, actor, null, null, now);
    }

    /**
     * Approves a placement in review. Paid third-party creatives need an approver who neither created nor
     * submitted it. Goes LIVE at once when the schedule has started, otherwise SCHEDULED.
     */
    public Placement approve(String approver, Instant now) {
        requireStatus(PlacementStatus.IN_REVIEW, "content.placement-not-in-review", "Only placements in review can be approved");
        if (sponsorship.paid() && (approver.equals(createdBy) || approver.equals(submittedBy))) {
            throw new ContentRuleViolation("content.four-eyes-required",
                    "Paid third-party creatives must be approved by someone other than their author or submitter");
        }
        if (schedule.endedBy(now)) {
            throw new ContentRuleViolation("content.schedule-ended", "The schedule has already ended",
                    Map.of("schedule.endsAt", "Already in the past"));
        }
        PlacementStatus next = schedule.startedBy(now) ? PlacementStatus.LIVE : PlacementStatus.SCHEDULED;
        return with(next, submittedBy, approver, null, now);
    }

    public Placement reject(String reviewer, String note, Instant now) {
        requireStatus(PlacementStatus.IN_REVIEW, "content.placement-not-in-review", "Only placements in review can be rejected");
        if (note == null || note.isBlank()) {
            throw new ContentRuleViolation("content.reason-required", "Give the author a reason",
                    Map.of("note", "Required"));
        }
        return new Placement(id, slot, name, payload, targeting, schedule, frequencyCap, priority, sponsorship,
                PlacementStatus.DRAFT, createdBy, null, null, reviewer + ": " + note.trim(), version, createdAt, now);
    }

    /** SCHEDULED → LIVE once the window opens (lifecycle job). */
    public Placement activate(Instant now) {
        requireStatus(PlacementStatus.SCHEDULED, "content.placement-not-scheduled", "Only scheduled placements go live");
        if (!schedule.startedBy(now)) {
            throw new ContentStateConflict("content.placement-not-due", "The schedule has not started yet");
        }
        return with(PlacementStatus.LIVE, submittedBy, approvedBy, reviewNote, now);
    }

    /** SCHEDULED or LIVE → ENDED (manually, or by the lifecycle job when the window closes). */
    public Placement end(Instant now) {
        if (status != PlacementStatus.SCHEDULED && status != PlacementStatus.LIVE) {
            throw new ContentStateConflict("content.placement-not-running", "Only scheduled or live placements can end");
        }
        return with(PlacementStatus.ENDED, submittedBy, approvedBy, reviewNote, now);
    }

    /** Approved and inside its schedule window (independent of whether the lifecycle job has run yet). */
    public boolean servableAt(Instant now) {
        return (status == PlacementStatus.LIVE || status == PlacementStatus.SCHEDULED) && schedule.activeAt(now);
    }

    public boolean dueForActivation(Instant now) {
        return status == PlacementStatus.SCHEDULED && schedule.startedBy(now) && !schedule.endedBy(now);
    }

    public boolean dueForEnd(Instant now) {
        return (status == PlacementStatus.LIVE || status == PlacementStatus.SCHEDULED) && schedule.endedBy(now);
    }

    /** "Sponsored" for paid creatives, "AfroTransact" otherwise. */
    public String label() {
        return sponsorship.paid() ? "Sponsored" : "AfroTransact";
    }

    private void requireStatus(PlacementStatus expected, String code, String message) {
        if (status != expected) {
            throw new ContentStateConflict(code, message + " (current status " + status + ")");
        }
    }

    private Placement with(PlacementStatus next, String submitter, String approver, String note, Instant now) {
        return new Placement(id, slot, name, payload, targeting, schedule, frequencyCap, priority, sponsorship, next,
                createdBy, submitter, approver, note, version, createdAt, now);
    }

    /**
     * Editable fields of a placement.
     *
     * @param name         internal name
     * @param payload      raw JSON payload
     * @param targeting    targeting
     * @param schedule     schedule window
     * @param frequencyCap cap
     * @param priority     priority (higher first)
     * @param sponsorship  paid or not
     */
    public record PlacementContent(String name, String payload, Targeting targeting, Schedule schedule,
                                   FrequencyCap frequencyCap, int priority, Sponsorship sponsorship) {

        public void validate() {
            Map<String, String> errors = new LinkedHashMap<>();
            if (name == null || name.isBlank() || name.length() > 120) {
                errors.put("name", "Give the placement a name of up to 120 characters");
            }
            if (payload == null || payload.isBlank()) {
                errors.put("payload", "Required");
            }
            if (schedule == null) {
                errors.put("schedule", "Required");
            }
            if (priority < -1000 || priority > 1000) {
                errors.put("priority", "Between -1000 and 1000");
            }
            if (sponsorship != null && sponsorship.paid()
                    && (sponsorship.advertiser() == null || sponsorship.advertiser().isBlank())) {
                errors.put("sponsorship.advertiser", "Paid creatives must name the advertiser");
            }
            if (targeting != null) {
                errors.putAll(targeting.problems());
            }
            ContentRuleViolation.throwIfAny("content.invalid-placement", "The placement is invalid", errors);
        }
    }
}
