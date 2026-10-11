package com.afrotransact.identity.adapter.in.web;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.adapter.in.security.HostedLoginEntryPoint;
import com.afrotransact.identity.adapter.in.security.MfaAwareAuthenticationSuccessHandler;
import com.afrotransact.identity.adapter.in.security.MfaPendingAuthentication;
import com.afrotransact.identity.adapter.in.security.TrustedBrowserCookie;
import com.afrotransact.identity.application.SignInAttempt;
import com.afrotransact.identity.application.SignInProtectionService;
import com.afrotransact.identity.application.SignInService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

@Component
class HostedSignInImpl implements HostedSignIn {

    private final UserDetailsService accounts;
    private final SignInService signIn;
    private final SignInProtectionService protection;
    private final TrustedBrowserCookie trustedBrowser;
    private final SecurityContextRepository contexts;
    private final RequestCache requestCache;
    private final String homeUrl;
    private final Clock clock;

    HostedSignInImpl(UserDetailsService accounts, SignInService signIn, SignInProtectionService protection,
                     TrustedBrowserCookie trustedBrowser, SecurityContextRepository contexts,
                     RequestCache requestCache, LoginPageSettings settings, Clock clock) {
        this.accounts = accounts;
        this.signIn = signIn;
        this.protection = protection;
        this.trustedBrowser = trustedBrowser;
        this.contexts = contexts;
        this.requestCache = requestCache;
        this.homeUrl = settings.homeUrl();
        this.clock = clock;
    }

    @Override
    public String signInAndContinue(HttpServletRequest request, HttpServletResponse response, String email) {
        UserDetails user = accounts.loadUserByUsername(email);
        if (!user.isEnabled()) {
            return HostedLoginEntryPoint.LOGIN_PAGE + "?error";
        }
        // The person just set this account's password (or proved the mailbox through an invitation link): the same
        // password factor a form sign-in records, with its time (the ID token's auth_time).
        List<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
        authorities.add(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(Instant.now(clock)).build());
        // No credentials in the principal: it is stored with the authorization (like a form login after erasure).
        UserDetails principal = User.withUsername(user.getUsername()).password("").authorities(authorities).build();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities());
        UUID userId = UUID.fromString(authentication.getName());
        boolean secondFactor = signIn.challenge(userId, trustedBrowser.read(request), false).required();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(secondFactor ? new MfaPendingAuthentication(authentication) : authentication);
        SecurityContextHolder.setContext(context);
        request.changeSessionId();
        contexts.saveContext(context, request, response);
        if (secondFactor) {
            return MfaAwareAuthenticationSuccessHandler.MFA_PAGE;
        }
        protection.recordSuccess(new SignInAttempt(userId, email, request.getRemoteAddr(),
                request.getHeader("User-Agent"), null, SignInAttempt.HOSTED, "PASSWORD", null));
        SavedRequest saved = requestCache.getRequest(request, response);
        requestCache.removeRequest(request, response);
        return saved != null ? saved.getRedirectUrl() : homeUrl;
    }
}
