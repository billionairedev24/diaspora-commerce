package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ComplianceHistoryEntry;
import com.afrotransact.seller.domain.ComplianceStatus;
import com.afrotransact.seller.domain.ComplianceUpdate;
import com.afrotransact.seller.domain.ConnectAccount;
import com.afrotransact.seller.domain.Enforcement;
import com.afrotransact.seller.domain.PayoutHold;
import com.afrotransact.seller.domain.PayoutSection;
import com.afrotransact.seller.domain.RequirementsSnapshot;
import com.afrotransact.seller.domain.StripeVerification;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.ConnectAccountRepository;
import com.afrotransact.seller.port.OrgSuspensionRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies Stripe account data to our mirror inside the caller's transaction: stores the snapshot and history,
 * refreshes the onboarding application's verification rows and publishes compliance / enforcement events. Releases
 * (payouts resumed, listings restored) are held back while the organisation is suspended by Trust and Safety.
 */
@Component
class ComplianceSync {

    private static final Logger LOG = LoggerFactory.getLogger(ComplianceSync.class);

    private final ConnectAccountRepository accounts;
    private final ApplicationRepository apps;
    private final OrgSuspensionRepository suspensions;
    private final DomainEventPublisher events;
    private final Clock clock;

    ComplianceSync(ConnectAccountRepository accounts, ApplicationRepository apps, OrgSuspensionRepository suspensions,
                   DomainEventPublisher events, Clock clock) {
        this.accounts = accounts;
        this.apps = apps;
        this.suspensions = suspensions;
        this.events = events;
        this.clock = clock;
    }

    /** Applies a snapshot to a locked account; the caller has already checked the version ordering. */
    @Transactional(propagation = Propagation.MANDATORY)
    ConnectAccount apply(ConnectAccount locked, RequirementsSnapshot snapshot, long version, String source,
                         String eventId, String eventType) {
        Instant now = Instant.now(clock);
        ComplianceUpdate update = locked.apply(snapshot, version, now);
        return record(locked, update, source, eventId, eventType, "account.updated");
    }

    /** Stores an update, writes history, refreshes the application and publishes events. */
    @Transactional(propagation = Propagation.MANDATORY)
    ConnectAccount record(ConnectAccount before, ComplianceUpdate update, String source, String eventId,
                          String eventType, String trigger) {
        Instant now = Instant.now(clock);
        ConnectAccount saved = accounts.update(update.account());
        accounts.appendHistory(new ComplianceHistoryEntry(UUID.randomUUID(), saved.accountId(), source, eventId,
                eventType, saved.status(), summary(update), saved.requirements(), null, now));
        refreshApplication(saved, now);
        if (saved.orgId() == null) {
            return saved;
        }
        if (update.requirementsChanged() || update.statusChanged()) {
            events.publish(SellerEvents.COMPLIANCE_CHANGED, SellerEvents.AGGREGATE_COMPLIANCE, saved.orgId(),
                    ComplianceEventPayload.of(saved, before.status().name(), update.newItems(), trigger, now));
        }
        publishEnforcement(saved, update.payouts(), null, null, now);
        publishEnforcement(saved, update.listings(), null, null, now);
        return saved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void publishEnforcement(ConnectAccount a, Enforcement e, String actorId, String note, Instant now) {
        if (e == Enforcement.NONE || a.orgId() == null) {
            return;
        }
        if ((e == Enforcement.RESUME_PAYOUTS || e == Enforcement.RESTORE_LISTINGS)
                && suspensions.findActive(a.orgId()).isPresent()) {
            // A Trust & Safety suspension still holds payouts and hides listings; reinstatement releases them.
            LOG.info("{} for {} not published: the organisation is suspended", e, a.orgId());
            return;
        }
        List<String> pastDue = a.requirements().pastDue();
        switch (e) {
            case PAUSE_PAYOUTS -> events.publish(SellerEvents.PAYOUTS_PAUSED, SellerEvents.AGGREGATE_COMPLIANCE,
                    a.orgId(), new EnforcementPayload(a.orgId(), a.accountId(),
                            a.holdReason() == null ? PayoutHold.MANUAL.name() : a.holdReason().name(), actorId, note,
                            pastDue, now));
            case RESUME_PAYOUTS -> events.publish(SellerEvents.PAYOUTS_RESUMED, SellerEvents.AGGREGATE_COMPLIANCE,
                    a.orgId(), new EnforcementPayload(a.orgId(), a.accountId(),
                            actorId == null ? "CLEARED" : PayoutHold.MANUAL.name(), actorId, note, pastDue, now));
            case HIDE_LISTINGS -> events.publish(SellerEvents.LISTINGS_HIDDEN, SellerEvents.AGGREGATE_COMPLIANCE,
                    a.orgId(), new EnforcementPayload(a.orgId(), a.accountId(),
                            a.deauthorized() ? PayoutHold.DEAUTHORIZED.name() : "CHARGES_DISABLED", actorId, note,
                            pastDue, now));
            case RESTORE_LISTINGS -> events.publish(SellerEvents.LISTINGS_RESTORED,
                    SellerEvents.AGGREGATE_COMPLIANCE, a.orgId(),
                    new EnforcementPayload(a.orgId(), a.accountId(), "CLEARED", actorId, note, pastDue, now));
            default -> {
            }
        }
    }

    /**
     * Mirrors the account onto the organisation's application while it is open: Stripe's verification results and the
     * payout bank account Stripe holds (the wizard's Payouts step is read-only; Stripe collects the bank).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void refreshApplication(ConnectAccount a, Instant now) {
        if (a.orgId() == null) {
            return;
        }
        apps.findByOrg(a.orgId()).filter(app -> !app.isApproved()).ifPresent(app -> {
            Application locked = apps.lockById(app.id()).orElse(app);
            apps.update(locked.withStripeAccount(StripeVerification.from(a.accountId(), a.requirements(), now),
                    PayoutSection.fromStripe(a.requirements().payoutBank()), now));
        });
    }

    private static String summary(ComplianceUpdate u) {
        StringBuilder sb = new StringBuilder();
        if (!u.newItems().isEmpty()) {
            sb.append("New requirements: ").append(String.join(", ", u.newItems())).append(". ");
        }
        if (u.account().status() == ComplianceStatus.COMPLIANT && u.requirementsChanged()) {
            sb.append("Requirements satisfied. ");
        }
        if (u.payouts() != Enforcement.NONE) {
            sb.append(u.payouts().name().toLowerCase(Locale.ROOT).replace('_', ' ')).append(". ");
        }
        if (u.listings() != Enforcement.NONE) {
            sb.append(u.listings().name().toLowerCase(Locale.ROOT).replace('_', ' ')).append(". ");
        }
        return sb.isEmpty() ? "No change" : sb.toString().trim();
    }
}
