package com.afrotransact.identity.domain;

import java.util.Map;

/** Default {@link TeamPolicy}. */
public class DefaultTeamPolicy implements TeamPolicy {

    @Override
    public Portal portalOf(OrgKind kind) {
        return kind == OrgKind.SELLER ? Portal.SELLER : Portal.PROVIDER;
    }

    @Override
    public String teamModuleKey(OrgKind kind) {
        return kind == OrgKind.SELLER ? "sTeam" : "pTeam";
    }

    @Override
    public boolean isOwnerRole(String roleKey) {
        return RoleKeys.SELLER_OWNER.equals(roleKey) || RoleKeys.PROVIDER_OWNER.equals(roleKey);
    }

    @Override
    public void requireRoleFor(OrgKind kind, Role role) {
        Portal expected = portalOf(kind);
        if (role.portal() != expected) {
            throw new IdentityRuleViolation("identity.wrong-portal", "Use a " + expected.wire() + " role",
                    Map.of("roleKey", "Choose a " + expected.wire() + ".* role for this organisation"));
        }
    }

    @Override
    public void requireOwnerRemains(String currentRole, String newRole, int otherOwners) {
        boolean losesOwner = isOwnerRole(currentRole) && (newRole == null || !isOwnerRole(newRole));
        if (losesOwner && otherOwners < 1) {
            throw new IdentityRuleViolation("identity.last-owner",
                    "An organisation needs an owner. Make someone else an owner first",
                    Map.of("roleKey", "The last owner can't be removed or given another role"));
        }
    }

    @Override
    public String newToken() {
        return SecretHashing.randomToken(TOKEN_BYTES);
    }

    @Override
    public String hashToken(String token) {
        return SecretHashing.sha256Hex(token == null ? "" : token.trim());
    }
}
