package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Staff input for a trial campaign. */
public record CampaignInput(String name, String promoCode, Integer trialDays, Instant signupFrom, Instant signupTo,
                            OrgKind kind, List<String> eligiblePlans, Integer maxRedemptions, CampaignStatus status) {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{2,39}");

    public CampaignInput {
        eligiblePlans = eligiblePlans == null ? List.of() : List.copyOf(eligiblePlans);
        promoCode = TrialCampaign.normalise(promoCode);
    }

    /** 422 seller.invalid-campaign with every field error. */
    public void validate() {
        Map<String, String> e = new LinkedHashMap<>();
        if (name == null || name.isBlank() || name.length() > 120) {
            e.put("name", "A name of up to 120 characters");
        }
        if (trialDays == null || trialDays < 1 || trialDays > 365) {
            e.put("trialDays", "1 to 365 days");
        }
        if (promoCode != null && !CODE.matcher(promoCode).matches()) {
            e.put("promoCode", "3–40 letters, digits, - or _");
        }
        if (promoCode == null && signupFrom == null && signupTo == null) {
            e.put("signupFrom", "Give a promo code, a sign-up window, or both");
        }
        if (signupFrom != null && signupTo != null && !signupTo.isAfter(signupFrom)) {
            e.put("signupTo", "Ends after it starts");
        }
        if (maxRedemptions != null && maxRedemptions < 1) {
            e.put("maxRedemptions", "At least 1");
        }
        if (!e.isEmpty()) {
            throw new SellerRuleViolation("seller.invalid-campaign", "Check the campaign: "
                    + String.join(", ", e.keySet()), e);
        }
    }
}
