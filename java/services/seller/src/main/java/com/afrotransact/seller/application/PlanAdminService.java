package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.PlanInput;
import com.afrotransact.seller.domain.PlanPricesInput;

/**
 * Staff plan catalogue (plans:manage to change, plans:manage / seller:billing to read): create and edit plans,
 * version prices and trials, publish (which syncs Stripe), retire, invite organisations to invite-only plans, and
 * migrate subscribers on older versions to the current one after a preview.
 */
public interface PlanAdminService {

    List<PlanAdminView> plans();

    PlanAdminView plan(String code);

    /** A new plan (DRAFT) with version 1 as a draft. */
    PlanAdminView create(PlanInput plan, PlanPricesInput prices);

    /** Name, tagline, features, limits, visibility, sort (no new version). {@code expectedVersion} = rowVersion. */
    PlanAdminView update(String code, PlanInput plan, long expectedVersion);

    /** Saves the draft version (creating the next version when prices or trial differ from the current one). */
    PlanAdminView saveDraft(String code, PlanPricesInput prices);

    /** Publishes the draft version (the plan becomes ACTIVE), then syncs it to Stripe. */
    PlanAdminView publish(String code);

    PlanAdminView retire(String code);

    PlanAdminView setInvites(String code, List<String> orgIds);

    MigrationPreview migrationPreview(String code);

    /** Moves subscribers (all, or {@code orgIds}) to the current version at their next renewal. */
    MigrationResult migrate(String code, List<String> orgIds);
}
