package com.afrotransact.identity.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.Model;

/** Common model of the hosted pages: branding for the pending authorization request, links and social providers. */
interface HostedPageModel {

    /** Panel copy for sign-in screens. */
    String SIGN_IN = "signin";
    /** Panel copy for account-creation screens. */
    String SIGN_UP = "signup";

    /**
     * Adds {@code ctx}, {@code brand}, {@code links} and {@code providers} for a page in the given mode and returns
     * the context.
     */
    HostedPageContext apply(Model model, HttpServletRequest request, HttpServletResponse response, String mode);
}
