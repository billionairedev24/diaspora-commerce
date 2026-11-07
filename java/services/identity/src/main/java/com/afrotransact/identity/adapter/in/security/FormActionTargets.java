package com.afrotransact.identity.adapter.in.security;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Where a hosted page's forms may lead besides this server: the CSP {@code form-action} source expressions for the
 * page being rendered. Chromium also applies {@code form-action} to every redirect that follows a form post, so the
 * sign-in form must allow the redirect target of the pending authorization request (the app's callback).
 */
public interface FormActionTargets {

    /** CSP source expressions (an origin, or {@code scheme:} for an app's custom scheme); never blank, never unsafe. */
    List<String> sources(HttpServletRequest request, HttpServletResponse response);
}
