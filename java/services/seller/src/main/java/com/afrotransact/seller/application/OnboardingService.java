package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.OnboardingStep;
import com.afrotransact.seller.domain.OrgKind;

/** The applicant's resumable, autosaved onboarding application. */
public interface OnboardingService {

    /**
     * Starts (or resumes) the caller's application, creating a DRAFT organisation with the caller as owner. Fields
     * known from the caller's account (name, email, phone) are prefilled and marked as such.
     */
    default StartResult start(OrgKind kind) {
        return start(kind, ApplicationPrefill.none());
    }

    /**
     * Same, also prefilling what {@code prefill} carries where the token's claims have nothing (phone, store name,
     * business address). A resumed application is returned unchanged: the prefill only applies on creation.
     */
    StartResult start(OrgKind kind, ApplicationPrefill prefill);

    /** The caller's draft with its current step and progress. */
    ApplicationView mine();

    /** Autosave of one step: stores the step's data, validating only the fields present. */
    ApplicationView saveStep(OnboardingStep step, StepInput input);

    /** Validates every step and submits for review (or resubmits after a request for info). */
    ApplicationView submit();

    /** Joins the launch list of a country that is not live; returns true when newly added. */
    boolean joinLaunchList(String country, String email, boolean shoppingUpdates);

    /** Countries open to sellers. */
    List<String> liveCountries();
}
