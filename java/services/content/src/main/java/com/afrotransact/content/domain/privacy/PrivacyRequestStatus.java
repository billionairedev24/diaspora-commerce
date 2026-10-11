package com.afrotransact.content.domain.privacy;

/** Lifecycle of a privacy request: received, then completed or denied (with a note) by staff. */
public enum PrivacyRequestStatus {
    RECEIVED,
    COMPLETED,
    DENIED
}
