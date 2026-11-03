package com.afrotransact.identity.adapter.in.web;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.application.AccountService;
import com.afrotransact.identity.application.SignUpCommand;
import com.afrotransact.identity.application.SignUpResult;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.RateLimitExceeded;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.DomainException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Hosted account creation: the sign-up form, the emailed 6-digit code, and then the same authorization request the
 * app started continues signed in. OIDC {@code prompt=create} lands here; an organisation invitation
 * ({@code invitation} authorize parameter) skips the email code. From Seller Center's "Start selling" /
 * "Offer your services" ({@code intent=sell|provide}) the new account also gets the seller or provider applicant role.
 */
@Hidden
@Controller
class SignUpPageController {

    /** Session attribute: email of the account waiting for its confirmation code. */
    static final String PENDING = "at.signup.pending";
    private static final String VIEW = "signup";
    private static final String VERIFY_VIEW = "signup-verify";
    private static final String SHOP = "shop";
    private static final String DEVICE_COOKIE = "at_device";
    private static final String REDIRECT = "redirect:";
    private static final List<String> FIELDS = List.of("firstName", "lastName", "email", "phone", "password",
            "confirmPassword", "termsAccepted");

    private final AccountService accounts;
    private final HostedPageModel pages;
    private final HostedSignIn hostedSignIn;
    private final MessageSource messages;

    SignUpPageController(AccountService accounts, HostedPageModel pages, HostedSignIn hostedSignIn,
                         MessageSource messages) {
        this.accounts = accounts;
        this.pages = pages;
        this.hostedSignIn = hostedSignIn;
        this.messages = messages;
    }

    @GetMapping("/signup")
    String form(HttpServletRequest request, HttpServletResponse response, Model model) {
        HostedPageContext ctx = pages.apply(model, request, response, HostedPageModel.SIGN_UP);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("email", ctx.loginHint());
        values.put("phoneRegion", PhoneCountries.DEFAULT_REGION);
        values.put("termsAccepted", false);
        values.put("smsConsent", false);
        values.put("referralCode", ctx.referral());
        ensureDeviceCookie(request, response);
        return render(model, values, Map.of(), null);
    }

    @PostMapping("/signup")
    @SuppressWarnings("checkstyle:ParameterNumber")
    String submit(@RequestParam(defaultValue = "") String firstName, @RequestParam(defaultValue = "") String lastName,
                  @RequestParam(defaultValue = "") String email, @RequestParam(defaultValue = "") String phone,
                  @RequestParam(defaultValue = PhoneCountries.DEFAULT_REGION) String phoneRegion,
                  @RequestParam(defaultValue = "") String password,
                  @RequestParam(defaultValue = "") String confirmPassword,
                  @RequestParam(defaultValue = "false") boolean termsAccepted,
                  @RequestParam(defaultValue = "false") boolean smsConsent,
                  @RequestParam(defaultValue = "") String referralCode,
                  HttpServletRequest request, HttpServletResponse response, Model model) {
        HostedPageContext ctx = pages.apply(model, request, response, HostedPageModel.SIGN_UP);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("firstName", firstName);
        values.put("lastName", lastName);
        values.put("email", email);
        values.put("phone", phone);
        String region = PhoneCountries.known(phoneRegion) ? phoneRegion : PhoneCountries.DEFAULT_REGION;
        values.put("phoneRegion", region);
        values.put("termsAccepted", termsAccepted);
        values.put("smsConsent", smsConsent);
        values.put("referralCode", referralCode);
        if (!ctx.app().selfSignUp()) {
            return render(model, values, Map.of(), null);
        }
        SignUpResult result;
        try {
            result = accounts.signUp(new SignUpCommand(firstName, lastName, email, phone, region, password,
                    confirmPassword, termsAccepted, smsConsent, ctx.intent() == null ? SHOP : ctx.intent(),
                    ctx.invitation(), referralCode, deviceId(request), request.getRemoteAddr()));
        } catch (IdentityRuleViolation ex) {
            return render(model, values, ordered(ex.fieldErrors()), ex.fieldErrors().isEmpty() ? ex.getMessage() : null);
        } catch (ConflictException ex) {
            return render(model, values, Map.of("email", ex.getMessage()), null);
        } catch (RateLimitExceeded ex) {
            return render(model, values, Map.of(), ex.getMessage());
        } catch (DomainException ex) {
            return render(model, values, Map.of(), text("auth.error.generic"));
        }
        if (!result.verificationRequired()) {
            return REDIRECT + hostedSignIn.signInAndContinue(request, response, result.email());
        }
        request.getSession().setAttribute(PENDING, result.email());
        return REDIRECT + "/signup/verify";
    }

