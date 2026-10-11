package com.afrotransact.identity.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Reads the pending authorization request to brand and prefill the hosted pages. */
interface HostedPageContextResolver {

    HostedPageContext resolve(HttpServletRequest request, HttpServletResponse response);
}
