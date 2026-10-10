package com.afrotransact.seller.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Beneficial owner (≥ 25 %) or person with significant control. */
public record Owner(String firstName, String lastName, String title, String email, BigDecimal ownershipPercent,
                    boolean significantControl, LocalDate dateOfBirth) {
}
