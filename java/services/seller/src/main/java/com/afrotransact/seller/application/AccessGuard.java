package com.afrotransact.seller.application;

import java.util.Arrays;
import java.util.Set;

import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Component;

/** Authorisation rules shared by the seller use cases (permissions are checked here, never only in UIs). */
@Component
class AccessGuard {

    static final Set<String> OWNER = Set.of("owner");
    static final Set<String> OWNER_OR_FINANCE = Set.of("owner", "finance");

    private final PermissionChecker permissions;
    private final ActorResolver actors;
    private final OrganisationRepository orgs;

    AccessGuard(PermissionChecker permissions, ActorResolver actors, OrganisationRepository orgs) {
        this.permissions = permissions;
        this.actors = actors;
        this.orgs = orgs;
    }

    Actor current() {
        return actors.current();
    }

    /** A human applicant (customers adding the seller role, or not-yet-approved seller/provider members). */
    Actor applicant() {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE || actor.type() == PrincipalType.AI_AGENT
                || actor.type() == PrincipalType.PARTNER_APP) {
            throw new ForbiddenActionException("seller.applicant-only", "Only people can apply to sell");
        }
        return actor;
    }

    /** Staff (or a super admin) holding {@code permission}. */
    Actor staff(String permission) {
        Actor actor = permissions.require(permission);
        requireStaff(actor);
        return actor;
    }

    /** Staff holding at least one of {@code anyOf}. */
    Actor staffAny(String... anyOf) {
        Actor actor = actors.current();
        if (Arrays.stream(anyOf).noneMatch(actor::can)) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing one of the permissions " + String.join(", ", anyOf));
        }
        requireStaff(actor);
        return actor;
    }

    /**
     * A member of {@code orgId} with one of {@code roles} (role-key suffixes such as {@code owner}), either from the
     * token (org claim + roles) or from the organisation's member list; staff with {@code staffPermission} also pass.
     */
    Actor orgRole(String orgId, Set<String> roles, String staffPermission) {
        Actor actor = actors.current();
        if (isStaff(actor) && staffPermission != null && actor.can(staffPermission)) {
            return actor;
        }
        boolean tokenRole = actor.orgId().map(orgId::equals).orElse(false)
                && actor.roles().stream().anyMatch(r -> roles.stream().anyMatch(s -> r.endsWith("." + s)));
        boolean memberRole = orgs.findMember(orgId, actor.subject()).map(OrgMember::roleKey)
                .map(k -> roles.stream().anyMatch(s -> k.endsWith("." + s))).orElse(false);
        if (!tokenRole && !memberRole) {
            throw new ForbiddenActionException("seller.org-role-required",
                    "Only the organisation's " + String.join(" or ", roles.stream().sorted().toList())
                            + " can do this");
        }
        return actor;
    }

    static boolean isStaff(Actor actor) {
        return actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE
                || actor.permissions().contains("*");
    }

    private static void requireStaff(Actor actor) {
        if (!isStaff(actor)) {
            throw new ForbiddenActionException("seller.staff-only", "Only AfroTransact staff can do this");
        }
    }
}
