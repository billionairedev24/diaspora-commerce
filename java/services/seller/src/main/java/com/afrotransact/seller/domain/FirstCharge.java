package com.afrotransact.seller.domain;

import java.time.LocalDate;

/** When the first subscription charge happens and the plain sentence shown at onboarding. */
public record FirstCharge(LocalDate chargeOn, long amountCents, String sentence) {
}
