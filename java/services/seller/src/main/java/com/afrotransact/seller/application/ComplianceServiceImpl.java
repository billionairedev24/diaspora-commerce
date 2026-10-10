package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.ComplianceHistoryEntry;
import com.afrotransact.seller.domain.ComplianceStatus;
import com.afrotransact.seller.domain.ComplianceUpdate;
import com.afrotransact.seller.domain.ConnectAccount;
import com.afrotransact.seller.domain.Enforcement;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.ReminderSchedule;
import com.afrotransact.seller.domain.RequirementAnswer;
import com.afrotransact.seller.domain.RequirementCatalog;
import com.afrotransact.seller.domain.RequirementError;
import com.afrotransact.seller.domain.RequirementsSnapshot;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.Validated;
import com.afrotransact.seller.port.ConnectAccountRepository;
import com.afrotransact.seller.port.MediaClient;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.RequirementSubmission;
import com.afrotransact.seller.port.StripeConnectGateway;
import com.afrotransact.seller.port.StripeEvent;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
class ComplianceServiceImpl implements ComplianceService {

    private static final Logger LOG = LoggerFactory.getLogger(ComplianceServiceImpl.class);
    private static final int HISTORY = 50;

    private final ConnectAccountRepository accounts;
    private final OrganisationRepository orgs;
    private final StripeConnectGateway stripe;
    private final MediaClient media;
    private final ComplianceSync sync;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final Clock clock;

    ComplianceServiceImpl(ConnectAccountRepository accounts, OrganisationRepository orgs,
                          StripeConnectGateway stripe, MediaClient media, ComplianceSync sync, AccessGuard access,
                          DomainEventPublisher events, TransactionOperations tx, Clock clock) {
        this.accounts = accounts;
        this.orgs = orgs;
        this.stripe = stripe;
        this.media = media;
        this.sync = sync;
        this.access = access;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String handle(StripeEvent e) {
        if (e.accountId() == null) {
            return "ignored-no-account";
        }
        Optional<ConnectAccount> found = accounts.lock(e.accountId());
        if (found.isEmpty()) {
            return "ignored-unknown-account";
        }
        ConnectAccount a = found.get();
        Instant now = Instant.now(clock);
        switch (e.type()) {
            case "account.updated" -> {
                if (e.created() < a.requirementsVersion()) {
                    history(a, "STRIPE_WEBHOOK", e, "Stale snapshot ignored (older than version "
                            + a.requirementsVersion() + ")", null);
                    return "stale";
                }
                sync.apply(a, e.account(), e.created(), "STRIPE_WEBHOOK", e.id(), e.type());
            }
            case "capability.updated" -> {
                ConnectAccount saved = accounts.update(a.withCapability(e.capability().id(), e.capability().status(),
                        now));
                history(saved, "STRIPE_WEBHOOK", e, "Capability " + e.capability().id() + " is "
                        + e.capability().status(), null);
            }
            case "person.created", "person.updated" -> {
                ConnectAccount saved = accounts.update(a.withPerson(e.person().id(),
                        e.person().verificationStatus(), now));
                history(saved, "STRIPE_WEBHOOK", e, "Person " + e.person().id() + " verification "
                        + e.person().verificationStatus(), null);
            }
            case "account.external_account.updated" -> {
                ConnectAccount saved = accounts.update(a.withExternalAccountStatus(e.externalAccount().status(),
                        now));
                history(saved, "STRIPE_WEBHOOK", e, "Bank " + e.externalAccount().bankName() + " ••"
                        + e.externalAccount().last4() + " is " + e.externalAccount().status(), null);
                String status = e.externalAccount().status();
                if ("errored".equals(status) || "verification_failed".equals(status)) {
                    changed(saved, "external_account." + status, now);
                }
            }
            case "payout.failed" -> {
                history(a, "STRIPE_WEBHOOK", e, "Payout " + e.payout().id() + " failed: "
                        + e.payout().failureCode(), null);
                changed(a, "payout.failed", now);
            }
            case "account.application.deauthorized" -> {
                ComplianceUpdate update = a.deauthorize(now);
                sync.record(a, update, "STRIPE_WEBHOOK", e.id(), e.type(), "deauthorized");
            }
            default -> {
                return "ignored-type";
            }
        }
        return "applied";
    }

    @Override
    @Transactional(readOnly = true)
    public AccountHealthView accountHealth(String orgId) {
        access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, null);
        ConnectAccount a = accounts.findByOrg(orgId).orElseThrow(() -> noAccount(orgId));
        return view(a, false);
    }

