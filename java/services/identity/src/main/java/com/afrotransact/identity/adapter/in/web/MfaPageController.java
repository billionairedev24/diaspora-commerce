package com.afrotransact.identity.adapter.in.web;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.adapter.in.security.MfaPendingAuthentication;
import com.afrotransact.identity.adapter.in.security.TrustedBrowserCookie;
import com.afrotransact.identity.application.SecondFactorChallenge;
import com.afrotransact.identity.application.SignInService;
import com.afrotransact.identity.domain.MfaFactor;
import com.afrotransact.identity.domain.RateLimitExceeded;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Two-step verification at sign-in: authenticator code, text-message code or recovery code, "trust this browser". */
@Hidden
@RestController
class MfaPageController {

    private final SignInService signIn;
    private final SecurityContextRepository contexts;
    private final RequestCache requestCache;
    private final TrustedBrowserCookie trustedBrowser;
    private final String homeUrl;

    MfaPageController(SignInService signIn, SecurityContextRepository contexts, RequestCache requestCache,
                      TrustedBrowserCookie trustedBrowser, LoginPageSettings settings) {
        this.signIn = signIn;
        this.contexts = contexts;
        this.requestCache = requestCache;
        this.trustedBrowser = trustedBrowser;
        this.homeUrl = settings.homeUrl();
    }

    @GetMapping(path = "/login/mfa", produces = MediaType.TEXT_HTML_VALUE)
    String page(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<MfaPendingAuthentication> pending = pending();
        if (pending.isEmpty()) {
            response.sendRedirect("/login");
            return "";
        }
        UUID userId = UUID.fromString(pending.get().getName());
        SecondFactorChallenge c = signIn.challenge(userId, Optional.empty(), false);
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String hidden = "<input type=\"hidden\" name=\"" + HtmlPage.esc(csrf.getParameterName()) + "\" value=\""
                + HtmlPage.esc(csrf.getToken()) + "\">";
        StringBuilder body = new StringBuilder("<h1>Two-step verification</h1>");
        if (request.getParameter("error") != null) {
            body.append("<p class=\"error\" role=\"alert\">That code isn't right. Try again.</p>");
        }
        if (request.getParameter("wait") != null) {
            body.append("<p class=\"error\" role=\"alert\">Wait a moment before asking for another code.</p>");
        }
        if (request.getParameter("sent") != null) {
            body.append("<p class=\"note\">We texted a code to ").append(HtmlPage.esc(c.smsHint())).append(".</p>");
        }
        String defaultFactor = c.totp() ? "TOTP" : c.sms() ? "SMS" : "RECOVERY_CODE";
        body.append("<form method=\"post\" action=\"/login/mfa\">")
                .append("<label for=\"factor\">Method</label><select id=\"factor\" name=\"factor\">");
        if (c.totp()) {
            body.append(option("TOTP", "Authenticator app", defaultFactor));
        }
        if (c.sms()) {
            body.append(option("SMS", "Text message to " + c.smsHint(), defaultFactor));
        }
        if (c.recovery()) {
            body.append(option("RECOVERY_CODE", "Recovery code", defaultFactor));
        }
        body.append("</select><label for=\"code\">Code</label>")
                .append("<input id=\"code\" name=\"code\" type=\"text\" inputmode=\"numeric\" ")
                .append("autocomplete=\"one-time-code\" required autofocus>")
                .append("<label><input type=\"checkbox\" name=\"trust\" value=\"true\"> Trust this browser for 30 days")
                .append("</label>").append(hidden).append("<button type=\"submit\">Verify</button></form>");
        if (c.sms()) {
            body.append("<form method=\"post\" action=\"/login/mfa/sms\">").append(hidden)
                    .append("<button type=\"submit\" class=\"secondary\">Text me a code</button></form>");
        }
        return HtmlPage.render("Two-step verification", body.toString(), "");
    }

    @PostMapping("/login/mfa/sms")
    void sendSms(HttpServletResponse response) throws IOException {
        Optional<MfaPendingAuthentication> pending = pending();
        if (pending.isEmpty()) {
            response.sendRedirect("/login");
            return;
        }
        try {
            signIn.sendSmsCode(UUID.fromString(pending.get().getName()));
            response.sendRedirect("/login/mfa?sent");
        } catch (RateLimitExceeded ex) {
            response.sendRedirect("/login/mfa?wait");
        }
    }

    @PostMapping("/login/mfa")
    void verify(@RequestParam String factor, @RequestParam String code,
                @RequestParam(required = false, defaultValue = "false") boolean trust,
                HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<MfaPendingAuthentication> pending = pending();
        if (pending.isEmpty()) {
            response.sendRedirect("/login");
            return;
        }
        UUID userId = UUID.fromString(pending.get().getName());
        MfaFactor parsed;
        try {
            parsed = MfaFactor.valueOf(factor.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            response.sendRedirect("/login/mfa?error");
            return;
        }
        if (!signIn.verifySecondFactor(userId, parsed, code)) {
            response.sendRedirect("/login/mfa?error");
            return;
        }
        Authentication firstFactor = pending.get().firstFactor();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(firstFactor);
        SecurityContextHolder.setContext(context);
        request.changeSessionId();
        contexts.saveContext(context, request, response);
        if (trust) {
            trustedBrowser.write(response, signIn.trustBrowser(userId, request.getHeader("User-Agent")));
        }
        SavedRequest saved = requestCache.getRequest(request, response);
        requestCache.removeRequest(request, response);
        response.sendRedirect(saved != null ? saved.getRedirectUrl() : homeUrl);
    }

    private static Optional<MfaPendingAuthentication> pending() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        return current instanceof MfaPendingAuthentication p ? Optional.of(p) : Optional.empty();
    }

    private static String option(String value, String label, String selected) {
        return "<option value=\"" + value + "\"" + (value.equals(selected) ? " selected" : "") + ">"
                + HtmlPage.esc(label) + "</option>";
    }
}
