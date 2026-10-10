package com.afrotransact.content.application;

/**
 * Permission keys checked by content use cases (granted by identity-service roles). Edit implies view.
 * Site content &amp; legal: Super Admin all, Marketing edit, Operations / Support / Trust &amp; Safety view.
 */
public final class ContentPermissions {

    public static final String PLACEMENTS_VIEW = "content:placements.view";
    public static final String PLACEMENTS_EDIT = "content:placements.edit";
    /** Approve placements in review; paid third-party creatives also need a different person than the author. */
    public static final String PLACEMENTS_APPROVE = "content:placements.approve";
    public static final String MARKETS_VIEW = "content:markets.view";
    public static final String MARKETS_EDIT = "content:markets.edit";
    public static final String SITE_VIEW = "content:site.view";
    public static final String SITE_EDIT = "content:site.edit";
    public static final String LEGAL_VIEW = "content:legal.view";
    public static final String LEGAL_EDIT = "content:legal.edit";
    public static final String FLAGS_VIEW = "content:flags.view";
    public static final String FLAGS_EDIT = "content:flags.edit";
    public static final String CAREERS_VIEW = "content:careers.view";
    public static final String CAREERS_EDIT = "content:careers.edit";
    /** Read the audit trail of content changes. */
    public static final String AUDIT_VIEW = "content:audit.view";

    private ContentPermissions() {
    }
}
