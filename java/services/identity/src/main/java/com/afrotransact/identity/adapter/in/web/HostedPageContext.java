package com.afrotransact.identity.adapter.in.web;

/**
 * What the hosted pages know about the authorization request in progress (saved in the session when the browser was
 * sent to sign in).
 *
 * @param app        the app the request comes from (branding, whether sign-up is offered)
 * @param clientId   OAuth client id, or null when the page was opened directly
 * @param appUrl     where the logo links to (the app's home page)
 * @param loginHint  OIDC {@code login_hint}: prefilled email
 * @param invitation organisation invitation token from an invite link ({@code invitation} parameter)
 * @param pending    whether an authorization request is waiting (signing in continues it)
 * @param intent     Seller Center entry point ({@code intent} parameter): {@code sell} ("Start selling") or
 *                   {@code provide} ("Offer your services"); null otherwise. Brands the pages and decides which
 *                   applicant role an account created here gets.
 */
record HostedPageContext(HostedApp app, String clientId, String appUrl, String loginHint, String invitation,
                         boolean pending, String intent) {

    /** Accepted {@code intent} values. */
    static final String SELL = "sell";
    static final String PROVIDE = "provide";
}
