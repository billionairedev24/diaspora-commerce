package com.afrotransact.seller.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Beneficial owner (≥ 25 %) or person with significant control. Stripe's embedded onboarding collects each person's
 * identity and tax number; the application keeps who they are, their share and their home address.
 */
public record Owner(String firstName, String lastName, String title, String email, BigDecimal ownershipPercent,
                    boolean significantControl, LocalDate dateOfBirth, Address address) {
}
