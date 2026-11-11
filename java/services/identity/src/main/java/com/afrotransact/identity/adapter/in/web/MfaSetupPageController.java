package com.afrotransact.identity.adapter.in.web;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.adapter.in.security.MfaAwareAuthenticationSuccessHandler;
import com.afrotransact.identity.adapter.in.security.MfaPendingAuthentication;
import com.afrotransact.identity.application.SecondFactorEnrolment;
import com.afrotransact.identity.application.SignInAttempt;
import com.afrotransact.identity.application.SignInProtectionService;
import com.afrotransact.identity.application.SignInService;
import com.afrotransact.identity.application.TotpSetup;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.RateLimitExceeded;
import com.afrotransact.platform.web.error.DomainException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
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

/**
 * "Secure your account": set up an authenticator app or email codes on the hosted pages.
 *
 * <ul>
 *   <li><b>Optional</b> (right after sign-up confirmed the email, or later): the browser is signed in, the page has a
 *       "Skip for now" button; skipping continues to the app and the reminder waits two weeks.</li>
 *   <li><b>Forced</b> (MFA is mandatory for the person, a portal switch or staff, and nothing is enrolled): the
 *       session is still {@link MfaPendingAuthentication}; no code was sent; there is no skip, and the session is only
 *       completed after a factor is confirmed.</li>
 * </ul>
 */
@Hidden
@Controller
class MfaSetupPageController {

    private static final String VIEW = "mfa-setup";
    private static final String REDIRECT = "redirect:";
    private static final String SETUP = MfaAwareAuthenticationSuccessHandler.SETUP_PAGE;

    private final SecondFactorEnrolment enrolment;
    private final SignInService signIn;
    private final SignInProtectionService protection;
    private final SecurityContextRepository contexts;
    private final RequestCache requestCache;
    private final HostedPageModel pages;
    private final MessageSource messages;
    private final String homeUrl;

    MfaSetupPageController(SecondFactorEnrolment enrolment, SignInService signIn,
                           SignInProtectionService protection, SecurityContextRepository contexts,
                           RequestCache requestCache, HostedPageModel pages, MessageSource messages,
                           LoginPageSettings settings) {
        this.enrolment = enrolment;
        this.signIn = signIn;
        this.protection = protection;
        this.contexts = contexts;
        this.requestCache = requestCache;
        this.pages = pages;
        this.messages = messages;
        this.homeUrl = settings.homeUrl();
    }

    @GetMapping("/login/mfa/setup")
    String choose(HttpServletRequest request, HttpServletResponse response, Model model) {
        Who who = who();
        if (who == null) {
            return REDIRECT + "/login";
        }
        if (who.forced() && !signIn.challenge(who.userId(), Optional.empty(), false).enrolmentRequired()) {
            return REDIRECT + MfaAwareAuthenticationSuccessHandler.MFA_PAGE;
        }
        return render(model, request, response, who, "choose");
    }

    @PostMapping("/login/mfa/setup/totp")
    String startTotp(HttpServletRequest request, HttpServletResponse response, Model model) {
        Who who = guard();
        if (who == null) {
            return REDIRECT + "/login";
        }
        TotpSetup setup = enrolment.startTotp(who.userId());
        return totpPage(model, request, response, who, setup, null);
    }

    @PostMapping("/login/mfa/setup/totp/confirm")
    String confirmTotp(@RequestParam(defaultValue = "") String code, HttpServletRequest request,
                       HttpServletResponse response, Model model) {
        Who who = guard();
        if (who == null) {
            return REDIRECT + "/login";
        }
        if (limited(request, who)) {
            return REDIRECT + "/login?error=locked";
        }
        try {
            List<String> recovery = enrolment.confirmTotp(who.userId(), code.trim());
            return done(model, request, response, who, recovery);
        } catch (IdentityRuleViolation wrong) {
            failed(request, who);
            Optional<TotpSetup> pending = enrolment.pendingTotp(who.userId());
            if (pending.isEmpty()) {
                return render(model, request, response, who, "choose");
            }
            return totpPage(model, request, response, who, pending.get(), text("auth.mfa.wrong"));
        } catch (DomainException ex) {
            return render(model, request, response, who, "choose");
        }
    }

    @PostMapping("/login/mfa/setup/email")
    String startEmail(HttpServletRequest request, HttpServletResponse response, Model model) {
        Who who = guard();
        if (who == null) {
            return REDIRECT + "/login";
        }
        try {
            enrolment.startEmailOtp(who.userId());
            model.addAttribute("notice", text("auth.setup.emailSent"));
        } catch (RateLimitExceeded tooSoon) {
            model.addAttribute("formError", text("auth.mfa.wait"));
        } catch (IdentityRuleViolation | DomainException ex) {
            model.addAttribute("formError", text("auth.error.generic"));
        }
        return render(model, request, response, who, "email");
    }