    @Override
    @Transactional
    public AccountHealthView answer(String orgId, String requirement, Map<String, String> values, String ip,
                                    String userAgent) {
        Actor actor = access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, null);
        ConnectAccount found = accounts.findByOrg(orgId).orElseThrow(() -> noAccount(orgId));
        ConnectAccount a = accounts.lock(found.accountId()).orElseThrow();
        RequirementsSnapshot r = a.requirements();
        if (!r.open().contains(requirement) && !r.eventuallyDue().contains(requirement)) {
            throw new NotFoundException("seller.requirement-not-open",
                    "Stripe isn't asking for " + requirement + " right now");
        }
        Validated<RequirementAnswer> v = RequirementCatalog.answer(requirement, values == null ? Map.of() : values,
                LocalDate.now(clock));
        if (!v.ok()) {
            throw new SellerRuleViolation("seller.invalid-answer", "Check the highlighted fields", v.errors());
        }
        RequirementAnswer answer = v.value();
        String front = answer.frontAssetId() == null ? null
                : stripe.uploadRequirementFile(a.accountId(), media.fetch(answer.frontAssetId()));
        String back = answer.backAssetId() == null ? null
                : stripe.uploadRequirementFile(a.accountId(), media.fetch(answer.backAssetId()));
        Instant now = Instant.now(clock);
        stripe.submitRequirement(a.accountId(), new RequirementSubmission(answer, front, back, ip, userAgent, now));
        ConnectAccount saved = accounts.update(a.answered(requirement, now));
        accounts.appendHistory(new ComplianceHistoryEntry(UUID.randomUUID(), saved.accountId(), "SELLER", null,
                "requirement.answered", saved.status(), "Answered " + requirement + " — verifying with Stripe",
                saved.requirements(), actor.subject(), now));
        events.publish(SellerEvents.COMPLIANCE_CHANGED, SellerEvents.AGGREGATE_COMPLIANCE, orgId,
                ComplianceEventPayload.of(saved, a.status().name(), List.of(), "answer", now));
        return view(saved, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountHealthView> accounts(ComplianceStatus status, boolean openOnly) {
        access.staffAny(SellerPermissions.COMPLIANCE_REMIND, SellerPermissions.COMPLIANCE_HOLD);
        return accounts.search(status, openOnly, 500).stream().map(a -> new AccountHealthView(a, orgName(a),
                List.of(), List.of())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AccountHealthView account(String accountId) {
        access.staffAny(SellerPermissions.COMPLIANCE_REMIND, SellerPermissions.COMPLIANCE_HOLD);
        return view(find(accountId), true);
    }

    @Override
    @Transactional
    public AccountHealthView remindNow(String accountId) {
        Actor actor = access.staff(SellerPermissions.COMPLIANCE_REMIND);
        ConnectAccount a = accounts.lock(accountId).orElseThrow(() -> unknown(accountId));
        if (!a.hasOpenItems()) {
            throw new SellerStateConflict("seller.nothing-to-remind", "This account has no open requirements");
        }
        Instant now = Instant.now(clock);
        accounts.recordManualReminder(accountId, actor.subject(), now);
        ConnectAccount saved = remind(a, null, actor.subject(), now);
        return view(saved, true);
    }

    @Override
    @Transactional
    public AccountHealthView pausePayouts(String accountId, String note) {
        Actor actor = access.staffAny(SellerPermissions.COMPLIANCE_REMIND, SellerPermissions.COMPLIANCE_HOLD);
        ConnectAccount a = accounts.lock(accountId).orElseThrow(() -> unknown(accountId));
        Instant now = Instant.now(clock);
        ConnectAccount next = a.pauseManually(now);
        if (next == a) {
            return view(a, true);
        }
        ConnectAccount saved = accounts.update(next);
        accounts.appendHistory(new ComplianceHistoryEntry(UUID.randomUUID(), accountId, "STAFF", null,
                "payouts.paused", saved.status(), "Payouts paused by staff" + note(note), saved.requirements(),
                actor.subject(), now));
        sync.publishEnforcement(saved, Enforcement.PAUSE_PAYOUTS, actor.subject(), note, now);
        return view(saved, true);
    }

    @Override
    @Transactional
    public AccountHealthView resumePayouts(String accountId, String note) {
        Actor actor = access.staff(SellerPermissions.COMPLIANCE_HOLD);
        ConnectAccount a = accounts.lock(accountId).orElseThrow(() -> unknown(accountId));
        Instant now = Instant.now(clock);
        ConnectAccount next = a.resumeManually(now);
        if (next == a) {
            return view(a, true);
        }
        ConnectAccount saved = accounts.update(next);
        accounts.appendHistory(new ComplianceHistoryEntry(UUID.randomUUID(), accountId, "STAFF", null,
                "payouts.resumed", saved.status(), "Payouts released by staff" + note(note), saved.requirements(),
                actor.subject(), now));
        sync.publishEnforcement(saved, Enforcement.RESUME_PAYOUTS, actor.subject(), note, now);
        return view(saved, true);
    }

    @Override
    public int sendDueReminders() {
        Instant now = Instant.now(clock);
        int sent = 0;
        List<ConnectAccount> candidates = tx.execute(s -> accounts.withDeadlineBefore(
                now.plus(Duration.ofDays(ReminderSchedule.THRESHOLD_DAYS.getLast())), 500));
        for (ConnectAccount c : candidates == null ? List.<ConnectAccount>of() : candidates) {
            Optional<Integer> threshold = ReminderSchedule.dueThreshold(c.requirements().currentDeadline(), now);
            if (threshold.isEmpty()) {
                continue;
            }
            try {
                Boolean done = tx.execute(s -> {
                    ConnectAccount a = accounts.lock(c.accountId()).orElseThrow();
                    if (!a.hasOpenItems() || a.requirements().currentDeadline() == null
                            || !accounts.claimReminder(a.accountId(), a.requirements().currentDeadline(),
                            threshold.get(), now)) {
                        return false;
                    }
                    remind(a, threshold.get(), null, now);
                    return true;
                });
                sent += Boolean.TRUE.equals(done) ? 1 : 0;
            } catch (RuntimeException ex) {
                LOG.warn("Reminder for {} failed; the job will try again", c.accountId(), ex);
            }
        }
        return sent;
    }

    private ConnectAccount remind(ConnectAccount a, Integer threshold, String actorId, Instant now) {
        ConnectAccount saved = accounts.update(a.reminded(now));
        String summary = threshold == null ? "Reminder sent by staff"
                : "Reminder sent " + threshold + " day(s) before the deadline";
        accounts.appendHistory(new ComplianceHistoryEntry(UUID.randomUUID(), a.accountId(),
                threshold == null ? "STAFF" : "SCHEDULER", null, "reminder.sent", saved.status(), summary,
                saved.requirements(), actorId, now));
        if (saved.orgId() != null) {
            events.publish(SellerEvents.COMPLIANCE_REMINDER, SellerEvents.AGGREGATE_COMPLIANCE, saved.orgId(),
                    new ComplianceReminderPayload(saved.orgId(), saved.accountId(), threshold, threshold == null,
                            actorId, new ArrayList<>(saved.requirements().open()),
                            saved.requirements().currentDeadline(), ComplianceEventPayload.ALL_CHANNELS, now));
        }
        return saved;
    }

    private void changed(ConnectAccount a, String trigger, Instant now) {
        if (a.orgId() != null) {
            events.publish(SellerEvents.COMPLIANCE_CHANGED, SellerEvents.AGGREGATE_COMPLIANCE, a.orgId(),
                    ComplianceEventPayload.of(a, a.status().name(), List.of(), trigger, now));
        }
    }

    private void history(ConnectAccount a, String source, StripeEvent e, String summary, String actor) {
        accounts.appendHistory(new ComplianceHistoryEntry(UUID.randomUUID(), a.accountId(), source, e.id(), e.type(),
                a.status(), summary, a.requirements(), actor, Instant.now(clock)));
    }

    private AccountHealthView view(ConnectAccount a, boolean withHistory) {
        RequirementsSnapshot r = a.requirements();
        List<RequirementItem> items = new ArrayList<>();
        addItems(items, r.pastDue(), "PAST_DUE", r);
        addItems(items, r.currentlyDue().stream().filter(k -> !r.pastDue().contains(k)).toList(), "CURRENTLY_DUE", r);
        addItems(items, r.eventuallyDue().stream().filter(k -> !r.open().contains(k)).toList(), "EVENTUALLY_DUE", r);
        addItems(items, r.pendingVerification(), "PENDING_VERIFICATION", r);
        return new AccountHealthView(a, orgName(a), items, withHistory ? accounts.history(a.accountId(), HISTORY)
                : accounts.history(a.accountId(), 10));
    }

    private static void addItems(List<RequirementItem> items, List<String> keys, String bucket,
                                 RequirementsSnapshot r) {
        for (String k : keys) {
            List<RequirementError> errors = r.errors().stream().filter(e -> k.equals(e.requirement())).toList();
            items.add(new RequirementItem(k, bucket, RequirementCatalog.form(k), errors));
        }
    }

    private String orgName(ConnectAccount a) {
        return a.orgId() == null ? null : orgs.findById(a.orgId()).map(Organisation::displayName).orElse(null);
    }

    private ConnectAccount find(String accountId) {
        return accounts.find(accountId).orElseThrow(() -> unknown(accountId));
    }

    private static String note(String note) {
        return note == null || note.isBlank() ? "" : ": " + note.trim();
    }

    private static NotFoundException unknown(String accountId) {
        return new NotFoundException("seller.connected-account-not-found", "Connected account " + accountId
                + " not found");
    }

    private static NotFoundException noAccount(String orgId) {
        return new NotFoundException("seller.no-connected-account",
                "Organisation " + orgId + " has no Stripe connected account yet");
    }
}
