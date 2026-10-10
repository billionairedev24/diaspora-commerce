package com.afrotransact.retail.domain;

/** Who must approve a PO: a buyer lead (standard) or, above the threshold, Finance as well. */
public enum ApprovalTier {
    STANDARD,
    FINANCE
}
