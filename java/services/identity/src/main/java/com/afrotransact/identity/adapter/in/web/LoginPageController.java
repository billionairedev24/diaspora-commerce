package com.afrotransact.identity.adapter.in.web;

import java.util.Locale;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The authorization server's sign-in page (email + password, passkey, the social providers that are configured and
 * enabled) and the home redirect; the "forgot password" pages are {@link PasswordResetPageController}. Apps never
 * collect passwords themselves: they send the browser here through {@code /oauth2/authorize}, so this is the single
 * place credentials are entered (SSO).
 */
@Hidden
@Controller
class LoginPageController {

    private final HostedPageModel pages;
    private final MessageSource messages;
    private final String homeUrl;

    LoginPageController(HostedPageModel pages, MessageSource messages, LoginPageSettings settings) {
        this.pages = pages;
        this.messages = messages;
        this.homeUrl = settings.homeUrl();
    }

    @GetMapping("/login")
    String login(HttpServletRequest request, HttpServletResponse response, Model model) {
        HostedPageContext ctx = pages.apply(model, request, response, HostedPageModel.SIGN_IN);
        Locale locale = request.getLocale();
        String error = request.getParameter("error");
        if (error != null) {
            String key = switch (error) {
                case "social" -> "auth.error.social";
                case "locked" -> "auth.error.locked";
                case "expired" -> "auth.error.expired";
                default -> "auth.error.credentials";
            };
            model.addAttribute("error", messages.getMessage(key, null, locale));
        } else if (request.getParameter("reset") != null) {
            // Password reset (S300): signed out everywhere; staff still verify their second factor next.
            String key = "staff".equals(request.getParameter("reset")) ? "auth.reset.doneStaff" : "auth.reset.done";
            model.addAttribute("success", messages.getMessage(key, null, locale));
        } else if (request.getParameter("logout") != null || ctx.signedOut()) {
            model.addAttribute("notice", messages.getMessage("auth.signedOut", null, locale));
        }
        // After a reset the email comes as a flash attribute; otherwise the app's login_hint.
        if (!model.containsAttribute("email")) {
            model.addAttribute("email", ctx.loginHint());
        }
        return "login";
    }

    @GetMapping("/")
    ResponseEntity<Void> home() {
        return ResponseEntity.status(302).header("Location", homeUrl).build();
    }
}
