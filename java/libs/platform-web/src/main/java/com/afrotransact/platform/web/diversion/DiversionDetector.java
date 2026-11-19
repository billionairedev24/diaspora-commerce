package com.afrotransact.platform.web.diversion;

import java.util.List;

/**
 * The one detector for text that steers a customer away from AfroTransact: web addresses, email addresses, phone numbers
 * (digits, words, look-alike characters), contact handles and phrases such as "WhatsApp" or "order direct".
 *
 * <p>Same rules as go/pkg/diversion; both run contracts/diversion/vectors.json. Change a rule in one, change the vectors and
 * the other. It reduces casual diversion; it cannot stop a determined seller.
 */
public interface DiversionDetector {

    /** The sentence the terms and the warning beside the fields carry. */
    String POLICY_SENTENCE = "Off-platform sales and steering buyers away from AfroTransact are not allowed.";

    /** The detector the services use. */
    static DiversionDetector standard() {
        return new DiversionDetectorImpl();
    }

    /**
     * What in the text steers a customer away from AfroTransact; empty when it is clean.
     *
     * @param strict the looser rules, for stores that staff flagged
     */
    List<DiversionFinding> scan(String text, boolean strict);

    /** What to tell the seller (what to remove and why); empty when there are no findings. */
    String message(List<DiversionFinding> findings);
}
