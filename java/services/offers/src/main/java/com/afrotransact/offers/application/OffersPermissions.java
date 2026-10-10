package com.afrotransact.offers.application;

/** Permission keys checked by offers use cases (granted by identity-service roles). */
public final class OffersPermissions {

    /** Read own organisation's offers, buy box explanations and seller standing. */
    public static final String READ = "offers:read";
    /** Create and change own organisation's offers; staff also set seller performance inputs. */
    public static final String WRITE = "offers:write";
    /** Create and manage seller-funded coupons for the own organisation. */
    public static final String COUPONS_WRITE = "coupons:write";
    /** Create and manage AfroTransact-funded (platform) coupons; staff only. */
    public static final String COUPONS_PLATFORM_WRITE = "coupons:platform:write";

    private OffersPermissions() {
    }
}
