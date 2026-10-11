package com.afrotransact.identity.adapter.in.web;

/**
 * Settings for the authorization server's own pages (sign-in, sign-up, two-step verification).
 *
 * @param homeUrl         where a browser goes without a pending authorization request
 * @param storefrontUrl   storefront origin (shopper branding)
 * @param sellerCenterUrl Seller Center origin (seller branding for requests returning there)
 * @param staffConsoleUrl staff console origin (staff branding, no self sign-up)
 * @param supportUrl      help page
 * @param termsUrl        Conditions of Use
 * @param privacyUrl      Privacy Notice
 * @param smsTermsUrl     SMS terms
 * @param smsProgram      name of the text-message programme
 */
public record LoginPageSettings(String homeUrl, String storefrontUrl, String sellerCenterUrl, String staffConsoleUrl,
                                String supportUrl, String termsUrl, String privacyUrl, String smsTermsUrl,
                                String smsProgram) {
}
