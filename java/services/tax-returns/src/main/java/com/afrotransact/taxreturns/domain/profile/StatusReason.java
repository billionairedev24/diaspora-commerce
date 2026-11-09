package com.afrotransact.taxreturns.domain.profile;

/** Why a profile is gated: the gate, the open decision (1 to 5, 0 = none) and the plain text. */
public record StatusReason(ProfileStatus status, int decision, String text) {
}
