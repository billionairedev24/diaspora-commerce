package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** What Stripe reports for a connected account (requirements.*, flags, capabilities). */
public record RequirementsSnapshot(List<String> currentlyDue, List<String> eventuallyDue, List<String> pastDue,
                                   List<String> pendingVerification, List<RequirementError> errors,
                                   Instant currentDeadline, String disabledReason, boolean chargesEnabled,
                                   boolean payoutsEnabled, boolean detailsSubmitted,
                                   String individualVerificationStatus, Map<String, String> capabilities) {

    public RequirementsSnapshot {
        currentlyDue = currentlyDue == null ? List.of() : List.copyOf(currentlyDue);
        eventuallyDue = eventuallyDue == null ? List.of() : List.copyOf(eventuallyDue);
        pastDue = pastDue == null ? List.of() : List.copyOf(pastDue);
        pendingVerification = pendingVerification == null ? List.of() : List.copyOf(pendingVerification);
        errors = errors == null ? List.of() : List.copyOf(errors);
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
    }

    public static RequirementsSnapshot empty() {
        return new RequirementsSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), null, null, false,
                false, false, null, Map.of());
    }

    /** Items the seller must act on: past due first, then currently due. */
    public Set<String> open() {
        Set<String> out = new LinkedHashSet<>(pastDue);
        out.addAll(currentlyDue);
        return out;
    }

    /** Every requirement mentioned in any bucket. */
    public Set<String> all() {
        Set<String> out = open();
        out.addAll(eventuallyDue);
        out.addAll(pendingVerification);
        return out;
    }

    /** The seller answered {@code key}: it waits for Stripe (pending verification) until the next account.updated. */
    public RequirementsSnapshot markPending(String key) {
        List<String> pending = new ArrayList<>(pendingVerification);
        if (!pending.contains(key)) {
            pending.add(key);
        }
        return new RequirementsSnapshot(without(currentlyDue, key), without(eventuallyDue, key),
                without(pastDue, key), pending, errors.stream().filter(e -> !key.equals(e.requirement())).toList(),
                currentDeadline, disabledReason, chargesEnabled, payoutsEnabled, detailsSubmitted,
                individualVerificationStatus, capabilities);
    }

    public RequirementsSnapshot withCapability(String capability, String status) {
        Map<String, String> caps = new TreeMap<>(capabilities);
        caps.put(capability, status);
        return new RequirementsSnapshot(currentlyDue, eventuallyDue, pastDue, pendingVerification, errors,
                currentDeadline, disabledReason, chargesEnabled, payoutsEnabled, detailsSubmitted,
                individualVerificationStatus, caps);
    }

    public boolean sameRequirements(RequirementsSnapshot o) {
        return o != null && Set.copyOf(currentlyDue).equals(Set.copyOf(o.currentlyDue))
                && Set.copyOf(pastDue).equals(Set.copyOf(o.pastDue))
                && Set.copyOf(eventuallyDue).equals(Set.copyOf(o.eventuallyDue))
                && Set.copyOf(pendingVerification).equals(Set.copyOf(o.pendingVerification))
                && Objects.equals(currentDeadline, o.currentDeadline)
                && Objects.equals(disabledReason, o.disabledReason)
                && chargesEnabled == o.chargesEnabled && payoutsEnabled == o.payoutsEnabled;
    }

    private static List<String> without(List<String> list, String key) {
        return list.stream().filter(k -> !k.equals(key)).toList();
    }
}
