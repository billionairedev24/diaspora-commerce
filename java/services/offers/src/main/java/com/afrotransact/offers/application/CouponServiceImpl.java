package com.afrotransact.offers.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.domain.Coupon;
import com.afrotransact.offers.domain.CouponCart;
import com.afrotransact.offers.domain.CouponEvaluation;
import com.afrotransact.offers.domain.CouponFunding;
import com.afrotransact.offers.domain.CouponLine;
import com.afrotransact.offers.domain.CouponNotApplicable;
import com.afrotransact.offers.domain.CouponPolicy;
import com.afrotransact.offers.domain.CouponRedemption;
import com.afrotransact.offers.domain.CouponRejection;
import com.afrotransact.offers.domain.CouponStatus;
import com.afrotransact.offers.domain.CouponUsage;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.RedemptionStatus;
import com.afrotransact.offers.port.CouponRepository;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CouponServiceImpl implements CouponService {

    private static final int MAX_KEY = 120;

    private final CouponRepository coupons;
    private final OfferRepository offers;
    private final CouponPolicy policy;
    private final PermissionChecker permissions;
    private final ActorResolver actors;
    private final DomainEventPublisher events;
    private final Clock clock;

    CouponServiceImpl(CouponRepository coupons, OfferRepository offers, CouponPolicy policy,
                      PermissionChecker permissions, ActorResolver actors, DomainEventPublisher events, Clock clock) {
        this.coupons = coupons;
        this.offers = offers;
        this.policy = policy;
        this.permissions = permissions;
        this.actors = actors;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Coupon create(CreateCouponCommand cmd) {
        if (cmd.funding() == null) {
            throw new OfferRuleViolation("The coupon is not valid", Map.of("funding", "Required"));
        }
        Actor actor;
        String fundedBy = null;
        if (cmd.funding() == CouponFunding.PLATFORM) {
            actor = requirePlatform();
        } else {
            actor = permissions.require(OffersPermissions.COUPONS_WRITE);
            fundedBy = Actors.isStaff(actor) && cmd.fundedByOrg() != null && !cmd.fundedByOrg().isBlank()
                    ? cmd.fundedByOrg().trim()
                    : actor.orgId().orElseThrow(() -> new OfferRuleViolation(
                            "Your account is not linked to a seller organisation"));
            permissions.requireForOrg(OffersPermissions.COUPONS_WRITE, fundedBy);
        }
        String code = cmd.code() == null ? null : cmd.code().trim().toUpperCase(Locale.ROOT);
        Instant now = Instant.now(clock);
        Coupon coupon = new Coupon(UUID.randomUUID(), code, cmd.description(), cmd.type(), cmd.value(),
                cmd.currency() == null ? "USD" : cmd.currency().trim().toUpperCase(Locale.ROOT),
                cmd.maxDiscountMinor(), cmd.scope(), cmd.scopeRef() == null ? null : cmd.scopeRef().trim(),
                cmd.minSpendMinor(), cmd.totalLimit(), cmd.perCustomerLimit(), cmd.clipRequired(), cmd.funding(),
                fundedBy, cmd.startsAt() == null ? now : cmd.startsAt(), cmd.endsAt(), CouponStatus.ACTIVE, 0,
                actor.subject(), now, now, 0);
        policy.validate(coupon);
        if (coupons.findByCode(code).isPresent()) {
            throw new ConflictException("offers.coupon-code-taken", "Coupon code " + code + " already exists");
        }
        Coupon saved = coupons.insert(coupon);
        publish(OffersEvents.COUPON_CREATED, saved);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Coupon> list(CouponFunding funding, String fundedByOrg, CouponStatus status) {
        Actor actor = actors.current();
        if (actor.can(OffersPermissions.COUPONS_PLATFORM_WRITE) && Actors.isStaff(actor)) {
            return coupons.list(funding, fundedByOrg, status);
        }
        permissions.require(OffersPermissions.COUPONS_WRITE);
        String org = actor.orgId().orElseThrow(() -> new OfferRuleViolation(
                "Your account is not linked to a seller organisation"));
        return coupons.list(CouponFunding.SELLER, org, status);
    }

    @Override
    @Transactional(readOnly = true)
    public Coupon get(UUID id) {
        Coupon c = coupons.findById(id).orElseThrow(() -> notFound(id.toString()));
        ensureManager(c);
        return c;
    }

    @Override
    @Transactional
    public Coupon changeStatus(UUID id, CouponStatus status) {
        Coupon c = coupons.lockById(id).orElseThrow(() -> notFound(id.toString()));
        ensureManager(c);
        Coupon saved = coupons.update(c.withStatus(status, Instant.now(clock)));
        publish(OffersEvents.COUPON_UPDATED, saved);
        return saved;
    }

    @Override
    @Transactional
    public Coupon clip(String code) {
        Actor actor = actors.current();
        if (actor.type() != PrincipalType.CUSTOMER) {
            throw new ForbiddenActionException("offers.customers-only", "Only customers clip coupons");
        }
        Coupon c = coupons.findByCode(normaliseCode(code)).orElseThrow(() -> notFound(code));
        Instant now = Instant.now(clock);
        if (!c.clipRequired() || c.status() != CouponStatus.ACTIVE || !c.inWindow(now)) {
            throw new OfferRuleViolation("This coupon cannot be clipped");
        }
        coupons.clip(c.id(), actor.subject(), now);
        return c;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Coupon> clippable(String atin, String currency) {
        String a = atin.trim().toUpperCase(Locale.ROOT);
        String cur = currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase(Locale.ROOT);
        List<CouponLine> lines = offers.findOpenByAtins(List.of(a)).stream()
                .filter(o -> o.live() && o.currency().equals(cur))
                .map(o -> line(o, 1))
                .toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        return coupons.findClippable(cur, Instant.now(clock)).stream()
                .filter(c -> lines.stream().anyMatch(l -> policy.appliesTo(c, l)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CouponQuote validate(CouponCartCommand cmd) {
        Actor actor = actors.current();
        String customer = customerOf(actor, cmd.customerId());
        Optional<Coupon> found = coupons.findByCode(normaliseCode(cmd.code()));
        if (found.isEmpty()) {
            return new CouponQuote(normaliseCode(cmd.code()), null, false, CouponRejection.NOT_FOUND,
                    "We don't recognise that code", cmd.currency(), 0, 0, 0, null, null, List.of());
        }
        Coupon c = found.get();
        CouponEvaluation e = policy.evaluate(c, cart(cmd), usage(c, customer), Instant.now(clock));
        return quote(c, cmd.currency(), e);
    }

    @Override
    @Transactional
    public RedemptionResult redeem(String idempotencyKey, CouponCartCommand cmd) {
        Actor actor = actors.current();
        if (!Actors.isStaff(actor)) {
            throw new ForbiddenActionException("offers.service-only",
                    "Coupons are redeemed by orders-service when the order is placed");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY) {
            throw new OfferRuleViolation("An Idempotency-Key header (1–" + MAX_KEY + " characters) is required",
                    Map.of("Idempotency-Key", "Required"));
        }
        if (cmd.customerId() == null || cmd.customerId().isBlank() || cmd.orderId() == null
                || cmd.orderId().isBlank()) {
            throw new OfferRuleViolation("customerId and orderId are required to redeem",
                    Map.of("customerId", "Required", "orderId", "Required"));
        }
        Coupon c = coupons.lockByCode(normaliseCode(cmd.code())).orElseThrow(() -> notFound(cmd.code()));
        String fingerprint = fingerprint(c, cmd);
        Optional<CouponRedemption> existing = coupons.findRedemptionByKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().fingerprint().equals(fingerprint)) {
                throw new ConflictException("offers.idempotency-key-reused",
                        "Idempotency-Key " + idempotencyKey + " was already used for a different request");
            }
            return new RedemptionResult(existing.get(), true);
        }
        Instant now = Instant.now(clock);
        CouponEvaluation e = policy.evaluate(c, cart(cmd), usage(c, cmd.customerId()), now);
        if (!e.applicable()) {
            throw new CouponNotApplicable(e.rejection(), e.message());
        }
        CouponRedemption saved = coupons.insertRedemption(new CouponRedemption(UUID.randomUUID(), c.id(), c.code(),
                cmd.customerId(), cmd.orderId(), idempotencyKey, fingerprint, c.currency(), e.discountMinor(),
                e.shippingDiscountMinor(), c.funding(), c.fundedByOrg(), e.lines(), RedemptionStatus.REDEEMED, now,
                null));
        coupons.update(c.withRedeemedDelta(1, now));
        events.publish(OffersEvents.COUPON_REDEEMED, OffersEvents.AGGREGATE, c.id().toString(),
                RedemptionEventPayload.of(saved));
        return new RedemptionResult(saved, false);
    }

    @Override
    @Transactional
    public RedemptionResult release(UUID redemptionId) {
        Actor actor = actors.current();
        if (!Actors.isStaff(actor)) {
            throw new ForbiddenActionException("offers.service-only", "Redemptions are released by orders-service");
        }
        CouponRedemption r = coupons.findRedemption(redemptionId)
                .orElseThrow(() -> new NotFoundException("offers.redemption-not-found",
                        "Redemption " + redemptionId + " not found"));
        Coupon c = coupons.lockById(r.couponId()).orElseThrow(() -> notFound(r.couponId().toString()));
        CouponRedemption current = coupons.findRedemption(redemptionId).orElseThrow();
        if (current.status() == RedemptionStatus.RELEASED) {
            return new RedemptionResult(current, true);
        }
        Instant now = Instant.now(clock);
        CouponRedemption released = coupons.updateRedemption(current.release(now));
        coupons.update(c.withRedeemedDelta(-1, now));
        events.publish(OffersEvents.COUPON_RELEASED, OffersEvents.AGGREGATE, c.id().toString(),
                RedemptionEventPayload.of(released));
        return new RedemptionResult(released, false);
    }

    private Actor requirePlatform() {
        Actor actor = permissions.require(OffersPermissions.COUPONS_PLATFORM_WRITE);
        if (!Actors.isStaff(actor)) {
            throw new ForbiddenActionException("offers.staff-only", "Platform coupons are managed by staff");
        }
        return actor;
    }

    private void ensureManager(Coupon c) {
        if (c.funding() == CouponFunding.PLATFORM) {
            requirePlatform();
            return;
        }
        Actor actor = actors.current();
        if (Actors.isStaff(actor) && actor.can(OffersPermissions.COUPONS_PLATFORM_WRITE)) {
            return;
        }
        permissions.requireForOrg(OffersPermissions.COUPONS_WRITE, c.fundedByOrg());
    }

    private static String customerOf(Actor actor, String requested) {
        if (actor.type() == PrincipalType.CUSTOMER) {
            return actor.subject();
        }
        return requested == null || requested.isBlank() ? null : requested;
    }

    private CouponUsage usage(Coupon c, String customerId) {
        long total = coupons.redeemedCount(c.id());
        long mine = customerId == null ? 0 : coupons.redeemedCount(c.id(), customerId);
        boolean clipped = customerId != null && coupons.clipped(c.id(), customerId);
        return new CouponUsage(total, mine, clipped);
    }

    private CouponCart cart(CouponCartCommand cmd) {
        if (cmd.lines().isEmpty()) {
            throw new OfferRuleViolation("The cart is empty", Map.of("lines", "Required"));
        }
        Map<UUID, Integer> qty = new HashMap<>();
        for (CouponCartCommand.Line l : cmd.lines()) {
            if (l.offerId() == null) {
                throw new OfferRuleViolation("Every line needs an offerId", Map.of("lines", "offerId required"));
            }
            qty.merge(l.offerId(), l.quantity(), Integer::sum);
        }
        Map<UUID, Offer> byId = new HashMap<>();
        for (UUID id : qty.keySet()) {
            Offer o = offers.findById(id).filter(x -> x.status() != OfferStatus.WITHDRAWN)
                    .orElseThrow(() -> new NotFoundException("offers.offer-not-found", "Offer " + id + " not found"));
            byId.put(id, o);
        }
        List<CouponLine> lines = new ArrayList<>();
        for (CouponCartCommand.Line l : cmd.lines()) {
            if (qty.containsKey(l.offerId())) {
                lines.add(line(byId.get(l.offerId()), qty.remove(l.offerId())));
            }
        }
        String currency = cmd.currency() == null ? "USD" : cmd.currency().trim().toUpperCase(Locale.ROOT);
        return new CouponCart(currency, lines, cmd.shippingMinor(), cmd.firstOrder());
    }

    private static CouponLine line(Offer o, int quantity) {
        return new CouponLine(o.id().toString(), o.atin(), o.sellerOrgId(),
                o.categoryId() == null ? null : o.categoryId().toString(), o.fulfilment(), o.priceMinor(), quantity);
    }

    private static CouponQuote quote(Coupon c, String currency, CouponEvaluation e) {
        return new CouponQuote(c.code(), c.id(), e.applicable(), e.rejection(), e.message(), currency,
                e.eligibleSubtotalMinor(), e.discountMinor(), e.shippingDiscountMinor(), c.funding(), c.fundedByOrg(),
                e.lines());
    }

    private static String fingerprint(Coupon c, CouponCartCommand cmd) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.id()).append('|').append(cmd.customerId()).append('|').append(cmd.orderId()).append('|')
                .append(cmd.currency()).append('|').append(cmd.shippingMinor()).append('|').append(cmd.firstOrder());
        cmd.lines().stream()
                .map(l -> l.offerId() + "x" + l.quantity())
                .sorted()
                .forEach(s -> sb.append('|').append(s));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String normaliseCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private void publish(String type, Coupon c) {
        events.publish(type, OffersEvents.AGGREGATE, c.id().toString(), CouponEventPayload.of(c));
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("offers.coupon-not-found", "Coupon " + id + " not found");
    }
}
