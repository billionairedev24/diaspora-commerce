package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanStatus;
import com.afrotransact.seller.domain.PriceSyncState;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.StripePriceFacts;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.PlanRepository;
import com.afrotransact.seller.port.StripeBillingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlanSyncServiceImpl implements PlanSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(PlanSyncServiceImpl.class);

    private final PlanRepository plans;
    private final StripeBillingGateway stripe;
    private final AccessGuard access;
    private final Clock clock;

    PlanSyncServiceImpl(PlanRepository plans, StripeBillingGateway stripe, AccessGuard access, Clock clock) {
        this.plans = plans;
        this.stripe = stripe;
        this.access = access;
        this.clock = clock;
    }

    /** Its own transaction: callers may be read-only (previews) or hold row locks the sync never touches. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String ensurePrice(String planCode, int version, Cadence cadence) {
        PlanRepository.PriceRow row = plans.prices(planCode, version).stream().filter(p -> p.cadence() == cadence)
                .findFirst().orElseThrow(() -> new NotFoundException("seller.price-not-found",
                        "No price for " + planCode + " v" + version + " " + cadence));
        if (row.amountCents() == 0) {
            throw new SellerStateConflict("seller.free-plan", "A free plan has no Stripe price");
        }
        if (row.stripePriceId() != null) {
            return row.stripePriceId();
        }
        Plan plan = plans.find(planCode).orElseThrow();
        PlanSyncReport.Line line = syncPrice(plan, row, true);
        if (line.stripePriceId() == null) {
            throw new GatewayException("seller.stripe-price-missing", "Could not create the Stripe price of "
                    + planCode + " v" + version + " " + cadence + ": " + line.error(), false, null);
        }
        return line.stripePriceId();
    }

    @Override
    public PlanSyncReport report() {
        access.staffAny(SellerPermissions.PLANS_MANAGE, SellerPermissions.BILLING);
        return run(false, null);
    }

    @Override
    public PlanSyncReport sync() {
        access.staff(SellerPermissions.PLANS_MANAGE);
        return run(true, null);
    }

    @Override
    public PlanSyncReport reconcile() {
        PlanSyncReport r = run(true, null);
        LOG.info("Plan catalogue reconciled with Stripe: {} price(s) needed a change", r.drifted());
        return r;
    }

    @Override
    public PlanSyncReport syncPlan(String planCode) {
        return run(true, planCode);
    }

    private PlanSyncReport run(boolean apply, String only) {
        List<PlanSyncReport.Line> out = new ArrayList<>();
        for (Plan plan : plans.findAll()) {
            if (plan.status() == PlanStatus.DRAFT || plan.version() == 0 || (only != null
                    && !only.equals(plan.code()))) {
                continue;
            }
            if (apply && plan.status() == PlanStatus.ACTIVE && !plan.isFree()) {
                renameProduct(plan);
            }
            // Monthly first: a Price adopted from make stripe-setup also gives the plan its Product.
            for (PlanRepository.PriceRow row : plans.prices(plan.code(), plan.version()).stream()
                    .sorted(Comparator.comparing(PlanRepository.PriceRow::cadence)).toList()) {
                out.add(syncPrice(plan, row, apply));
            }
        }
        return new PlanSyncReport(Instant.now(clock), apply, out);
    }

    private PlanSyncReport.Line syncPrice(Plan plan, PlanRepository.PriceRow row, boolean apply) {
        Cadence c = row.cadence();
        if (row.amountCents() == 0) {
            return line(plan, row, PriceSyncState.FREE, "NONE", null, null, null);
        }
        try {
            StripePriceFacts stored = row.stripePriceId() == null ? null
                    : stripe.retrievePrice(row.stripePriceId()).orElse(null);
            StripePriceFacts byKey = row.stripePriceId() == null
                    ? stripe.findPriceByLookupKey(row.lookupKey()).orElse(null) : null;
            PriceSyncState state = PriceSyncState.of(row.amountCents(), c, row.stripePriceId(), stored, byKey);
            Long stripeAmount = stored != null ? Long.valueOf(stored.amountCents())
                    : byKey != null ? Long.valueOf(byKey.amountCents()) : null;
            if (!apply || state == PriceSyncState.IN_SYNC) {
                return line(plan, row, state, "NONE", row.stripePriceId(), stripeAmount, null);
            }
            Instant now = Instant.now(clock);
            if (state == PriceSyncState.ADOPTABLE) {
                adoptProduct(plan, byKey.productId());
                plans.recordStripePrice(plan.code(), row.version(), c, byKey.id(), now);
                return line(plan, row, state, "ADOPTED", byKey.id(), stripeAmount, null);
            }
            String product = product(plan, stored != null ? stored.productId() : byKey != null ? byKey.productId()
                    : null);
            StripePriceFacts old = stored != null ? stored : byKey;
            String id = stripe.createPrice(product, row.amountCents(), c, row.lookupKey(), plan.code(), row.version(),
                    "plan-price-" + plan.code() + "-v" + row.version() + "-" + c.name().toLowerCase(Locale.ROOT)
                            + "-" + row.amountCents() + (old == null ? "" : "-" + old.id()));
            plans.recordStripePrice(plan.code(), row.version(), c, id, now);
            String action = "CREATED";
            // Never edit a Price: the old one is archived (its subscribers keep renewing on it until migrated).
            if (old != null && old.active() && !old.id().equals(id)) {
                stripe.archivePrice(old.id());
                action = "REPLACED";
            }
            return line(plan, row, state, action, id, stripeAmount, null);
        } catch (GatewayException e) {
            LOG.warn("Stripe sync of {} v{} {} failed: {}", plan.code(), row.version(), c, e.getMessage());
            plans.recordSyncError(plan.code(), row.version(), c, e.getMessage());
            return line(plan, row, PriceSyncState.MISSING, "FAILED", row.stripePriceId(), null, e.getMessage());
        }
    }

    private void renameProduct(Plan plan) {
        Optional<String> product = productOf(plan.code());
        if (product.isPresent()) {
            try {
                stripe.updateProduct(product.get(), productName(plan));
            } catch (GatewayException e) {
                LOG.warn("Could not rename Stripe product of {}: {}", plan.code(), e.getMessage());
            }
        }
    }

    private void adoptProduct(Plan plan, String productId) {
        if (productId != null && productOf(plan.code()).isEmpty()) {
            plans.setStripeProduct(plan.code(), productId);
        }
    }

    private String product(Plan plan, String fallback) {
        Optional<String> existing = productOf(plan.code());
        if (existing.isPresent()) {
            return existing.get();
        }
        String id = fallback != null ? fallback
                : stripe.createProduct(plan.code(), productName(plan), "plan-product-" + plan.code());
        plans.setStripeProduct(plan.code(), id);
        return id;
    }

    private Optional<String> productOf(String code) {
        return Optional.ofNullable(plans.stripeProduct(code));
    }

    private static String productName(Plan plan) {
        return "AfroTransact " + plan.name();
    }

    private static PlanSyncReport.Line line(Plan plan, PlanRepository.PriceRow row, PriceSyncState state,
                                            String action, String priceId, Long stripeAmount, String error) {
        return new PlanSyncReport.Line(plan.code(), plan.name(), row.version(), row.cadence(), row.amountCents(),
                row.lookupKey(), state, action, priceId, stripeAmount, error);
    }
}
