package com.afrotransact.bookings.application;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.Party;
import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * Authorisation rules shared by the bookings use cases (server-side, whatever the UI shows):
 * <ul>
 *   <li>Provider endpoints need the permission and membership of the organisation (staff may act for any).</li>
 *   <li>Practitioners (role {@code provider.practitioner} without {@code bookings:team:manage}) see and change
 *       only their own calendar, bookings and hours.</li>
 *   <li>View-only roles (Finance: {@code bookings:read} only) cannot change anything.</li>
 *   <li>Customers act on their own bookings only (principal type CUSTOMER, subject = customer id).</li>
 * </ul>
 */
@Component
class BookingsAccess {

    private final PermissionChecker permissions;
    private final ActorResolver actors;

    BookingsAccess(PermissionChecker permissions, ActorResolver actors) {
        this.permissions = permissions;
        this.actors = actors;
    }

    ProviderScope provider(String orgId, String permission) {
        Actor actor = permissions.requireForOrg(permission, orgId);
        return new ProviderScope(actor, orgId, ownOnly(actor));
    }

    /** Org-wide changes (rules, services, team): practitioners are refused even with the permission. */
    ProviderScope providerWide(String orgId, String permission) {
        ProviderScope scope = provider(orgId, permission);
        if (scope.ownOnly()) {
            throw new ForbiddenActionException("bookings.own-calendar-only",
                    "Practitioners can only change their own calendar and hours");
        }
        return scope;
    }

    /** Throws unless the scope reaches {@code member} (practitioners: only themselves). */
    void requireMember(ProviderScope scope, TeamMember member) {
        if (!member.orgId().equals(scope.orgId())) {
            throw new NotFoundException("bookings.member-not-found", "Team member " + member.id() + " not found");
        }
        if (scope.ownOnly() && !member.isUser(scope.actor().subject())) {
            throw new ForbiddenActionException("bookings.own-calendar-only",
                    "Practitioners can only see and change their own calendar and hours");
        }
    }

    /** The signed-in shopper (gateway-bff relays the customer token). The local profile's super admin also passes. */
    Actor customer() {
        Actor actor = actors.current();
        if (actor.type() != PrincipalType.CUSTOMER && !actor.permissions().contains("*")) {
            throw new ForbiddenActionException("bookings.customer-only", "Only customers book services");
        }
        return actor;
    }

    /**
     * Which side the current actor is on for {@code booking}: its customer, or the provider (with
     * {@code permission}, own bookings only for practitioners). Anyone else gets 404.
     */
    Party partyFor(Booking booking, String permission, TeamMember assignee) {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.CUSTOMER) {
            if (!booking.customerId().equals(actor.subject())) {
                throw notFound(booking);
            }
            return Party.CUSTOMER;
        }
        if (!isStaff(actor) && !actor.orgId().map(booking.orgId()::equals).orElse(false)) {
            throw notFound(booking);
        }
        ProviderScope scope = provider(booking.orgId(), permission);
        if (scope.ownOnly() && (assignee == null || !assignee.isUser(actor.subject()))) {
            throw new ForbiddenActionException("bookings.own-calendar-only",
                    "Practitioners can only act on their own bookings");
        }
        return Party.PROVIDER;
    }

    Actor current() {
        return actors.current();
    }

    static boolean isStaff(Actor actor) {
        return actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
    }

    private static boolean ownOnly(Actor actor) {
        if (isStaff(actor) || actor.can(BookingsPermissions.TEAM_MANAGE)) {
            return false;
        }
        return actor.roles().contains(BookingsPermissions.ROLE_PRACTITIONER)
                && !actor.roles().contains(BookingsPermissions.ROLE_OWNER)
                && !actor.roles().contains(BookingsPermissions.ROLE_MANAGER);
    }

    private static NotFoundException notFound(Booking b) {
        return new NotFoundException("bookings.booking-not-found", "Booking " + b.reference() + " not found");
    }
}
