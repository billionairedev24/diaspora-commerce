package com.afrotransact.deliveryruns.domain;

/**
 * The words of an advertorial. The live closing countdown, the town, the delivery day and the fee are added from the
 * window or run when it is shown; this is the part staff write (or the system drafts from the window or run).
 *
 * @param headline up to 140 characters
 * @param body     up to 400 characters (may be empty)
 * @param ctaLabel the button, up to 40 characters
 */
public record AdvertorialCopy(String headline, String body, String ctaLabel) {

    public static final int MAX_HEADLINE = 140;
    public static final int MAX_BODY = 400;
    public static final int MAX_CTA = 40;

    public AdvertorialCopy {
        headline = headline == null ? "" : headline.trim();
        body = body == null ? "" : body.trim();
        ctaLabel = ctaLabel == null ? "" : ctaLabel.trim();
        if (headline.isEmpty() || headline.length() > MAX_HEADLINE) {
            throw new RuleViolation("delivery-runs.invalid-advertorial",
                    "The headline is 1 to " + MAX_HEADLINE + " characters");
        }
        if (body.length() > MAX_BODY) {
            throw new RuleViolation("delivery-runs.invalid-advertorial", "The text is at most " + MAX_BODY
                    + " characters");
        }
        if (ctaLabel.isEmpty() || ctaLabel.length() > MAX_CTA) {
            throw new RuleViolation("delivery-runs.invalid-advertorial",
                    "The button label is 1 to " + MAX_CTA + " characters");
        }
    }
}
