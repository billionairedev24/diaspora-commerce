package com.afrotransact.identity.adapter.in.web;

import java.util.List;

import com.afrotransact.identity.application.IdentityProviderService;
import com.afrotransact.identity.domain.IdentityProviderSetting;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authorization server's sign-in page: email + password, passkey, and the social providers the platform has
 * enabled. Apps never collect passwords themselves; they redirect here through {@code /oauth2/authorize}.
 */
@Hidden
@RestController
class LoginPageController {

    private final IdentityProviderService providers;
    private final String homeUrl;

    LoginPageController(IdentityProviderService providers, LoginPageSettings settings) {
        this.providers = providers;
        this.homeUrl = settings.homeUrl();
    }

    @GetMapping(path = "/login", produces = MediaType.TEXT_HTML_VALUE)
    String login(HttpServletRequest request) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        StringBuilder body = new StringBuilder("<h1>Sign in</h1>");
        if (request.getParameter("error") != null) {
            body.append("<p class=\"error\" role=\"alert\">")
                    .append("social".equals(request.getParameter("error"))
                            ? "We couldn't sign you in with that provider."
                            : "Email or password is incorrect, or the email isn't confirmed yet.")
                    .append("</p>");
        }
        if (request.getParameter("logout") != null) {
            body.append("<p class=\"note\">You've been signed out.</p>");
        }
        body.append("""
                <form method="post" action="/login">
                  <label for="username">Email</label>
                  <input id="username" name="username" type="email" autocomplete="username webauthn" required autofocus>
                  <label for="password">Password</label>
                  <input id="password" name="password" type="password" autocomplete="current-password" required>
                  <input type="hidden" name="%s" value="%s">
                  <button type="submit">Sign in</button>
                </form>
                <button id="passkey-signin" type="button" class="secondary">Sign in with a passkey</button>
                """.formatted(HtmlPage.esc(csrf.getParameterName()), HtmlPage.esc(csrf.getToken())));
        List<IdentityProviderSetting> enabled = providers.enabledProviders();
        if (!enabled.isEmpty()) {
            body.append("<p class=\"divider\">or continue with</p>");
            for (IdentityProviderSetting p : enabled) {
                body.append("<a class=\"btn secondary\" href=\"/oauth2/authorization/").append(HtmlPage.esc(p.id()))
                        .append("\">").append(HtmlPage.esc(p.displayName())).append("</a>");
            }
        }
        String scripts = """
                <script src="/login/webauthn.js"></script>
                <script>document.addEventListener("DOMContentLoaded", () => setupLogin({"%s": "%s"}, "",
                  document.getElementById("passkey-signin")));</script>
                """.formatted(HtmlPage.esc(csrf.getHeaderName()), HtmlPage.esc(csrf.getToken()));
        return HtmlPage.render("Sign in", body.toString(), scripts);
    }

    @GetMapping("/")
    ResponseEntity<Void> home() {
        return ResponseEntity.status(302).header("Location", homeUrl).build();
    }
}
