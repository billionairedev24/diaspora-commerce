package com.afrotransact.seller.domain;

/** Who may hold a selling approval case: the same two-person rule as {@link AssignmentRules}, for selling applications. */
public final class SellingAssignmentRules {

    private SellingAssignmentRules() {
    }

    public static boolean eligible(SellingApplication app, ReviewStage stage, String candidate,
                                   boolean holdsPermission) {
        return reason(app, stage, candidate, holdsPermission) == null;
    }

    /** Why the candidate may not hold the case, or null when they may. */
    public static String reason(SellingApplication app, ReviewStage stage, String candidate,
                                boolean holdsPermission) {
        if (!holdsPermission) {
            return "They do not hold the " + stage.permission() + " permission";
        }
        if (candidate.equals(app.applicantUserId())) {
            return "They are the applicant";
        }
        if (stage == ReviewStage.APPROVAL && candidate.equals(app.reviewerId())) {
            return "They did the first review: a different person must approve";
        }
        return null;
    }

    public static void require(SellingApplication app, ReviewStage stage, String candidate, boolean holdsPermission,
                               String candidateName) {
        String why = reason(app, stage, candidate, holdsPermission);
        if (why != null) {
            throw new SellerRuleViolation("seller.assignee-not-eligible",
                    (candidateName == null ? "That person" : candidateName) + " can't take this case. " + why + ".");
        }
    }
}
