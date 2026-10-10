package com.afrotransact.identity.config;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

import com.afrotransact.identity.adapter.in.security.TrustedBrowserCookie;
import com.afrotransact.identity.adapter.in.web.LoginPageSettings;
import com.afrotransact.identity.application.FirstPartySignInSettings;
import com.afrotransact.identity.adapter.out.notification.LoggingVerificationCodeSender;
import com.afrotransact.identity.adapter.out.notification.OutboxVerificationCodeSender;
import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.domain.AddressBookPolicy;
import com.afrotransact.identity.domain.DefaultAccessPolicy;
import com.afrotransact.identity.domain.DefaultAddressBookPolicy;
import com.afrotransact.identity.domain.DefaultLandingPolicy;
import com.afrotransact.identity.domain.DefaultMfaPolicy;
import com.afrotransact.identity.domain.DefaultPasswordPolicy;
import com.afrotransact.identity.domain.DefaultSignInThrottlePolicy;
import com.afrotransact.identity.domain.DefaultSignUpPolicy;
import com.afrotransact.identity.domain.DefaultTotpAuthenticator;
import com.afrotransact.identity.domain.DefaultVerificationPolicy;
import com.afrotransact.identity.domain.LandingPolicy;
import com.afrotransact.identity.domain.LibPhoneNumberPolicy;
import com.afrotransact.identity.domain.MfaPolicy;
import com.afrotransact.identity.domain.PasswordPolicy;
import com.afrotransact.identity.domain.PhoneNumberPolicy;
import com.afrotransact.identity.domain.SignInThrottlePolicy;
import com.afrotransact.identity.domain.SignUpPolicy;
import com.afrotransact.identity.domain.TotpAuthenticator;
import com.afrotransact.identity.domain.VerificationPolicy;
import com.afrotransact.identity.port.VerificationCodeSender;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Explicit wiring of framework-free domain policies and environment-selected adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
class IdentityConfiguration {

    @Bean
    PasswordPolicy passwordPolicy() {
        return new DefaultPasswordPolicy();
    }

    @Bean
    AddressBookPolicy addressBookPolicy() {
        return new DefaultAddressBookPolicy();
    }

    @Bean
    PhoneNumberPolicy phoneNumberPolicy() {
        return new LibPhoneNumberPolicy();
    }

    @Bean
    SignUpPolicy signUpPolicy(PasswordPolicy passwords, PhoneNumberPolicy phones) {
        return new DefaultSignUpPolicy(passwords, phones);
    }

    @Bean
    AccessPolicy accessPolicy() {
        return new DefaultAccessPolicy();
    }

    @Bean
    LandingPolicy landingPolicy(IdentityProperties props) {
        return new DefaultLandingPolicy(new HashSet<>(props.staffEmailDomains()));
    }

    @Bean
    VerificationPolicy verificationPolicy() {
        return new DefaultVerificationPolicy();
    }

    @Bean
    MfaPolicy mfaPolicy() {
        return new DefaultMfaPolicy();
    }

    @Bean
    TotpAuthenticator totpAuthenticator() {
        return new DefaultTotpAuthenticator();
    }

    /** {@code outbox}: notifications service delivers codes; {@code log}: local development only. */
    @Bean
    VerificationCodeSender verificationCodeSender(IdentityProperties props, DomainEventPublisher events,
                                                  Clock clock) {
        return "log".equalsIgnoreCase(props.verification().sender()) ? new LoggingVerificationCodeSender()
                : new OutboxVerificationCodeSender(events, clock);
    }

    /** BCrypt by default; the delegating encoder keeps older hashes verifiable when the default changes. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    LoginPageSettings loginPageSettings(IdentityProperties props) {
        return new LoginPageSettings(props.homeUrl());
    }

    @Bean
    TrustedBrowserCookie trustedBrowserCookie(IdentityProperties props) {
        return new TrustedBrowserCookie(props.mfa().secureCookies());
    }

    @Bean
    SignInThrottlePolicy signInThrottlePolicy(IdentityProperties props) {
        return new DefaultSignInThrottlePolicy(limits(props.signInProtection().account()),
                limits(props.signInProtection().ip()));
    }

    @Bean
    FirstPartySignInSettings firstPartySignInSettings(IdentityProperties props) {
        return new FirstPartySignInSettings(Set.copyOf(props.firstParty().clientIds()));
    }

    private static DefaultSignInThrottlePolicy.Limits limits(IdentityProperties.Limit l) {
        return new DefaultSignInThrottlePolicy.Limits(l.maxFailures(), l.window(), l.lockout(), l.maxLockout());
    }
}
