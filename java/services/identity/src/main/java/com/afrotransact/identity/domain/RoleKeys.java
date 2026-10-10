package com.afrotransact.identity.domain;

/** Well-known role keys. The full catalogue lives in the {@code role} table (V2 seed migration). */
public final class RoleKeys {

    public static final String CUSTOMER = "account.customer";
    public static final String SELLER_APPLICANT = "account.seller_applicant";
    public static final String PROVIDER_APPLICANT = "account.provider_applicant";

    public static final String SUPER_ADMIN = "platform.super_admin";
    public static final String DRIVER = "platform.driver";

    public static final String SELLER_OWNER = "seller.owner";
    public static final String PROVIDER_OWNER = "provider.owner";

    private RoleKeys() {
    }
}
