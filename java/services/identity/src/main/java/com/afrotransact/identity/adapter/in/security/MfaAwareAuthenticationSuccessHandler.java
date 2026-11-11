package com.afrotransact.identity.adapter.in.security;

import java.io.IOException;
import java.util.UUID;

import com.afrotransact.identity.application.SecondFactorChallenge;
import com.afrotransact.identity.application.SignInService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * After the password or social first factor: if the person enrolled a second factor (and the browser is not trusted)
 * the session holds a {@link MfaPendingAuthentication} and the browser goes to {@code /login/mfa}; if MFA is mandatory
 * for them and nothing is enrolled it goes to the forced setup page {@code /login/mfa/setup} (no code is sent);
 * otherwise it continues to the saved request (usually {@code /oauth2/authorize}).
 */
public class MfaAwareAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    public static final String MFA_PAGE = "/login/mfa";
    public static final String SETUP_PAGE = "/login/mfa/setup";

    private final SignInService signIn;
    private final HostedLoginProtection protection;
    private final TrustedBrowserCookie trustedBrowser;
    private final SecurityContextRepository contexts;
    private final AuthenticationSuccessHandler next;

    public MfaAwareAuthenticationSuccessHandler(SignInService signIn, HostedLoginProtection protection,
                                                TrustedBrowserCookie trustedBrowser,
                                                SecurityContextRepository contexts, String defaultTargetUrl) {
        this.signIn = signIn;
        this.protection = protection;
        this.trustedBrowser = trustedBrowser;
        this.contexts = contexts;
        SavedRequestAwareAuthenticationSuccessHandler saved = new SavedRequestAwareAuthenticationSuccessHandler();
        saved.setDefaultTargetUrl(defaultTargetUrl);
        this.next = saved;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        UUID userId = UUID.fromString(authentication.getName());
        SecondFactorChallenge challenge = signIn.challenge(userId, trustedBrowser.read(request), false);
        if (!challenge.pending()) {
            if (authentication instanceof UsernamePasswordAuthenticationToken) {
                protection.succeeded(request, userId);
            }
            next.onAuthenticationSuccess(request, response, authentication);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new MfaPendingAuthentication(authentication));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        if (challenge.enrolmentRequired()) {
            // Mandatory but nothing enrolled: no code is sent, the person sets up a factor first.
            response.sendRedirect(request.getContextPath() + SETUP_PAGE);
            return;
        }
        if (challenge.emailIsDefault()) {
            signIn.sendEmailCodeIfDefault(userId);
        }
        response.sendRedirect(request.getContextPath() + MFA_PAGE);
    }
}
