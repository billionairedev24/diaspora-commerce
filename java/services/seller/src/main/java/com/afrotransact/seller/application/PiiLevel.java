package com.afrotransact.seller.application;

/** How much personal data a view may show. */
public enum PiiLevel {
    /** The applicant: their own data, but the tax ID only as last 4. */
    APPLICANT,
    /** Reviewers without applications:pii: tax ID last 4, dates of birth as year only. */
    MASKED,
    /** applications:pii: full tax ID and dates of birth (logged). */
    FULL
}
