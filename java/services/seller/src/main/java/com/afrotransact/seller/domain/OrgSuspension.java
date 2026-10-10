package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A Trust &amp; Safety enforcement hold on an organisation. While active it pauses payouts and hides listings,
 * independently of Stripe compliance holds on the connected account; reinstating releases only this hold.
 *
 * @param previousStatus the organisation status to return to on reinstatement (normally APPROVED)
 */
public record OrgSuspension(UUID id, String orgId, SuspensionReason reason, String note, String actorId,
                            OrgStatus previousStatus, Instant suspendedAt, Instant reinstatedAt, String reinstatedBy,
                            String reinstateNote, long version) {

    public static final int NOTE_MAX = 1000;

    public OrgSuspension {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(previousStatus, "previousStatus");
        Objects.requireNonNull(suspendedAt, "suspendedAt");
    }

    /** Opens a suspension hold. A note is required for {@link SuspensionReason#OTHER}. */
    public static OrgSuspension open(UUID id, Organisation org, SuspensionReason reason, String note, String actorId,
                                     Instant now) {
        if (reason == null) {
            throw new SellerRuleViolation("seller.invalid-input", "Pick a suspension reason",
                    Map.of("reasonCode", "Required"));
        }
        String text = clean(note);
        if (reason == SuspensionReason.OTHER && text == null) {
            throw new SellerRuleViolation("seller.invalid-input", "Explain the suspension in the note",
                    Map.of("note", "Required for OTHER"));
        }
        if (org.status() != OrgStatus.APPROVED) {
            throw new SellerStateConflict("seller.org-not-approved",
                    "Only approved organisations can be suspended (status is " + org.status() + ")");
        }
        return new OrgSuspension(id, org.id(), reason, text, actorId, org.status(), now, null, null, null, 0);
    }

    public boolean active() {
        return reinstatedAt == null;
    }

    public OrgSuspension reinstate(String by, String note, Instant now) {
        if (!active()) {
            throw new SellerStateConflict("seller.not-suspended", "This suspension was already lifted");
        }
        return new OrgSuspension(id, orgId, reason, this.note, actorId, previousStatus, suspendedAt, now, by,
                clean(note), version);
    }

    /** {@code [CODE] note} for events and history. */
    public String summary() {
        return "[" + reason.name() + "]" + (note == null ? "" : " " + note);
    }

    private static String clean(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String t = note.trim();
        if (t.length() > NOTE_MAX) {
            throw new SellerRuleViolation("seller.invalid-input", "Note is too long",
                    Map.of("note", "At most " + NOTE_MAX + " characters"));
        }
        return t;
    }
}