    @GetMapping("/signup/verify")
    String verifyPage(HttpServletRequest request, HttpServletResponse response, Model model) {
        String email = pending(request);
        if (email == null) {
            return REDIRECT + "/signup";
        }
        pages.apply(model, request, response, HostedPageModel.SIGN_UP);
        if (request.getParameter("sent") != null) {
            model.addAttribute("notice", text("auth.verify.sent"));
        }
        if (request.getParameter("wait") != null) {
            model.addAttribute("formError", text("auth.verify.wait"));
        }
        return verifyView(model, email, null);
    }

    @PostMapping("/signup/verify")
    String verify(@RequestParam(defaultValue = "") String code, HttpServletRequest request,
                  HttpServletResponse response, Model model) {
        String email = pending(request);
        if (email == null) {
            return REDIRECT + "/signup";
        }
        String trimmed = code.trim();
        if (!trimmed.matches("\\d{6}")) {
            pages.apply(model, request, response, HostedPageModel.SIGN_UP);
            return verifyView(model, email, text("auth.verify.invalid"));
        }
        try {
            accounts.verifyEmail(email, trimmed);
        } catch (IdentityRuleViolation ex) {
            pages.apply(model, request, response, HostedPageModel.SIGN_UP);
            return verifyView(model, email, ex.fieldErrors().getOrDefault("code", ex.getMessage()));
        } catch (DomainException ex) {
            pages.apply(model, request, response, HostedPageModel.SIGN_UP);
            return verifyView(model, email, text("auth.verify.invalid"));
        }
        request.getSession().removeAttribute(PENDING);
        return REDIRECT + hostedSignIn.signInAndContinue(request, response, email);
    }

    @PostMapping("/signup/verify/resend")
    String resend(HttpServletRequest request) {
        String email = pending(request);
        if (email == null) {
            return REDIRECT + "/signup";
        }
        try {
            accounts.resendVerification(email);
            return REDIRECT + "/signup/verify?sent";
        } catch (RateLimitExceeded ex) {
            return REDIRECT + "/signup/verify?wait";
        } catch (DomainException ex) {
            request.getSession().removeAttribute(PENDING);
            return REDIRECT + "/login";
        }
    }

    /**
     * A random id in a first-party cookie, set on the sign-up form. Only a keyed hash of it is published with the
     * registration, for the referral program's same-device check (S322); it is not used to track anyone elsewhere.
     */
    private static void ensureDeviceCookie(HttpServletRequest request, HttpServletResponse response) {
        if (deviceId(request) != null) {
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(DEVICE_COOKIE, UUID.randomUUID().toString()).httpOnly(true)
                .secure(request.isSecure()).sameSite("Lax").path("/").maxAge(Duration.ofDays(365)).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String deviceId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies).filter(c -> DEVICE_COOKIE.equals(c.getName())).map(Cookie::getValue)
                .findFirst().orElse(null);
    }

    private String render(Model model, Map<String, Object> values, Map<String, String> errors, String formError) {
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        model.addAttribute("formError", formError);
        model.addAttribute("countries", PhoneCountries.all());
        return VIEW;
    }

    private String verifyView(Model model, String email, String codeError) {
        model.addAttribute("destination", mask(email));
        model.addAttribute("codeError", codeError);
        return VERIFY_VIEW;
    }

    /** Field errors in form order (the error summary lists them top to bottom). */
    private static Map<String, String> ordered(Map<String, String> errors) {
        Map<String, String> out = new LinkedHashMap<>();
        FIELDS.forEach(f -> {
            if (errors.containsKey(f)) {
                out.put(f, errors.get(f));
            }
        });
        errors.forEach(out::putIfAbsent);
        return out;
    }

    private static String pending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(PENDING);
    }

    /** a••••••@example.com */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        int hidden = Math.max(1, Math.min(6, at - 1));
        return email.charAt(0) + "•".repeat(hidden) + email.substring(at).toLowerCase(Locale.ROOT);
    }

    private String text(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
