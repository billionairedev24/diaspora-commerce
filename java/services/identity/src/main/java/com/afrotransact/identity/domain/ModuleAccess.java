package com.afrotransact.identity.domain;

/** One cell of the module access matrix: what a role can do in a back-office module. */
public record ModuleAccess(Portal portal, String moduleKey, String roleKey, AccessLevel level) {
}
