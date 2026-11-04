package com.afrotransact.seller.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A review step that already happened (HTTP 409): the application is approved or rejected, or step 1 was already
 * decided. The message says what was decided, by whom and when, e.g. "This application is already approved (by Ada
 * Obi, 12 Oct 2026, 14:03 UTC)". Code {@code seller.already-decided} (final decision) or
 * {@code seller.already-reviewed} (step 1).
 */
public class ApplicationAlreadyDecided extends SellerStateConflict {

    public static final String DECIDED = "seller.already-decided";
    public static final String REVIEWED = "seller.already-reviewed";

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'",
            Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final ApplicationStatus status;
    private final String deciderId;
    private final String deciderLabel;
    private final Instant decidedAt;

    private ApplicationAlreadyDecided(ApplicationStatus status, String deciderId, String deciderLabel,
                                      Instant decidedAt) {
        super(status == ApplicationStatus.APPROVED || status == ApplicationStatus.REJECTED ? DECIDED : REVIEWED,
                message(status, deciderLabel, decidedAt));
        this.status = status;
        this.deciderId = deciderId;
        this.deciderLabel = deciderLabel;
        this.decidedAt = decidedAt;
    }

    /** {@code actor} is who is trying now: when they made the decision themselves the message says "by you". */
    static ApplicationAlreadyDecided of(ApplicationStatus status, String deciderId, Instant decidedAt, String actor) {
        return new ApplicationAlreadyDecided(status, deciderId, label(deciderId, actor, null), decidedAt);
    }

    /** The same conflict naming the decider ({@code name} from the timeline); "you" stays "you". */
    public ApplicationAlreadyDecided named(String name, String actor) {
        return new ApplicationAlreadyDecided(status, deciderId, label(deciderId, actor, name), decidedAt);
    }

    public ApplicationStatus status() {
        return status;
    }

    public String deciderId() {
        return deciderId;
    }

    /** "you", the decider's name, or their id when no name was recorded. */
    public String deciderLabel() {
        return deciderLabel;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    private static String label(String deciderId, String actor, String name) {
        if (deciderId != null && deciderId.equals(actor)) {
            return "you";
        }
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return deciderId;
    }

    private static String message(ApplicationStatus status, String who, Instant at) {
        String by = who == null && at == null ? ""
                : " (" + (who == null ? "" : "by " + who) + (who != null && at != null ? ", " : "")
                + (at == null ? "" : WHEN.format(at)) + ")";
        return switch (status) {
            case APPROVED -> "This application is already approved" + by;
            case REJECTED -> "This application is already rejected" + by;
            case FLAGGED -> "This application is already flagged" + by + "; it is waiting for the final approver";
            default -> "This application is already approved for final review" + by
                    + "; it is waiting for final approval";
        };
    }
}
