package com.afrotransact.identity.domain;

/** Organisation approval status, mirrored from the seller service's two-step review. */
public enum OrgStatus {
    PENDING,
    APPROVED,
    SUSPENDED,
    REJECTED
}
