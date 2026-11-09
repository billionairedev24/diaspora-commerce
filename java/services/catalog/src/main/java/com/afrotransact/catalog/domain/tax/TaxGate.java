package com.afrotransact.catalog.domain.tax;

import java.util.Set;

/**
 * What a category's tax status means for a seller, in plain words (no tax jargon), and what happens next. The status
 * itself is decided by tax-returns' category tax profile (docs/engineering/category-tax-design.md section 6).
 */
public final class TaxGate {

    public static final String ACTIVE = "ACTIVE";
    /** Gates where no seller action helps: the product stays a draft and cannot be submitted. */
    private static final Set<String> CLOSED = Set.of("NEEDS_APPROVAL", "BLOCKED_BUNDLE", "CONFLICT_OPEN");

    private TaxGate() {
    }

    /** True when nothing stops a seller from publishing into the category on account of its tax status. */
    public static boolean open(String status) {
        return status == null || ACTIVE.equals(status);
    }

    /** True when the category cannot take a submission at all (the seller can still save a draft). */
    public static boolean closed(String status) {
        return status != null && CLOSED.contains(status);
    }

    /** The plain message shown next to the category and when a submission is refused; null for an open category. */
    public static String message(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "NEEDS_APPROVAL" -> "This category is not open for publishing yet. Our team is still approving how it is "
                    + "handled for sales tax. You can save your product as a draft; we will tell you when the category opens.";
            case "BLOCKED_BUNDLE" -> "Gift baskets and bundles cannot be published yet. Each item inside a bundle needs its own "
                    + "listing with its own details, and that is not ready. Please list the items separately for now.";
            case "CONFLICT_OPEN" -> "This category is on hold while we settle how it is handled for sales tax. You can save "
                    + "your product as a draft; we will tell you when the category opens.";
            case "REFINE_CODE" -> "Items in this category get one extra check by our team before they go live. You can "
                    + "submit as usual; we will confirm the last details and publish it.";
            default -> null;
        };
    }

    /** The message for the status of a variant that is not ready, in plain words. */
    public static String variantMessage(TaxStatus status) {
        return switch (status) {
            case READY -> "Ready.";
            case MISSING_FACTS -> "Some answers are still needed before this can go live.";
            case PENDING_REVIEW -> "Waiting for our team to check the details. This usually needs no action from you.";
            case BLOCKED -> "This cannot be published yet: a bundle must list every item on its own.";
            case NEEDS_APPROVAL -> "This category is not open for publishing yet; we will tell you when it opens.";
            case REFINE_CODE -> "Our team still has to confirm one last detail before this goes live.";
            case CONFLICT_OPEN -> "This category is on hold while we settle how it is handled.";
            case REJECTED -> "Our team could not approve these details. Check the answers and the proof you uploaded, then save again.";
        };
    }
}
