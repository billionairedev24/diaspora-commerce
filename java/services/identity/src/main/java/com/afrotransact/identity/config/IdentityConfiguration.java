package com.afrotransact.identity.config;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.afrotransact.identity.adapter.in.security.TrustedBrowserCookie;
import com.afrotransact.identity.adapter.in.web.LoginPageSettings;
import com.afrotransact.identity.adapter.out.crypto.KeycloakPbkdf2PasswordEncoder;
import com.afrotransact.identity.adapter.out.persistence.RotatingSecretEncoder;
import com.afrotransact.identity.application.AuthSettingsService;
import com.afrotransact.identity.application.FirstPartySignInSettings;
import com.afrotransact.identity.application.InvitationSettings;
import com.afrotransact.identity.application.ProtectedClients;
import com.afrotransact.identity.application.TokenIssuer;
import com.afrotransact.identity.adapter.out.notification.LoggingVerificationCodeSender;
import com.afrotransact.identity.adapter.out.notification.MailpitVerificationCodeSender;
import com.afrotransact.identity.adapter.out.notification.OutboxVerificationCodeSender;
import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.domain.AddressBookPolicy;
import com.afrotransact.identity.domain.AuthSettings;
import com.afrotransact.identity.domain.DefaultAccessPolicy;
import com.afrotransact.identity.domain.DefaultAddressBookPolicy;
import com.afrotransact.identity.domain.DefaultLandingPolicy;
import com.afrotransact.identity.domain.DefaultMfaPolicy;
import com.afrotransact.identity.domain.DefaultPasswordPolicy;
import com.afrotransact.identity.domain.DefaultSignInThrottlePolicy;
import com.afrotransact.identity.domain.DefaultSignUpPolicy;
import com.afrotransact.identity.domain.DefaultTeamPolicy;
import com.afrotransact.identity.domain.DefaultTotpAuthenticator;
import com.afrotransact.identity.domain.DefaultVerificationPolicy;
import com.afrotransact.identity.domain.LandingPolicy;
import com.afrotransact.identity.domain.LibPhoneNumberPolicy;
import com.afrotransact.identity.domain.LocalDevelopmentPolicy;
import com.afrotransact.identity.domain.MfaPolicy;
import com.afrotransact.identity.domain.PasswordPolicy;
import com.afrotransact.identity.domain.PhoneNumberPolicy;
import com.afrotransact.identity.domain.SignInThrottlePolicy;
import com.afrotransact.identity.domain.SignUpPolicy;
import com.afrotransact.identity.domain.TeamPolicy;
import com.afrotransact.identity.domain.TotpAuthenticator;
import com.afrotransact.identity.domain.VerificationPolicy;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.identity.port.VerificationCodeSender;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Explicit wiring of framework-free domain policies and environment-selected adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
class IdentityConfiguration {

    @Bean
    PasswordPolicy passwordPolicy(AuthSettingsService authSettings) {
        return new DefaultPasswordPolicy(() -> authSettings.effective().passwordMinLength());
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

    /**
     * {@code outbox}: the notifications service delivers codes (every environment). Local profile only (start-up fails
     * elsewhere, S276): {@code log} writes them to the log; {@code mailpit} also drops them in the local Mailpit inbox.
     */
    @Bean
    VerificationCodeSender verificationCodeSender(IdentityProperties props, LocalDevelopmentProperties local,
                                                  LocalDevelopmentPolicy policy, Environment env,
                                                  DomainEventPublisher events, UserAccountRepository users,
                                                  Clock clock) {
        String sender = LocalDevelopmentConfiguration.checkedSender(policy, props.verification().sender(),
                LocalDevelopmentConfiguration.activeProfiles(env));
        if ("log".equalsIgnoreCase(sender)) {
            return new LoggingVerificationCodeSender();
        }
        if ("mailpit".equalsIgnoreCase(sender)) {
            return new MailpitVerificationCodeSender(local.mailpitUrl(), users);
        }
        return new OutboxVerificationCodeSender(events, clock);
    }

    /**
     * BCrypt by default; the delegating encoder keeps older hashes verifiable when the default changes. Hashes imported
     * from Keycloak ({@code {kc-pbkdf2-sha256}} and friends) verify but are never produced.
     */
    @Bean
    PasswordEncoder passwordEncoder(Clock clock) {
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        PasswordEncoder bcrypt = new BCryptPasswordEncoder();
        encoders.put("bcrypt", bcrypt);
        for (KeycloakPbkdf2PasswordEncoder.Variant v : KeycloakPbkdf2PasswordEncoder.Variant.values()) {
            encoders.put(v.id(), new KeycloakPbkdf2PasswordEncoder(v));
        }
        // A client secret in rotation (S291) reads as {rotating}: either of its two hashes verifies until the old one
        // expires. The encoder for it is built over the hashes above.
        encoders.put(RotatingSecretEncoder.ID,
                new RotatingSecretEncoder(new DelegatingPasswordEncoder("bcrypt", Map.copyOf(encoders)), clock));
        return new DelegatingPasswordEncoder("bcrypt", encoders);
    }

    /** The configured defaults of the settings the staff console can override. */
    @Bean
    AuthSettings authSettingsDefaults(IdentityProperties props) {
        IdentityProperties.Auth a = props.auth();
        return new AuthSettings(a.passwordMinLength(), a.mfaRequiredAccount(), a.mfaRequiredSeller(),
                a.mfaRequiredProvider(), (int) a.sessionIdleTimeout().toMinutes(),
                (int) a.sessionAbsoluteTimeout().toMinutes(), null, null);
    }

    @Bean
    ProtectedClients protectedClients(IdentityProperties props) {
        return new ProtectedClients(Set.copyOf(props.firstParty().clientIds()));
    }

    @Bean
    TokenIssuer tokenIssuer(IdentityProperties props) {
        return new TokenIssuer(props.issuer());
    }

    @Bean
    LoginPageSettings loginPageSettings(IdentityProperties props) {
        IdentityProperties.Pages p = props.pages();
        return new LoginPageSettings(props.homeUrl(), p.storefrontUrl(), p.sellerCenterUrl(), p.staffConsoleUrl(),
                p.supportUrl(), p.termsUrl(), p.privacyUrl(), p.smsTermsUrl(), p.smsProgram());
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
    TeamPolicy teamPolicy() {
        return new DefaultTeamPolicy();
    }

    @Bean
    InvitationSettings invitationSettings(IdentityProperties props) {
        return new InvitationSettings(props.invitations().acceptUrl());
    }

    @Bean
    FirstPartySignInSettings firstPartySignInSettings(IdentityProperties props) {
        return new FirstPartySignInSettings(Set.copyOf(props.firstParty().clientIds()),
                Set.copyOf(props.firstParty().nativeClientIds()));
    }

    private static DefaultSignInThrottlePolicy.Limits limits(IdentityProperties.Limit l) {
        return new DefaultSignInThrottlePolicy.Limits(l.maxFailures(), l.window(), l.lockout(), l.maxLockout());
    }
}
