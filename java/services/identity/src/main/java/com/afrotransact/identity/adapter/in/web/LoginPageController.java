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
 * enabled), the "forgot password" help page and the home redirect. Apps never collect passwords themselves: they send
 * the browser here through {@code /oauth2/authorize}, so this is the single place credentials are entered (SSO).
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
        }
        if (request.getParameter("logout") != null) {
            model.addAttribute("notice", messages.getMessage("auth.signedOut", null, locale));
        }
        model.addAttribute("email", ctx.loginHint());
        return "login";
    }

    @GetMapping("/login/forgot")
    String forgot(HttpServletRequest request, HttpServletResponse response, Model model) {
        pages.apply(model, request, response, HostedPageModel.SIGN_IN);
        return "forgot";
    }

    @GetMapping("/")
    ResponseEntity<Void> home() {
        return ResponseEntity.status(302).header("Location", homeUrl).build();
    }
}
