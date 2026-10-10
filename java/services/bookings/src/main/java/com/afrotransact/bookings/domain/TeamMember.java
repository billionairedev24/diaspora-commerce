package com.afrotransact.bookings.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A member of a provider's team as bookings sees them: who they are (identity user id), their role, whether
 * customers can book them, and their own weekly working hours.
 *
 * @param userId identity subject of the member's login; null for staff without an account
 */
public record TeamMember(UUID id, String orgId, String userId, String displayName, TeamRole role, boolean bookable,
                         boolean active, WeeklyHours hours, Instant createdAt, Instant updatedAt, long version) {

    public TeamMember {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(role, "role");
        if (displayName == null || displayName.isBlank()) {
            throw new BookingsRuleViolation("bookings.invalid-member", "Name is required");
        }
        displayName = displayName.trim();
        userId = userId == null || userId.isBlank() ? null : userId.trim();
        hours = hours == null ? WeeklyHours.closed() : hours;
        if (bookable && !role.canBeBooked()) {
            throw new BookingsRuleViolation("bookings.invalid-member", "Finance members can't take appointments");
        }
    }

    public static TeamMember add(String orgId, String userId, String name, TeamRole role, Boolean bookable,
                                 WeeklyHours hours, Instant now) {
        boolean canBook = bookable == null ? role == TeamRole.OWNER || role == TeamRole.PRACTITIONER : bookable;
        return new TeamMember(UUID.randomUUID(), orgId, userId, name, role, canBook, true, hours, now, now, 0);
    }

    public TeamMember update(String newUserId, String newName, TeamRole newRole, boolean newBookable,
                             boolean newActive, Instant now) {
        return new TeamMember(id, orgId, newUserId, newName, newRole, newBookable, newActive, hours, createdAt, now,
                version);
    }

    public TeamMember withHours(WeeklyHours newHours, Instant now) {
        return new TeamMember(id, orgId, userId, displayName, role, bookable, active, newHours, createdAt, now,
                version);
    }

    public boolean takesBookings() {
        return active && bookable;
    }

    public boolean isUser(String subject) {
        return userId != null && userId.equals(subject);
    }
}
