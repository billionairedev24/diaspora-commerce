package com.afrotransact.identity.adapter.in.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.identity.application.PasswordResetCompleted;
import com.afrotransact.identity.application.PasswordResetCompletion;
import com.afrotransact.identity.application.PasswordResetRequest;
import com.afrotransact.identity.application.PasswordResetService;
import com.afrotransact.identity.application.SignInAttempt;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.RateLimitExceeded;
import com.afrotransact.identity.domain.UserAccount;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * "Forgot password?" on the hosted pages (S300): enter the email, then the 6-digit code and a new password. The pages
 * keep the branding of the app that sent the browser to sign in (the saved authorization request stays in the
 * session), and finishing lands on that app's sign-in page with a success message, so signing in continues the same
 * request. The first step answers the same for every email (no account enumeration).
 */
@Hidden
@Controller
class PasswordResetPageController {

    /** Session attribute: the email a code was asked for. */
    static final String PENDING = "at.reset.email";
    private static final String VIEW = "forgot";
    private static final String CODE_VIEW = "forgot-code";
    private static final String REDIRECT = "redirect:";
    private static final String CODE_PAGE = "/login/forgot/code";
    private static final int MAX_EMAIL = 254;

    private final PasswordResetService resets;
    private final HostedPageModel pages;
    private final MessageSource messages;

    PasswordResetPageController(PasswordResetService resets, HostedPageModel pages, MessageSource messages) {
        this.resets = resets;
        this.pages = pages;
        this.messages = messages;
    }

    @GetMapping("/login/forgot")
    String form(HttpServletRequest request, HttpServletResponse response, Model model) {
        HostedPageContext ctx = pages.apply(model, request, response, HostedPageModel.SIGN_IN);
        String email = pending(request);
        return view(model, email != null ? email : ctx.loginHint(), null, null);
    }

    @PostMapping("/login/forgot")
    String request(@RequestParam(defaultValue = "") String email, HttpServletRequest request,
                   HttpServletResponse response, Model model) {
        HostedPageContext ctx = pages.apply(model, request, response, HostedPageModel.SIGN_IN);
        String normalised = UserAccount.normaliseEmail(email);
        if (!emailLike(normalised)) {
            return view(model, email, text("auth.reset.emailInvalid"), null);
        }
        try {
            resets.request(new PasswordResetRequest(normalised, request.getRemoteAddr(),
                    request.getHeader(HttpHeaders.USER_AGENT), ctx.clientId(), SignInAttempt.HOSTED));
        } catch (RateLimitExceeded ex) {
            return view(model, email, null, locked(ex));
        }
        request.getSession().setAttribute(PENDING, normalised);
        return REDIRECT + CODE_PAGE;
    }

    @GetMapping(CODE_PAGE)
    String codePage(HttpServletRequest request, HttpServletResponse response, Model model) {
        String email = pending(request);
        if (email == null) {
            return REDIRECT + "/login/forgot";
        }
        pages.apply(model, request, response, HostedPageModel.SIGN_IN);
        if (request.getParameter("sent") != null) {
            model.addAttribute("notice", text("auth.reset.resent"));
        }
        if (request.getParameter("wait") != null) {
            model.addAttribute("formError", text("auth.reset.wait"));
        }
        return codeView(model, email, Map.of());
    }

    @PostMapping(CODE_PAGE)
    String complete(@RequestParam(defaultValue = "") String code, @RequestParam(defaultValue = "") String password,
                    @RequestParam(defaultValue = "") String confirmPassword, HttpServletRequest request,
                    HttpServletResponse response, Model model, RedirectAttributes redirect) {
        String email = pending(request);
        if (email == null) {
            return REDIRECT + "/login/forgot";
        }
        HostedPageContext ctx = pages.apply(model, request, response, HostedPageModel.SIGN_IN);
        PasswordResetCompleted done;
        try {
            done = resets.complete(new PasswordResetCompletion(email, code, password, confirmPassword,
                    request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT), ctx.clientId(),
                    SignInAttempt.HOSTED));
        } catch (IdentityRuleViolation ex) {
            Map<String, String> errors = new LinkedHashMap<>();
            if (PasswordResetService.INVALID_CODE.equals(ex.code())) {
                errors.put("code", text("auth.reset.codeInvalid"));
            } else {
                ex.fieldErrors().forEach((field, message) -> errors.put(
                        "newPassword".equals(field) ? "password" : field, message));
            }
            return codeView(model, email, errors);
        } catch (RateLimitExceeded ex) {
            model.addAttribute("formError", locked(ex));
            return codeView(model, email, Map.of());
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(PENDING);
        }
        redirect.addFlashAttribute("email", done.email());
        return REDIRECT + "/login?reset" + (done.staff() ? "=staff" : "");
    }

    @PostMapping("/login/forgot/resend")
    String resend(HttpServletRequest request, HttpServletResponse response, Model model) {
        String email = pending(request);
        if (email == null) {
            return REDIRECT + "/login/forgot";
        }
        HostedPageContext ctx = pages.apply(model, request, response, HostedPageModel.SIGN_IN);
        try {
            resets.request(new PasswordResetRequest(email, request.getRemoteAddr(),
                    request.getHeader(HttpHeaders.USER_AGENT), ctx.clientId(), SignInAttempt.HOSTED));
        } catch (RateLimitExceeded ex) {
            return REDIRECT + CODE_PAGE + "?wait";
        }
        return REDIRECT + CODE_PAGE + "?sent";
    }

    private String view(Model model, String email, String emailError, String formError) {
        model.addAttribute("email", email);
        model.addAttribute("emailError", emailError);
        model.addAttribute("formError", formError);
        return VIEW;
    }

    private String codeView(Model model, String email, Map<String, String> errors) {
        model.addAttribute("email", email);
        model.addAttribute("destination", SignUpPageController.mask(email));
        model.addAttribute("minutes", resets.codeTimeToLive().toMinutes());
        model.addAttribute("errors", errors);
        return CODE_VIEW;
    }

    private String locked(RateLimitExceeded ex) {
        long minutes = Math.max(1, (ex.retryAfter().toSeconds() + 59) / 60);
        return messages.getMessage("auth.reset.locked", new Object[] {minutes}, LocaleContextHolder.getLocale());
    }

    private static boolean emailLike(String email) {
        if (email == null || email.length() > MAX_EMAIL || email.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        int at = email.indexOf('@');
        return at > 0 && at == email.lastIndexOf('@') && email.indexOf('.', at) > at + 1 && !email.endsWith(".");
    }

    private static String pending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(PENDING);
    }

    private String text(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
