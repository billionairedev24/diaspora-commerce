package com.afrotransact.bookings.domain;

/** Why moderation removed a review (shown to the customer; {@link #OTHER} needs a note). */
public enum ReviewRejectReason {
    /** Insults, harassment, hate or threats. */
    ABUSIVE,
    /** Phone numbers, addresses or other personal data of anyone. */
    PERSONAL_INFORMATION,
    /** Advertising, links, repeated or nonsense text. */
    SPAM,
    /** Not about the appointment (for example about the platform or another business). */
    OFF_TOPIC,
    /** Written by or for the provider, or by a competitor. */
    CONFLICT_OF_INTEREST,
    /** Anything else; the note explains. */
    OTHER
}
