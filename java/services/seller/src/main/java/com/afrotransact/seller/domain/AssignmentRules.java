package com.afrotransact.seller.domain;

/**
 * Who may hold a case. The two-person rule holds per application: nobody holds their own application, and the person
 * who did step 1 never holds the step 2 of the same application, even when they also hold the approver role.
 */
public final class AssignmentRules {

    private AssignmentRules() {
    }

    /** Whether {@code candidate} may hold {@code app} at {@code stage}; {@code holdsPermission} is the stage permission. */
    public static boolean eligible(Application app, ReviewStage stage, String candidate, boolean holdsPermission) {
        return reason(app, stage, candidate, holdsPermission) == null;
    }

    /** Why the candidate may not hold the case, or null when they may. */
    public static String reason(Application app, ReviewStage stage, String candidate, boolean holdsPermission) {
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

    /** Throws {@link SellerRuleViolation} naming the reason when the candidate may not hold the case. */
    public static void require(Application app, ReviewStage stage, String candidate, boolean holdsPermission,
                               String candidateName) {
        String why = reason(app, stage, candidate, holdsPermission);
        if (why != null) {
            throw new SellerRuleViolation("seller.assignee-not-eligible",
                    (candidateName == null ? "That person" : candidateName) + " can't take this case. " + why + ".");
        }
    }
}
