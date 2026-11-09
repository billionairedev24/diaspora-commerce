package com.afrotransact.taxreturns.application;

import java.util.List;

import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.TaxProfile;
import com.afrotransact.taxreturns.port.TaxProfileRepository;

/** Category tax profiles: browse them and change a profile's status gate with a reason. */
public interface TaxProfileService {

    /** Profiles, optionally by department, status or text (tax:read). */
    List<ProfileView> list(String department, ProfileStatus status, String q);

    /** One profile with its facts and the decision table (tax:read); 404 tax.profile-not-found. */
    ProfileView get(String categoryCode);

    /**
     * Changes the gate (tax:classify-admin). Lifting NEEDS_APPROVAL, CONFLICT_OPEN or BLOCKED_BUNDLE to ACTIVE records
     * who approved and the sign-off reference, starts a new rule version and publishes tax.classification.changed.
     */
    ProfileView changeStatus(String categoryCode, StatusChange change);

    /**
     * @param status      the new gate
     * @param reason      why (at least 3 characters)
     * @param approvedBy  the tax professional, needed when lifting a gate that needs sign-off
     * @param decisionRef reference of the signed-off decision or opinion letter
     */
    record StatusChange(ProfileStatus status, String reason, String approvedBy, String decisionRef) {
    }

    /**
     * @param profile the profile
     * @param stamp   who changed its status last (null when never changed since the seed)
     */
    record ProfileView(TaxProfile profile, TaxProfileRepository.Stamp stamp, String ruleVersion) {
    }
}
