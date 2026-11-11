package com.afrotransact.catalog.application;

import java.util.List;

import com.afrotransact.catalog.port.SellingApprovals.Missing;
import com.afrotransact.platform.web.error.ConflictException;

/**
 * The listing cannot go live: the seller has no approved, unexpired approval for a scope that needs one, or the scope
 * is not available to them (HTTP 409). The listing stays a draft; the message is plain words and {@code applyUrl} is
 * the seller-center page where they apply.
 *
 * @param level   APPLY or NOT_AVAILABLE
 * @param applyUrl a path in seller-center ("/selling-approvals?apply=CATEGORY:dietary-supplements"), null when nothing can be applied for
 */
public class SellingApprovalRequired extends ConflictException {

    private static final long serialVersionUID = 1L;
    public static final String CODE = "catalog.selling-approval-required";

    private final String level;
    private final transient List<Missing> missing;
    private final String applyUrl;

    public SellingApprovalRequired(String level, String message, List<Missing> missing, String applyUrl) {
        super(CODE, message);
        this.level = level;
        this.missing = List.copyOf(missing);
        this.applyUrl = applyUrl;
    }

    public String level() {
        return level;
    }

    public List<Missing> missing() {
        return missing;
    }

    public String applyUrl() {
        return applyUrl;
    }
}
