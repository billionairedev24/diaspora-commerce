package com.afrotransact.identity.adapter.in.web;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.adapter.in.security.MfaPendingAuthentication;
import com.afrotransact.identity.adapter.in.security.TrustedBrowserCookie;
import com.afrotransact.identity.application.SecondFactorChallenge;
import com.afrotransact.identity.application.SignInAccount;
import com.afrotransact.identity.application.SignInAttempt;
import com.afrotransact.identity.application.SignInProtectionService;
import com.afrotransact.identity.application.SignInService;
import com.afrotransact.identity.domain.MfaFactor;
import com.afrotransact.identity.domain.RateLimitExceeded;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Two-step verification at sign-in: authenticator code, text-message code or recovery code, "trust this browser". */
@Hidden
@Controller
class MfaPageController {

    private final SignInService signIn;
    private final SignInProtectionService protection;
    private final SecurityContextRepository contexts;
    private final RequestCache requestCache;
    private final TrustedBrowserCookie trustedBrowser;
    private final String homeUrl;
    private final HostedPageModel pages;
    private final MessageSource messages;

    MfaPageController(SignInService signIn, SignInProtectionService protection, SecurityContextRepository contexts,
                      RequestCache requestCache, TrustedBrowserCookie trustedBrowser, LoginPageSettings settings,
                      HostedPageModel pages, MessageSource messages) {
        this.signIn = signIn;
        this.protection = protection;
        this.contexts = contexts;
        this.requestCache = requestCache;
        this.trustedBrowser = trustedBrowser;
        this.homeUrl = settings.homeUrl();
        this.pages = pages;
        this.messages = messages;
    }

    @GetMapping("/login/mfa")
    String page(HttpServletRequest request, HttpServletResponse response, Model model) {
        Optional<MfaPendingAuthentication> pending = pending();
        if (pending.isEmpty()) {
            return "redirect:/login";
        }
        UUID userId = UUID.fromString(pending.get().getName());
        SecondFactorChallenge c = signIn.challenge(userId, Optional.empty(), false);
        pages.apply(model, request, response, HostedPageModel.SIGN_IN);
        Locale locale = LocaleContextHolder.getLocale();
        if (request.getParameter("error") != null) {
            model.addAttribute("formError", messages.getMessage("auth.mfa.wrong", null, locale));
        }
        if (request.getParameter("wait") != null) {
            model.addAttribute("formError", messages.getMessage("auth.mfa.wait", null, locale));
        }
        if (request.getParameter("sent") != null) {
            model.addAttribute("notice", messages.getMessage("auth.mfa.sent", new Object[] {c.smsHint()}, locale));
        }
        model.addAttribute("challenge", c);
        model.addAttribute("defaultFactor", c.totp() ? "TOTP" : c.sms() ? "SMS" : "RECOVERY_CODE");
        return "mfa";
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
        String email = signIn.findAccount(userId.toString()).map(SignInAccount::email).orElse(null);
        if (protection.lockedFor(email, request.getRemoteAddr()).isPresent()) {
            lockedOut(request, response);
            return;
        }
        if (!signIn.verifySecondFactor(userId, parsed, code)) {
            boolean locked = protection.recordFailure(attempt(request, userId, email, parsed, "wrong-code"))
                    .isPresent();
            if (locked) {
                lockedOut(request, response);
                return;
            }
            response.sendRedirect("/login/mfa?error");
            return;
        }
        protection.recordSuccess(attempt(request, userId, email, parsed, null));
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

    /** Locked while two-step verification was pending: drop the half-finished sign-in. */
    private void lockedOut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContext empty = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(empty);
        contexts.saveContext(empty, request, response);
        response.sendRedirect("/login?error=locked");
    }

    private static SignInAttempt attempt(HttpServletRequest request, UUID userId, String email, MfaFactor factor,
                                         String reason) {
        return new SignInAttempt(userId, email, request.getRemoteAddr(), request.getHeader("User-Agent"), null,
                SignInAttempt.HOSTED, "MFA_" + factor.name(), reason);
    }

    private static Optional<MfaPendingAuthentication> pending() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        return current instanceof MfaPendingAuthentication p ? Optional.of(p) : Optional.empty();
    }
}
