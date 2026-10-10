package com.afrotransact.identity.adapter.in.security;

import java.util.List;

import com.afrotransact.identity.application.SignInAccount;
import com.afrotransact.identity.application.SignInService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Loads accounts for form login (by email) and passkey login (by account id). The resulting principal name is the
 * account id, which becomes the token {@code sub} for every sign-in method. Unverified accounts are disabled until
 * the email code is confirmed.
 */
public class AccountUserDetailsService implements UserDetailsService {

    /** Never matches: accounts without a password (social or passkey only) cannot use the password form. */
    private static final String NO_PASSWORD = "{bcrypt}!";

    private final SignInService signIn;

    public AccountUserDetailsService(SignInService signIn) {
        this.signIn = signIn;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        SignInAccount a = signIn.findAccount(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
        return User.withUsername(a.id().toString())
                .password(a.passwordHash() == null ? NO_PASSWORD : a.passwordHash())
                .disabled(!a.enabled() || !a.emailVerified())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
    }
}
