package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.application.SellingViews.SellingApprovalView;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.domain.ReasonCode;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.SellingApplication;
import com.afrotransact.seller.domain.SellingApproval;
import com.afrotransact.seller.domain.SellingApprovalStatus;
import com.afrotransact.seller.domain.SellingRule;
import com.afrotransact.seller.domain.SellingScopeType;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.port.ReasonCodeRepository;
import com.afrotransact.seller.port.SellingApprovalRepository;
import com.afrotransact.seller.port.SellingRuleRepository;
import com.afrotransact.seller.port.StaffDirectory;
import com.afrotransact.seller.port.StaffDirectory.StaffPerson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
class SellingApprovalServiceImpl implements SellingApprovalService {

    private static final Logger LOG = LoggerFactory.getLogger(SellingApprovalServiceImpl.class);
    private static final int BATCH = 200;
    private static final int MIN_REASON = 10;

    private final SellingApprovalRepository approvals;
    private final SellingRuleRepository rules;
    private final OrganisationRepository orgs;
    private final ReasonCodeRepository reasons;
    private final StaffDirectory directory;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final Clock clock;

    SellingApprovalServiceImpl(SellingApprovalRepository approvals, SellingRuleRepository rules,
                               OrganisationRepository orgs, ReasonCodeRepository reasons, StaffDirectory directory,
                               AccessGuard access, DomainEventPublisher events, TransactionOperations tx, Clock clock) {
        this.approvals = approvals;
        this.rules = rules;
        this.orgs = orgs;
        this.reasons = reasons;
        this.directory = directory;
        this.access = access;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- application outcomes

    @Override
    public void recordSubmitted(SellingApplication app) {
        Instant now = Instant.now(clock);
        Optional<SellingApproval> existing = approvals.lock(app.orgId(), app.scope());
        if (existing.isEmpty()) {
            SellingRule rule = rules.find(app.scope()).orElseThrow(() -> new NotFoundException(
                    "seller.selling-rule-not-found", "No selling rule for " + app.scopeLabel()));
            approvals.save(SellingApproval.applied(app.orgId(), rule, app.id(), now));
        } else if (!existing.get().valid(now)) {
            approvals.save(existing.get().with(SellingApprovalStatus.APPLIED, null, null, app.id(), now));
        }
        publishApplication(SellerEvents.SELLING_SUBMITTED, app, null, now);
    }

    @Override
    public void recordInfoRequested(SellingApplication app) {
        Instant now = Instant.now(clock);
        approvals.lock(app.orgId(), app.scope()).ifPresent(a -> {
            if (!a.valid(now)) {
                approvals.save(a.with(SellingApprovalStatus.NEEDS_ATTENTION, sellerReason(app), null, app.id(), now));
            }
        });
        publishApplication(SellerEvents.SELLING_INFO_REQUESTED, app, sellerReason(app), now);
    }

    @Override
    public void recordApproved(SellingApplication app) {
        Instant now = Instant.now(clock);
        SellingApproval existing = approvals.lock(app.orgId(), app.scope()).orElseGet(() -> SellingApproval.applied(
                app.orgId(), rules.find(app.scope()).orElseThrow(), app.id(), now));
        boolean wasPaused = existing.listingsPaused() || existing.paused();
        SellingApproval saved = approvals.save(existing.approved(app, now));
        publishApplication(SellerEvents.SELLING_APPROVED, app, null, now);
        if (wasPaused) {
            publishLifecycle(SellerEvents.SELLING_LISTINGS_RESTORED, saved, "RENEWED", null, null, now, true);
        }
    }

    @Override
    public void recordRejected(SellingApplication app) {
        Instant now = Instant.now(clock);
        Optional<SellingApproval> existing = approvals.lock(app.orgId(), app.scope());
        if (existing.isPresent() && !existing.get().valid(now)) {
            SellingApproval a = existing.get();
            SellingApproval next = app.disqualified()
                    ? a.with(SellingApprovalStatus.NOT_AVAILABLE, sellerReason(app), app.reapplyAfter(), app.id(), now)
                    : a.with(SellingApprovalStatus.NEEDS_ATTENTION, sellerReason(app), null, app.id(), now);
            approvals.save(next);
        }
        publishApplication(SellerEvents.SELLING_REJECTED, app, sellerReason(app), now);
    }

    // ---------------------------------------------------------------- staff

    @Override
    @Transactional(readOnly = true)
    public Page<SellingApprovalView> search(String orgId, SellingApprovalStatus status, SellingScopeType type, int page,
                                     int size) {
        staffView();
        Instant now = Instant.now(clock);
        Page<SellingApproval> found = approvals.search(orgId, status, type, Math.max(page, 0),
                Math.min(Math.max(size, 1), 200));
        return new Page<>(found.items().stream().map(a -> SellingApprovalView.of(a, now)).toList(), found.page(),
                found.size(), found.total());
    }

    @Override
    @Transactional(readOnly = true)
    public SellingApprovalView get(UUID id) {
        staffView();
        return SellingApprovalView.of(approvals.findById(id).orElseThrow(() -> notFound(id)), Instant.now(clock));
    }

    @Override
    @Transactional
    public SellingApprovalView pause(UUID id, String reason, String actorName) {
        access.staff(SellerPermissions.APPLICATIONS_APPROVE);
        String why = reason(reason);
        SellingApproval a = approvals.lockById(id).orElseThrow(() -> notFound(id));
        Instant now = Instant.now(clock);
        if (a.status() != SellingApprovalStatus.APPROVED && a.status() != SellingApprovalStatus.EXPIRED) {
            throw new SellerStateConflict("seller.selling-approval-state", "Only an approved scope can be paused");
        }
        if (a.paused()) {
            return SellingApprovalView.of(a, now);
        }
        SellingApproval next = approvals.save(a.withPause(true, why, now).listingsPaused(true, now));
        publishLifecycle(SellerEvents.SELLING_LISTINGS_PAUSED, next, "PERFORMANCE", why, actorName, now, true);
        return SellingApprovalView.of(next, now);
    }

    @Override
    @Transactional
    public SellingApprovalView restore(UUID id, String reason, String actorName) {
        access.staff(SellerPermissions.APPLICATIONS_APPROVE);
        String why = reason(reason);
        SellingApproval a = approvals.lockById(id).orElseThrow(() -> notFound(id));
        Instant now = Instant.now(clock);
        if (!a.paused()) {
            return SellingApprovalView.of(a, now);
        }
        boolean stillExpired = a.status() == SellingApprovalStatus.EXPIRED && a.graceEndsAt() != null
                && !now.isBefore(a.graceEndsAt());
        SellingApproval next = approvals.save(a.withPause(false, null, now).listingsPaused(stillExpired, now));
        if (!stillExpired) {
            publishLifecycle(SellerEvents.SELLING_LISTINGS_RESTORED, next, "STAFF_RESTORED", why, actorName, now,
                    true);
        }
        return SellingApprovalView.of(next, now);
    }

    @Override
    @Transactional
    public SellingApprovalView revoke(UUID id, String reason, LocalDate reapplyAfter, String actorName) {
        access.staff(SellerPermissions.APPLICATIONS_APPROVE);
        String why = reason(reason);
        Instant now = Instant.now(clock);
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        if (reapplyAfter == null || !reapplyAfter.isAfter(today)) {
            throw new SellerRuleViolation("seller.reapply-date-required",
                    "Set the day the seller may apply again (after today)",
                    Map.of("reapplyAfter", "Choose a date after today"));
        }
        SellingApproval a = approvals.lockById(id).orElseThrow(() -> notFound(id));
        SellingApproval next = approvals.save(a.with(SellingApprovalStatus.NOT_AVAILABLE, why, reapplyAfter, null, now)
                .listingsPaused(true, now));
        publishLifecycle(SellerEvents.SELLING_LISTINGS_PAUSED, next, "REVOKED", why, actorName, now, true);
        return SellingApprovalView.of(next, now);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public int runLifecycle(Instant now) {
        Instant horizon = now.plusSeconds(31L * 24 * 3600);
        int changed = 0;
        for (UUID id : approvals.lifecycleCandidates(horizon, BATCH)) {
            try {
                Boolean did = tx.execute(s -> lifecycleOf(id, now));
                if (Boolean.TRUE.equals(did)) {
                    changed++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Lifecycle check of selling approval {} failed; it is retried on the next run", id, e);
            }
        }
        return changed;
    }

    /** One approval under its row lock: a replica that runs at the same time sees the markers this one wrote. */
    private boolean lifecycleOf(UUID id, Instant now) {
        SellingApproval a = approvals.lockById(id).orElse(null);
        if (a == null || a.expiresAt() == null || a.status() != SellingApprovalStatus.APPROVED
                && a.status() != SellingApprovalStatus.EXPIRED) {
            return false;
        }
        boolean changed = false;
        if (a.status() == SellingApprovalStatus.APPROVED && !now.isAfter(a.expiresAt())) {
            Long days = a.daysToExpiry(now);
            Integer threshold = null;
            for (int d : SellingApproval.WARN_DAYS) {
                if (days != null && days <= d) {
                    threshold = d;
                }
            }
            if (threshold != null && warnedAt(a, threshold) == null) {
                SellingApproval next = a;
                for (int d : SellingApproval.WARN_DAYS) {
                    if (d >= threshold && warnedAt(next, d) == null) {
                        next = next.warned(d, now);
                    }
                }
                a = approvals.save(next);
                publishLifecycle(SellerEvents.SELLING_EXPIRY_WARNING, a, "EXPIRING", null, null, now, false,
                        days == null ? null : days.intValue());
                changed = true;
            }
        }
        if (a.status() == SellingApprovalStatus.APPROVED && now.isAfter(a.expiresAt())) {
            a = approvals.save(a.expiredNow(now));
            publishLifecycle(SellerEvents.SELLING_EXPIRED, a, "EXPIRED", null, null, now, true, 0);
            changed = true;
        }
        if (a.status() == SellingApprovalStatus.EXPIRED && !a.listingsPaused() && a.graceEndsAt() != null
                && !now.isBefore(a.graceEndsAt())) {
            a = approvals.save(a.listingsPaused(true, now));
            publishLifecycle(SellerEvents.SELLING_LISTINGS_PAUSED, a, "EXPIRED", null, null, now, true);
            changed = true;
        }
        return changed;
    }

    private static Instant warnedAt(SellingApproval a, int days) {
        return switch (days) {
            case 30 -> a.warned30At();
            case 14 -> a.warned14At();
            default -> a.warned7At();
        };
    }

    // ---------------------------------------------------------------- events

    private void publishApplication(String type, SellingApplication app, String reason, Instant now) {
        events.publish(type, SellerEvents.AGGREGATE_APPLICATION, app.id(), new SellingEventPayload(app.orgId(),
                app.scope().type().name(), app.scope().key(), app.scopeLabel(), app.id(), app.reference(),
                app.status().name(), reason, app.decisionNote(), app.requestedItems(), app.disqualified(),
                app.reapplyAfter(), null, null, List.of(app.applicantUserId()), List.of(), now, List.of()));
    }

    private void publishLifecycle(String type, SellingApproval a, String reason, String note, String actorName,
                                  Instant now, boolean tellStaff) {
        publishLifecycle(type, a, reason, note, actorName, now, tellStaff, null);
    }

    private void publishLifecycle(String type, SellingApproval a, String reason, String note, String actorName,
                                  Instant now, boolean tellStaff, Integer daysLeft) {
        List<String> owners = orgs.members(a.orgId()).stream().filter(OrgMember::isOwner).map(OrgMember::userId)
                .toList();
        List<String> staff = tellStaff ? safe(() -> directory.withPermission(SellerPermissions.APPLICATIONS_ASSIGN))
                .orElse(List.of()).stream().map(StaffPerson::id).toList() : List.of();
        events.publish(type, SellerEvents.AGGREGATE_COMPLIANCE, a.orgId(), new SellingEventPayload(a.orgId(),
                a.scope().type().name(), a.scope().key(), a.label(), a.applicationId(), null, a.status().name(),
                reason, note == null ? actorName : note, List.of(), false, a.reapplyAfter(), a.expiresAt(), daysLeft,
                owners, staff, now, categoriesOf(a)));
    }

    // ---------------------------------------------------------------- helpers

    private List<String> categoriesOf(SellingApproval a) {
        return a.scope().type() == SellingScopeType.CAPABILITY ? rules.categoriesWithCapability(a.scope().key())
                : List.of();
    }

    /** What the seller is told: the staff message, else the reason code's label. */
    private String sellerReason(SellingApplication app) {
        if (app.decisionNote() != null && !app.decisionNote().isBlank()) {
            return app.decisionNote().strip();
        }
        if (!app.requestedItems().isEmpty() && app.status() == ApplicationStatus.INFO_REQUESTED) {
            return String.join(", ", app.requestedItems());
        }
        return app.reasonCode() == null ? null : reasons.find(app.reasonCode()).map(ReasonCode::label)
                .orElse(app.reasonCode());
    }

    private void staffView() {
        access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE,
                SellerPermissions.APPLICATIONS_ASSIGN);
    }

    private static String reason(String reason) {
        if (reason == null || reason.strip().length() < MIN_REASON) {
            throw new SellerRuleViolation("seller.reason-required",
                    "Say why (at least " + MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        return reason.strip();
    }

    private <T> Optional<T> safe(Supplier<T> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (RuntimeException e) {
            LOG.warn("Staff directory unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("seller.selling-approval-not-found", "Selling approval " + id + " not found");
    }
}
