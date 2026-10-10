package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.CommKind;

/**
 * Create or edit a pre-order window (wizard Details + Comms). Fees are in minor units of the window's currency.
 *
 * @param targets                area keys, e.g. {@code US/TX/Lubbock} or {@code US/TX}
 * @param timeZone               IANA zone (default America/Chicago)
 * @param socialBudgetMinor      paid social budget
 * @param doorFeeOverrideMinor   null = platform door fee
 * @param pickupFeeOverrideMinor null = platform pickup fee
 * @param locationId             delivery-runs location of the closing run (null = matched by city)
 */
public record WindowCommand(String name, List<String> targets, Instant opensAt, Instant closesAt, LocalDate deliversOn,
                            String timeZone, String ribbon, String headline, String smsCopy, Set<String> categories,
                            Boolean door, Boolean pickup, String pickupPointId, String pickupAddress,
                            Boolean showCount, Long socialBudgetMinor, Set<CommKind> comms, Long doorFeeOverrideMinor,
                            Long pickupFeeOverrideMinor, UUID locationId) {
}
