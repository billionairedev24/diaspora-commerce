package com.afrotransact.bookings.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A bookable service of a provider ("Knotless box braids · medium", 300 min, $180, $40 deposit). Bookings copy the
 * name, duration, price and deposit when they are made, so price changes never affect existing bookings.
 *
 * @param memberIds team members who perform the service (empty = every bookable member)
 */
public record ServiceOffering(UUID id, String orgId, String name, String description, String category,
                              int durationMinutes, long priceMinor, long depositMinor, String currency,
                              Set<ServiceMode> modes, List<AddOn> addOns, Set<UUID> memberIds, boolean active,
                              Instant createdAt, Instant updatedAt, long version) {

    public ServiceOffering {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orgId, "orgId");
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null || name.trim().length() < 3 || name.trim().length() > 120) {
            errors.put("name", "3–120 characters");
        }
        if (durationMinutes < 5 || durationMinutes > 24 * 60) {
            errors.put("durationMinutes", "Between 5 minutes and 24 hours");
        }
        if (priceMinor <= 0) {
            errors.put("priceMinor", "Price must be above zero");
        }
        if (depositMinor < 0 || depositMinor > priceMinor) {
            errors.put("depositMinor", "Deposit must be between 0 and the price");
        }
        if (modes == null || modes.isEmpty()) {
            errors.put("modes", "Choose at least one: in person, at home or virtual");
        }
        List<AddOn> extras = addOns == null ? List.of() : addOns;
        Set<String> ids = new HashSet<>();
        for (AddOn a : extras) {
            if (!ids.add(a.id())) {
                errors.put("addOns", "Add-on ids must be unique");
            }
        }
        if (!errors.isEmpty()) {
            throw new BookingsRuleViolation("bookings.invalid-service", "Service details are invalid", errors);
        }
        name = name.trim();
        description = description == null ? "" : description.trim();
        category = category == null || category.isBlank() ? "services" : category.trim().toLowerCase(Locale.ROOT);
        currency = currency == null ? "USD" : currency.toUpperCase(Locale.ROOT);
        modes = Set.copyOf(EnumSet.copyOf(modes));
        addOns = List.copyOf(extras);
        memberIds = memberIds == null ? Set.of() : Set.copyOf(memberIds);
    }

    public long balanceMinor() {
        return priceMinor - depositMinor;
    }

    public boolean performedBy(UUID memberId) {
        return memberIds.isEmpty() || memberIds.contains(memberId);
    }

    /** The add-ons a customer picked, in the service's order; unknown ids are refused. */
    public List<AddOn> pick(List<String> addOnIds) {
        if (addOnIds == null || addOnIds.isEmpty()) {
            return List.of();
        }
        List<AddOn> picked = new ArrayList<>();
        for (AddOn a : addOns) {
            if (addOnIds.contains(a.id())) {
                picked.add(a);
            }
        }
        if (picked.size() != new HashSet<>(addOnIds).size()) {
            throw new BookingsRuleViolation("bookings.unknown-add-on", "One of the add-ons is not offered",
                    Map.of("addOnIds", "Unknown add-on"));
        }
        return List.copyOf(picked);
    }

    public ServiceOffering update(String newName, String newDescription, String newCategory, int newDuration,
                                  long newPrice, long newDeposit, Set<ServiceMode> newModes, List<AddOn> newAddOns,
                                  Instant now) {
        return new ServiceOffering(id, orgId, newName, newDescription, newCategory, newDuration, newPrice, newDeposit,
                currency, newModes, newAddOns, memberIds, active, createdAt, now, version);
    }

    public ServiceOffering withMembers(Set<UUID> members, Instant now) {
        return new ServiceOffering(id, orgId, name, description, category, durationMinutes, priceMinor,
                depositMinor, currency, modes, addOns, members, active, createdAt, now, version);
    }

    public ServiceOffering withActive(boolean value, Instant now) {
        return new ServiceOffering(id, orgId, name, description, category, durationMinutes, priceMinor,
                depositMinor, currency, modes, addOns, memberIds, value, createdAt, now, version);
    }
}
