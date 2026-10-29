package com.afrotransact.catalog.domain;

/**
 * Why a review is reported or removed (shown to the customer on a rejected review; {@link #OTHER} needs a note). The
 * codes match trust-ops' review reason codes, so a decision made in the moderation queue maps one to one.
 */
public enum ReviewReasonCode {
    /** Insults, harassment, hate or threats. */
    ABUSIVE,
    /** Phone numbers, addresses, e-mail addresses or other personal data of anyone. */
    PERSONAL_INFORMATION,
    /** Advertising, links, repeated or nonsense text. */
    SPAM,
    /** Not about the product (for example about delivery, the platform or another business). */
    OFF_TOPIC,
    /** Written by or for the seller, or by a competitor. */
    CONFLICT_OF_INTEREST,
    /** Anything else; the note explains. */
    OTHER
}
