package com.afrotransact.catalog.domain;

/** What the automatic checks found in a review's title or text; any flag sends the review to moderation. */
public enum ReviewFlag {
    /** A word from the profanity list. */
    PROFANITY,
    /** A web address or a domain name. */
    LINK,
    /** An e-mail address. */
    EMAIL,
    /** A phone number. */
    PHONE
}
