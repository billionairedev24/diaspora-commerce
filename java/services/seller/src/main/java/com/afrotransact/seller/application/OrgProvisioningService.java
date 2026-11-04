package com.afrotransact.seller.application;

import java.util.Set;

import com.afrotransact.seller.domain.Offering;
import com.afrotransact.seller.domain.OrgKind;

/**
 * Staff-provisioned organisations: an approved seller or provider created directly, without an onboarding
 * application (local and dev demo data, migrations). Off unless {@code at.seller.provisioning-enabled} is true, which
 * only the local and dev profiles set.
 */
public interface OrgProvisioningService {

    /** Permission to provision organisations. */
    String PROVISION = "sellers:provision";

    /**
     * Creates the approved organisation and publishes seller.org.approved and seller.org.updated (offers, cart and the
     * search indexer learn its display name; identity mirrors it). Idempotent on {@code id}: an existing organisation
     * is returned unchanged, except that the departments of the command are added to its set (never removed), so a
     * re-run of the demo seed keeps every organisation consistent with its products.
     */
    Provisioned provision(ProvisionCommand command);

    /**
     * @param id          {@code org_…} (lowercase letters, digits, underscore), or null for a generated id
     * @param kind        onboarding portal (role prefix)
     * @param offering    what it sells
     * @param legalName   registered name
     * @param displayName public store name
     * @param country     ISO 3166-1 alpha-2
     * @param departments catalog department codes the organisation may sell in (null or empty: none added)
     */
    record ProvisionCommand(String id, OrgKind kind, Offering offering, String legalName, String displayName,
                            String country, Set<String> departments) {
    }

    /** The organisation and whether this call created it. */
    record Provisioned(OrgView org, boolean created) {
    }
}
