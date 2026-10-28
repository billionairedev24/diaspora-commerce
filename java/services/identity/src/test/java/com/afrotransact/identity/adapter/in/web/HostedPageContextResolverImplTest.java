package com.afrotransact.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * Which app the hosted pages are branded for. The web apps' URLs follow PUBLIC_WEB_URL / SELLER_CENTER_URL /
 * STAFF_CONSOLE_URL, so Seller Center on another port (tools/dev/local.sh moves it to :3010 when :3001 is taken) or a
 * LAN address is still recognised. Without an authorization request, or for anything unrecognised, the pages are
 * neutral: the staff console's branding only ever comes from a staff console request.
 */
class HostedPageContextResolverImplTest {

    private static final String SF = "http://localhost:3000";
    private static final String SC = "http://localhost:3010";
    private static final String ST = "http://localhost:3002";
    private static final LoginPageSettings SETTINGS = new LoginPageSettings(SF, SF, SC, ST, SF + "/help",
            SF + "/legal/conditions-of-use", SF + "/legal/privacy", SF + "/legal/sms-terms", "AfroTransact Alerts");

    private final HttpSessionRequestCache cache = new HttpSessionRequestCache();
    private final HostedPageContextResolverImpl resolver = new HostedPageContextResolverImpl(cache, SETTINGS);

    private HostedPageContext resolve(Map<String, String> authorizeParams) {
        MockHttpSession session = new MockHttpSession();
        if (authorizeParams != null) {
            MockHttpServletRequest authorize = new MockHttpServletRequest("GET", "/oauth2/authorize");
            authorize.setSession(session);
            authorizeParams.forEach(authorize::addParameter);
            cache.saveRequest(authorize, new MockHttpServletResponse());
        }
        MockHttpServletRequest page = new MockHttpServletRequest("GET", "/login/forgot");
        page.setSession(session);
        return resolver.resolve(page, new MockHttpServletResponse());
    }

    private static Map<String, String> bff(String origin) {
        return Map.of("client_id", "gateway-bff", "redirect_uri", origin + "/api/bff/v1/auth/oidc/callback");
    }

    @Test
    void withoutAnAuthorizationRequestThePagesAreNeutral() {
        HostedPageContext ctx = resolve(null);
        assertThat(ctx.app()).isEqualTo(HostedApp.NEUTRAL);
        assertThat(ctx.pending()).isFalse();
        assertThat(ctx.appUrl()).isEqualTo(SF);
        assertThat(ctx.signedOut()).isFalse();
    }

    @Test
    void eachWebAppIsRecognisedByItsCallbackOrigin() {
        assertThat(resolve(bff(SF)).app()).isEqualTo(HostedApp.SHOP);
        HostedPageContext seller = resolve(bff(SC));
        assertThat(seller.app()).isEqualTo(HostedApp.SELLER);
        assertThat(seller.appUrl()).isEqualTo(SC);
        HostedPageContext staff = resolve(bff(ST));
        assertThat(staff.app()).isEqualTo(HostedApp.STAFF);
        assertThat(staff.appUrl()).isEqualTo(ST);
    }

    @Test
    void staffBrandingNeedsAStaffConsoleRequest() {
        // Seller Center's old default port is not the staff console, and an unknown origin is not either.
        assertThat(resolve(bff("http://localhost:3001")).app()).isEqualTo(HostedApp.NEUTRAL);
        assertThat(resolve(bff("http://192.168.1.50:3002")).app()).isEqualTo(HostedApp.NEUTRAL);
        assertThat(resolve(Map.of("client_id", "some-partner", "redirect_uri", ST + "/cb")).app())
                .isEqualTo(HostedApp.NEUTRAL);
        assertThat(resolve(Map.of("client_id", "staff-console")).app()).isEqualTo(HostedApp.STAFF);
    }

    @Test
    void nativeAndAssistantClientsKeepTheirBranding() {
        assertThat(resolve(Map.of("client_id", "customer-app")).app()).isEqualTo(HostedApp.SHOP);
        assertThat(resolve(Map.of("client_id", "driver-app")).app()).isEqualTo(HostedApp.DRIVER);
        assertThat(resolve(Map.of("client_id", "mcp-client")).app()).isEqualTo(HostedApp.AGENT);
    }

    @Test
    void theSignInAnAppStartsAfterSigningOutSaysSo() {
        Map<String, String> params = new HashMap<>(bff(SC));
        params.put("notice", "signed_out");
        params.put("prompt", "login");
        HostedPageContext ctx = resolve(params);
        assertThat(ctx.app()).isEqualTo(HostedApp.SELLER);
        assertThat(ctx.signedOut()).isTrue();
        params.put("notice", "something-else");
        assertThat(resolve(params).signedOut()).isFalse();
    }
}
