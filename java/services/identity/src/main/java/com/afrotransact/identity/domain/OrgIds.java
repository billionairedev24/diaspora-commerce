package com.afrotransact.identity.domain;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Organisation ids are owned by the seller service ({@code org_…}, for example {@code org_3k2j9x8q7w6e5r4t3y2u} or
 * {@code org_afrotransact_retail}). Ids created before S205 are UUIDs and stay valid as text.
 */
public final class OrgIds {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,79}");

    private OrgIds() {
    }

    public static boolean valid(String orgId) {
        return orgId != null && VALID.matcher(orgId).matches();
    }

    /** Throws {@link IdentityRuleViolation} ({@code identity.invalid-org-id}) unless {@code orgId} is well formed. */
    public static String requireValid(String orgId) {
        if (!valid(orgId)) {
            throw new IdentityRuleViolation("identity.invalid-org-id", "Not an organisation id",
                    Map.of("orgId", "Letters, digits, '_' or '-', at most 80 characters"));
        }
        return orgId;
    }
}
