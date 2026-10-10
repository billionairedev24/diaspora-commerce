package com.afrotransact.platform.security;

/** Fine-grained authorisation checks done inside the service, after coarse scope checks at the edge. */
public interface PermissionChecker {

    /** Throws {@code ForbiddenActionException} unless the current actor has {@code permission}. */
    Actor require(String permission);

    /** Throws unless the actor has {@code permission} and either is staff or belongs to {@code orgId}. */
    Actor requireForOrg(String permission, String orgId);
}
