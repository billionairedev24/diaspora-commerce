package com.afrotransact.identity.adapter.in.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.afrotransact.identity.application.FirstPartySignInService;
import com.afrotransact.identity.application.RedeemedLoginTicket;
import com.afrotransact.identity.domain.SignInMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Burns a first-party login ticket and authenticates its account for this one authorization request. The result is
 * the same kind of authentication the hosted password form produces (principal name = account id), so the
 * authorization server stores and serialises it as usual and the token customizer adds the normal claims.
 */
public class LoginTicketAuthenticationProvider implements AuthenticationProvider {

    private final FirstPartySignInService signIn;
    private final UserDetailsService accounts;

    public LoginTicketAuthenticationProvider(FirstPartySignInService signIn, UserDetailsService accounts) {
        this.signIn = signIn;
        this.accounts = accounts;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        LoginTicketAuthenticationToken request = (LoginTicketAuthenticationToken) authentication;
        Optional<RedeemedLoginTicket> redeemed = signIn.redeemLoginTicket(request.ticket(), request.clientId());
        if (redeemed.isEmpty()) {
            throw new BadCredentialsException("The login ticket is invalid, expired, already used or for another client");
        }
        UserDetails account;
        try {
            account = accounts.loadUserByUsername(redeemed.get().userId().toString());
        } catch (UsernameNotFoundException gone) {
            throw new BadCredentialsException("The account no longer exists", gone);
        }
        if (!account.isEnabled()) {
            throw new DisabledException("The account is disabled or its email is not confirmed");
        }
        // Factor authorities carry the sign-in time (ID token auth_time), like the hosted form and passkey logins.
        List<GrantedAuthority> authorities = new ArrayList<>(account.getAuthorities());
        String factor = redeemed.get().method() == SignInMethod.PASSKEY ? FactorGrantedAuthority.WEBAUTHN_AUTHORITY
                : FactorGrantedAuthority.PASSWORD_AUTHORITY;
        authorities.add(FactorGrantedAuthority.withAuthority(factor).issuedAt(redeemed.get().authenticatedAt())
                .build());
        UserDetails principal = User.withUsername(account.getUsername()).password("").authorities(authorities)
                .build();
        UsernamePasswordAuthenticationToken result = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities());
        result.eraseCredentials();
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return LoginTicketAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
