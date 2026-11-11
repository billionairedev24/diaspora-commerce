package com.afrotransact.identity.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Signs a browser in on the hosted pages after a step that already proved who it is (a confirmed sign-up email code,
 * or an organisation invitation link), then continues the pending authorization request, so creating an account ends
 * signed in to the app that asked, like signing in does.
 */
interface HostedSignIn {

    /**
     * Starts the identity session for the account and returns where the browser goes next: the pending authorization
     * request (or the home page), or the two-step page first when a second factor is required.
     */
    String signInAndContinue(HttpServletRequest request, HttpServletResponse response, String email);

    /**
     * Like {@link #signInAndContinue} for a brand-new account whose email was just confirmed: the browser first goes
     * to the optional "Secure your account" page (skippable), which then continues the pending authorization request.
     * Where MFA is mandatory the page is the forced setup instead.
     */
    String signInAndOfferMfaSetup(HttpServletRequest request, HttpServletResponse response, String email);
}