    @PostMapping("/login/mfa/setup/email/confirm")
    String confirmEmail(@RequestParam(defaultValue = "") String code, HttpServletRequest request,
                        HttpServletResponse response, Model model) {
        Who who = guard();
        if (who == null) {
            return REDIRECT + "/login";
        }
        if (limited(request, who)) {
            return REDIRECT + "/login?error=locked";
        }
        try {
            List<String> recovery = enrolment.confirmEmailOtp(who.userId(), code.trim());
            return done(model, request, response, who, recovery);
        } catch (IdentityRuleViolation | DomainException wrong) {
            failed(request, who);
            model.addAttribute("formError", text("auth.mfa.wrong"));
            return render(model, request, response, who, "email");
        }
    }

    /** Optional setup only: "Skip for now" continues to the app; the reminder comes back after the interval. */
    @PostMapping("/login/mfa/setup/skip")
    String skip(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Who who = who();
        if (who == null) {
            return REDIRECT + "/login";
        }
        if (who.forced()) {
            return REDIRECT + SETUP;
        }
        enrolment.dismissSetupPrompt(who.userId());
        return REDIRECT + continueUrl(request, response);
    }

    /** After the recovery codes were shown: complete the session (forced) or continue to the app. */
    @PostMapping("/login/mfa/setup/finish")
    String finish(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Who who = who();
        if (who == null) {
            return REDIRECT + "/login";
        }
        if (who.forced()) {
            if (signIn.challenge(who.userId(), Optional.empty(), false).enrolmentRequired()) {
                return REDIRECT + SETUP;
            }
            MfaPendingAuthentication pending = (MfaPendingAuthentication) SecurityContextHolder.getContext()
                    .getAuthentication();
            Authentication firstFactor = pending.firstFactor();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(firstFactor);
            SecurityContextHolder.setContext(context);
            request.changeSessionId();
            contexts.saveContext(context, request, response);
            String email = signIn.findAccount(who.userId().toString()).map(a -> a.email()).orElse(null);
            protection.recordSuccess(new SignInAttempt(who.userId(), email, request.getRemoteAddr(),
                    request.getHeader("User-Agent"), null, SignInAttempt.HOSTED, "MFA_ENROL", null));
        }
        return REDIRECT + continueUrl(request, response);
    }

    private String done(Model model, HttpServletRequest request, HttpServletResponse response, Who who,
                        List<String> recoveryCodes) {
        model.addAttribute("recoveryCodes", recoveryCodes);
        return render(model, request, response, who, "done");
    }

    private String totpPage(Model model, HttpServletRequest request, HttpServletResponse response, Who who,
                            TotpSetup setup, String error) {
        model.addAttribute("secret", setup.secret());
        model.addAttribute("qr", QrCodes.svg(setup.otpauthUri(), text("auth.setup.qrLabel")));
        model.addAttribute("formError", error);
        return render(model, request, response, who, "totp");
    }

    private String render(Model model, HttpServletRequest request, HttpServletResponse response, Who who,
                          String mode) {
        pages.apply(model, request, response, HostedPageModel.SIGN_IN);
        model.addAttribute("mode", mode);
        model.addAttribute("skippable", !who.forced());
        model.addAttribute("emailHint", signIn.challenge(who.userId(), Optional.empty(), false).emailHint());
        return VIEW;
    }

    private String continueUrl(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = requestCache.getRequest(request, response);
        requestCache.removeRequest(request, response);
        return saved != null ? saved.getRedirectUrl() : homeUrl;
    }

    private boolean limited(HttpServletRequest request, Who who) {
        String email = signIn.findAccount(who.userId().toString()).map(a -> a.email()).orElse(null);
        return protection.lockedFor(email, request.getRemoteAddr()).isPresent();
    }

    private void failed(HttpServletRequest request, Who who) {
        String email = signIn.findAccount(who.userId().toString()).map(a -> a.email()).orElse(null);
        protection.recordFailure(new SignInAttempt(who.userId(), email, request.getRemoteAddr(),
                request.getHeader("User-Agent"), null, SignInAttempt.HOSTED, "MFA_ENROL", "wrong-code"));
    }

    /** The person on this browser: pending (forced setup) or signed in (optional setup); null when neither. */
    private static Who who() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current instanceof MfaPendingAuthentication pending) {
            return new Who(UUID.fromString(pending.getName()), true);
        }
        if (current == null || current instanceof AnonymousAuthenticationToken || !current.isAuthenticated()) {
            return null;
        }
        try {
            return new Who(UUID.fromString(current.getName()), false);
        } catch (IllegalArgumentException notAnAccount) {
            return null;
        }
    }

    /** {@link #who()}, and a forced person must still need to enrol (a stale tab cannot enrol twice). */
    private Who guard() {
        Who who = who();
        if (who != null && who.forced()
                && !signIn.challenge(who.userId(), Optional.empty(), false).enrolmentRequired()) {
            return null;
        }
        return who;
    }

    private String text(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private record Who(UUID userId, boolean forced) {
    }
}
