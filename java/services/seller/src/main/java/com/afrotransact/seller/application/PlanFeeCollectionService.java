package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.domain.PlanFeeCollection;
import com.afrotransact.seller.port.PlanFeeCollectionRepository;

/**
 * How each organisation pays its plan (S298): {@code EARNINGS_FIRST} ("Pay your plan from your sales") or {@code CARD}
 * ("Charge my card", for sellers who book plan fees as a separate card expense). Owners and finance members choose in
 * Plan & billing (and on the onboarding Plan step); staff set the platform default for new sellers and override one
 * organisation with a reason. A change applies from the next invoice: an invoice Stripe already created keeps the
 * choice it was created with. The kill switch ({@code COLLECT_PLAN_FEES_FROM_EARNINGS}) off hides the choice and every
 * invoice is charged to the card.
 */
public interface PlanFeeCollectionService {

    /** Whether the choice is offered and the default for new sellers (onboarding Plan step). */
    Options options();

    /** The organisation's choice (owner / finance members, or billing staff). */
    PlanFeeCollectionView view(String orgId);

    /** Owner / finance: "Pay your plan from your sales" or "Charge my card". 409 while the kill switch is off. */
    PlanFeeCollectionView choose(String orgId, String mode);

    /** Staff (billing:adjust): per-seller override with a reason code (OTHER needs a note). */
    PlanFeeCollectionView override(String orgId, String mode, String reasonCode, String note);

    /** Staff: the platform settings (any Plans & billing permission). */
    PlatformSettings settings();

    /** Staff (plans:manage): the default for sellers who don't choose. */
    PlatformSettings setDefault(String mode);

    /**
     * At activation (inside its transaction): the onboarding choice, or the platform default when there was none.
     *
     * @param chosen the Plan step's choice, null when the applicant kept the default
     */
    void initialise(String orgId, PlanFeeCollection chosen, String actorId);

    /** What the onboarding Plan step offers. */
    record Options(boolean available, PlanFeeCollection platformDefault) {
    }

    /**
     * One organisation's choice.
     *
     * @param available             the kill switch is on: the choice is offered and honoured
     * @param mode                  what the organisation chose (kept while the kill switch is off)
     * @param effective             what its next invoice uses: {@code mode}, or CARD while the kill switch is off
     * @param source                DEFAULT, ONBOARDING, SELLER or STAFF
     * @param appliesFromInvoiceAt  the next invoice a change applies to (the next renewal); null without a paid plan
     * @param inProgressInvoiceId   an invoice being collected now: it keeps the choice it was created with
     */
    record PlanFeeCollectionView(String orgId, boolean available, PlanFeeCollection mode, PlanFeeCollection effective,
                                 String source, Instant changedAt, String changedBy,
                                 PlanFeeCollection platformDefault, Instant appliesFromInvoiceAt,
                                 String appliesFromLabel, String inProgressInvoiceId,
                                 List<PlanFeeCollectionRepository.Change> history) {

        public PlanFeeCollectionView {
            history = List.copyOf(history);
        }
    }

    /** Plans & billing › Settings. */
    record PlatformSettings(boolean earningsCollectionEnabled, PlanFeeCollection planFeeCollectionDefault,
                            String updatedBy, Instant updatedAt) {
    }
}
