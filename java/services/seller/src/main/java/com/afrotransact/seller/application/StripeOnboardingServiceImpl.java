package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.BusinessSection;
import com.afrotransact.seller.domain.ConnectAccount;
import com.afrotransact.seller.domain.EntityType;
import com.afrotransact.seller.domain.RequirementsSnapshot;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.StripeVerification;
import com.afrotransact.seller.port.AccountSession;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.ConnectAccountRepository;
import com.afrotransact.seller.port.CreateConnectedAccount;
import com.afrotransact.seller.port.StripeConnectGateway;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StripeOnboardingServiceImpl implements StripeOnboardingService {

    private final ApplicationRepository apps;
    private final ConnectAccountRepository accounts;
    private final StripeConnectGateway stripe;
    private final ComplianceSync sync;
    private final ApplicationViews views;
    private final AccessGuard access;
    private final SellerSettings settings;
    private final Clock clock;

    StripeOnboardingServiceImpl(ApplicationRepository apps, ConnectAccountRepository accounts,
                                StripeConnectGateway stripe, ComplianceSync sync, ApplicationViews views,
                                AccessGuard access, SellerSettings settings, Clock clock) {
        this.apps = apps;
        this.accounts = accounts;
        this.stripe = stripe;
        this.sync = sync;
        this.views = views;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AccountSession accountSession() {
        Actor actor = access.applicant();
        Application app = apps.lockByApplicant(actor.subject()).orElseThrow(StripeOnboardingServiceImpl::none);
        if (app.status() == ApplicationStatus.REJECTED) {
            throw new SellerStateConflict("seller.application-rejected", "This application was rejected");
        }
        String accountId = app.data().stripe() == null ? null : app.data().stripe().accountId();
        if (accountId == null) {
            BusinessSection b = app.data().business();
            if (b == null || b.entityType() == null || b.country() == null) {
                throw new SellerRuleViolation("seller.business-step-required",
                        "Tell us your business type and country before starting Stripe verification",
                        Map.of("business.entityType", "Required", "business.country", "Required"));
            }
            Instant now = Instant.now(clock);
            // The key names the account settings (v2: Stripe-liable): Stripe refuses a key reused with other parameters.
            accountId = stripe.createAccount(new CreateConnectedAccount(app.orgId(), app.id(), b.country(),
                    b.contactEmail(), businessType(b.entityType()), "connect-account-v2-" + app.orgId()));
            apps.update(app.withStripe(StripeVerification.created(accountId, now), now));
            accounts.insert(ConnectAccount.open(accountId, app.orgId(), now));
        }
        return stripe.createAccountSession(accountId, new LinkedHashSet<>(settings.sessionComponents()));
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSession accountSession(String orgId) {
        access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, null);
        ConnectAccount a = accounts.findByOrg(orgId).orElseThrow(() -> new NotFoundException(
                "seller.no-connected-account", "Organisation " + orgId + " has no Stripe account yet"));
        if (a.deauthorized()) {
            throw new SellerStateConflict("seller.account-deauthorized",
                    "This Stripe account is no longer connected to AfroTransact");
        }
        return stripe.createAccountSession(a.accountId(), new LinkedHashSet<>(settings.sessionComponents()));
    }

    @Override
    @Transactional
    public ApplicationView refreshVerification() {
        Actor actor = access.applicant();
        Application app = apps.findByApplicant(actor.subject()).orElseThrow(StripeOnboardingServiceImpl::none);
        if (app.data().stripe() == null || app.data().stripe().accountId() == null) {
            throw new SellerStateConflict("seller.no-connected-account", "Start Stripe verification first");
        }
        String accountId = app.data().stripe().accountId();
        RequirementsSnapshot snapshot = stripe.retrieveAccount(accountId);
        Instant now = Instant.now(clock);
        ConnectAccount locked = accounts.lock(accountId).orElseGet(() -> {
            accounts.insert(ConnectAccount.open(accountId, app.orgId(), now));
            return accounts.lock(accountId).orElseThrow();
        });
        sync.apply(locked, snapshot, now.getEpochSecond(), "STRIPE_API", null, "account.retrieve");
        Application fresh = apps.findById(app.id()).orElseThrow();
        return views.view(fresh, PiiLevel.APPLICANT, apps.timeline(app.id()), List.of());
    }

    /** Stripe business_type for the legal form. */
    static String businessType(EntityType et) {
        return switch (et) {
            case SOLE_PROPRIETOR -> "individual";
            case NONPROFIT -> "non_profit";
            default -> "company";
        };
    }

    private static NotFoundException none() {
        return new NotFoundException("seller.no-application", "You haven't started an application yet");
    }
}
