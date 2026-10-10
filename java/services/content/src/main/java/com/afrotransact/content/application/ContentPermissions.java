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
    /** Staff: read every organisation's sponsored-products campaigns and reports. */
    public static final String ADS_VIEW = "content:ads.view";
    /** Staff: create, edit, pause and end any organisation's campaigns. */
    public static final String ADS_EDIT = "content:ads.edit";
    /** Seller members (owner, marketing): manage their own organisation's campaigns (Seller Center → Advertising). */
    public static final String ADS_MANAGE = "ads:manage";
    /** Service scope of search-service: read the campaigns eligible to serve. */
    public static final String ADS_SERVE = "ads:serve";
    /** Service scope of seller-service: read charged ads spend per organisation and day (metered billing, S206). */
    public static final String ADS_SPEND_READ = "ads:spend.read";

    private ContentPermissions() {
    }
}
