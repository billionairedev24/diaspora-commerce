package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.PlanFeeCollection;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.EarningsCollectionRepository;
import com.afrotransact.seller.port.EarningsWithholdingGateway;
import com.afrotransact.seller.port.PlanFeeCollectionRepository;
import com.afrotransact.seller.port.SubscriptionRepository;
import com.afrotransact.seller.port.SubscriptionTerms;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class PlanFeeCollectionServiceImpl implements PlanFeeCollectionService {

    static final String SOURCE_DEFAULT = "DEFAULT";
    static final String SOURCE_ONBOARDING = "ONBOARDING";
    static final String SOURCE_SELLER = "SELLER";
    static final String SOURCE_STAFF = "STAFF";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final int HISTORY = 20;

    private final SubscriptionRepository subs;
    private final PlanFeeCollectionRepository repo;
    private final EarningsCollectionRepository collections;
    private final BillingGrantRepository grants;
    private final EarningsWithholdingGateway payments;
    private final AccessGuard access;
    private final SellerSettings settings;
    private final TransactionOperations tx;
    private final Clock clock;

    PlanFeeCollectionServiceImpl(SubscriptionRepository subs, PlanFeeCollectionRepository repo,
                                 EarningsCollectionRepository collections, BillingGrantRepository grants,
                                 EarningsWithholdingGateway payments, AccessGuard access, SellerSettings settings,
                                 TransactionOperations tx, Clock clock) {
        this.subs = subs;
        this.repo = repo;
        this.collections = collections;
        this.grants = grants;
        this.payments = payments;
        this.access = access;
        this.settings = settings;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Options options() {
        return new Options(payments.enabled(), tx.execute(s -> repo.platformDefault().mode()));
    }

    @Override
    public PlanFeeCollectionView view(String orgId) {
        access.orgRoleOrStaff(orgId, AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING,
                SellerPermissions.BILLING_ADJUST, SellerPermissions.PLANS_MANAGE);
        return tx.execute(s -> build(orgId));
    }

    @Override
    public PlanFeeCollectionView choose(String orgId, String mode) {
        Actor actor = access.orgRole(orgId, AccessGuard.OWNER_OR_FINANCE, null);
        PlanFeeCollection next = PlanFeeCollection.parse(mode, "mode");
        requireAvailable();
        return tx.execute(s -> change(orgId, next, SOURCE_SELLER, actor.subject(), null, null));
    }

    @Override
    public PlanFeeCollectionView override(String orgId, String mode, String reasonCode, String note) {
        Actor actor = access.staff(SellerPermissions.BILLING_ADJUST);
        PlanFeeCollection next = PlanFeeCollection.parse(mode, "mode");
        if (settings.isRetail(orgId)) {
            throw new SellerRuleViolation("seller.retail-not-billed", "AfroTransact Retail is never billed");
        }
        String reason = reasonCode == null ? null : reasonCode.trim().toUpperCase(Locale.ROOT);
        String why = note == null || note.isBlank() ? null : note.trim();
        if (reason == null || tx.execute(s -> grants.reasonCodes()).stream().noneMatch(r -> r.code().equals(reason))) {
            throw invalid("reasonCode", "Choose a reason");
        }
        if ("OTHER".equals(reason) && why == null) {
            throw invalid("note", "Explain the reason");
        }
        if (why != null && why.length() > 500) {
            throw invalid("note", "At most 500 characters");
        }
        requireAvailable();
        return tx.execute(s -> change(orgId, next, SOURCE_STAFF, actor.subject(), reason, why));
    }

    @Override
    public PlatformSettings settings() {
        access.staffAny(SellerPermissions.BILLING, SellerPermissions.BILLING_ADJUST, SellerPermissions.PLANS_MANAGE);
        return tx.execute(s -> platform());
    }

    @Override
    public PlatformSettings setDefault(String mode) {
        Actor actor = access.staff(SellerPermissions.PLANS_MANAGE);
        PlanFeeCollection next = PlanFeeCollection.parse(mode, "planFeeCollectionDefault");
        return tx.execute(s -> {
            repo.setPlatformDefault(next, actor.subject(), Instant.now(clock));
            return platform();
        });
    }

    @Override
    public void initialise(String orgId, PlanFeeCollection chosen, String actorId) {
        Instant now = Instant.now(clock);
        if (chosen == null) {
            subs.setPlanFeeCollection(orgId, repo.platformDefault().mode(), SOURCE_DEFAULT, null, now);
            return;
        }
        subs.setPlanFeeCollection(orgId, chosen, SOURCE_ONBOARDING, actorId, now);
        repo.recordChange(new PlanFeeCollectionRepository.Change(UUID.randomUUID(), orgId, null, chosen,
                SOURCE_ONBOARDING, actorId == null ? "applicant" : actorId, null, null, null, now));
    }

    private PlatformSettings platform() {
        PlanFeeCollectionRepository.PlatformDefault d = repo.platformDefault();
        return new PlatformSettings(payments.enabled(), d.mode(), d.updatedBy(), d.updatedAt());
    }

    private PlanFeeCollectionView change(String orgId, PlanFeeCollection next, String source, String actor,
                                         String reason, String note) {
        Subscription sub = subs.lock(orgId).orElseThrow(() -> new NotFoundException("seller.no-subscription",
                "Organisation " + orgId + " has no subscription yet"));
        SubscriptionTerms terms = subs.terms(orgId);
        Instant now = Instant.now(clock);
        if (terms.planFeeCollection() != next || SOURCE_STAFF.equals(source)) {
            subs.setPlanFeeCollection(orgId, next, source, actor, now);
            repo.recordChange(new PlanFeeCollectionRepository.Change(UUID.randomUUID(), orgId,
                    terms.planFeeCollection(), next, source, actor, reason, note, sub.renewalAt(), now));
        }
        return build(orgId);
    }

    private PlanFeeCollectionView build(String orgId) {
        SubscriptionTerms terms = subs.terms(orgId);
        boolean available = payments.enabled();
        PlanFeeCollection platformDefault = repo.platformDefault().mode();
        Optional<Subscription> sub = subs.find(orgId);
        Instant appliesFrom = sub.map(Subscription::renewalAt).orElse(null);
        Optional<EarningsCollectionRepository.Row> inProgress = collections.inProgress(orgId);
        String label = appliesFrom == null ? "Applies from your first paid invoice"
                : "Applies from your next invoice, on " + DAY.format(appliesFrom.atZone(ZoneOffset.UTC));
        return new PlanFeeCollectionView(orgId, available, terms.planFeeCollection(),
                available ? terms.planFeeCollection() : PlanFeeCollection.CARD, terms.planFeeCollectionSource(),
                terms.planFeeCollectionChangedAt(), terms.planFeeCollectionChangedBy(), platformDefault, appliesFrom,
                label, inProgress.map(EarningsCollectionRepository.Row::invoiceId).orElse(null),
                repo.changes(orgId, HISTORY));
    }

    private void requireAvailable() {
        if (!payments.enabled()) {
            throw new SellerStateConflict("seller.earnings-collection-off", "Paying plans from sales is switched "
                    + "off on this platform; plan fees are charged to the card");
        }
    }

    private static SellerRuleViolation invalid(String field, String message) {
        return new SellerRuleViolation("seller.invalid-plan-fee-collection", message, Map.of(field, message));
    }
}
