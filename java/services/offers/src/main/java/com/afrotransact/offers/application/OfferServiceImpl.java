package com.afrotransact.offers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferPackage;
import com.afrotransact.offers.domain.OfferPolicy;
import com.afrotransact.offers.domain.PageContentLocked;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.application.OfferTaxGate.Verdict;
import com.afrotransact.offers.port.CatalogClient;
import com.afrotransact.offers.port.CatalogClient.CatalogVariant;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.offers.port.OfferRepository.OfferPage;
import com.afrotransact.offers.port.OfferRepository.OfferQuery;
import com.afrotransact.offers.port.OfferTaxMetrics;
import com.afrotransact.offers.port.ListingEntitlementsClient;
import com.afrotransact.offers.port.PickupLocationClient;
import com.afrotransact.offers.port.PickupLocationClient.PickupLocation;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.relationship.FgaObject;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.BusinessRuleException;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OfferServiceImpl implements OfferService {

    /** The field a refusal about the chosen location is reported on (the gateway maps it to the form's location field). */
    static final String LOCATION_FIELD = "shipFrom.locationId";

    static final int MAX_SELLER_QUANTITY = 99_999;

    private final OfferRepository offers;
    private final CatalogClient catalog;
    private final PickupLocationClient pickupLocations;
    private final ListingEntitlementsClient entitlements;
    private final BuyBoxService buyBox;
    private final BuyBoxEvaluator evaluator;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final DomainEventPublisher events;
    private final OffersSettings settings;
    private final OfferTaxGate taxGate;
    private final TaxHoldService taxHold;
    private final OfferSettingsService policy;
    private final OfferTaxMetrics taxMetrics;
    private final Clock clock;

    OfferServiceImpl(OfferRepository offers, CatalogClient catalog, PickupLocationClient pickupLocations,
                     ListingEntitlementsClient entitlements, BuyBoxService buyBox, BuyBoxEvaluator evaluator, PermissionChecker permissions,
                     ResourceAccessChecker access, DomainEventPublisher events, OffersSettings settings,
                     OfferTaxGate taxGate, TaxHoldService taxHold, OfferSettingsService policy,
                     OfferTaxMetrics taxMetrics, Clock clock) {
        this.offers = offers;
        this.catalog = catalog;
        this.pickupLocations = pickupLocations;
        this.entitlements = entitlements;
        this.buyBox = buyBox;
        this.evaluator = evaluator;
        this.permissions = permissions;
        this.access = access;
        this.events = events;
        this.settings = settings;
        this.taxGate = taxGate;
        this.taxHold = taxHold;
        this.policy = policy;
        this.taxMetrics = taxMetrics;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OfferView create(CreateOfferCommand cmd) {
        Actor actor = permissions.require(OffersPermissions.WRITE);
        String org = sellerOrg(actor, cmd.sellerOrgId());
        access.requireForOrg(OffersPermissions.WRITE, OffersRelations.ORG_MANAGE_PRODUCTS, org);
        // An offer carries offer terms only: the page's content is the page's (S383).
        PageContentLocked.refuse(cmd.unknownFields());
        validateCreate(cmd);
        requireNoPerProductShipping(cmd.shippingMinor(), 0);
        requireNoHeldSellerStock(cmd.fulfilment(), org);
        requireListingRoom(org);
        ShipFrom shipFrom = switch (cmd.fulfilment()) {
            case AFROTRANSACT_DELIVERY -> pickupShipFrom(org, cmd.shipFrom());
            case SELLER -> sellerShipFrom(org, cmd.shipFrom());
            case AFROTRANSACT -> cmd.shipFrom();
        };

        CatalogVariant variant = resolve(cmd);
        // The same checks the product writer applies to a listing: page on sale, brand, department, selling approval.
        CatalogClient.Eligibility eligibility = catalog.eligibility(variant.variantAtin(), org);
        requireEligible(eligibility);
        OfferPolicy limits = policy.current();
        OfferCondition condition = cmd.condition() == null ? OfferCondition.NEW : cmd.condition();
        String conditionNote = checkCondition(condition, cmd.conditionNote(), eligibility, limits);
        OfferPackage box = checkPackage(cmd.fulfilment(), cmd.packaging(), limits);
        boolean fba = cmd.fulfilment().fulfilledByAfroTransact();
        offers.findOpen(org, variant.variantAtin(), condition, fba).ifPresent(existing -> {
            throw new ConflictException("offers.offer-exists", "You already have a " + condition + " "
                    + channel(fba) + " offer on " + variant.variantAtin() + " (" + existing.id()
                    + "); update it instead");
        });
        String sku = cmd.sellerSku().trim().toUpperCase(Locale.ROOT);
        if (offers.sellerSkuTaken(org, sku, fba)) {
            throw new ConflictException("offers.sku-taken",
                    "SKU " + sku + " is already used by another of your " + channel(fba) + " offers");
        }
        // The tax rule (S384): live only with this seller's own RESOLVED decision on the ATIN, else held. Never a default.
        Verdict verdict = taxGate.check(variant.variantAtin(), org, cmd.sellerBehaviour());
        Instant now = Instant.now(clock);
        String inventorySku = cmd.inventorySku() == null || cmd.inventorySku().isBlank() ? sku
                : cmd.inventorySku().trim().toUpperCase(Locale.ROOT);
        Offer offer = offers.insert(new Offer(UUID.randomUUID(), variant.variantAtin(), variant.productAtin(),
                variant.productId(), variant.gtin(), variant.categoryId(), org, kindOf(org), sku, inventorySku,
                condition, cmd.priceMinor(), cmd.listPriceMinor(), currency(cmd.currency()), cmd.shippingMinor(),
                cmd.fulfilment(), cmd.quantity() == null ? 0 : cmd.quantity(),
                cmd.handlingDays() == null ? settings.defaultHandlingDays() : cmd.handlingDays(), shipFrom,
                verdict.resolved() ? OfferStatus.ACTIVE : OfferStatus.TAX_HELD, true, now, now, 0, conditionNote, box,
                cmd.sellerBehaviour(), verdict.resolved() ? null : verdict.holdReason(),
                verdict.resolved() ? Offer.TAX_BASIS_SELLER_DECISION : null));
        publish(OffersEvents.OFFER_CREATED, offer);
        if (!verdict.resolved()) {
            publish(OffersEvents.OFFER_TAX_HELD, offer);
            taxMetrics.held(verdict.holdReason());
        }
        buyBox.recompute(List.of(offer.atin()), OffersEvents.OFFER_CREATED);
        return evaluator.view(offer);
    }

    /** Maps what catalog says about the page to the offers error codes of the contract. */
    private static void requireEligible(CatalogClient.Eligibility eligibility) {
        if (eligibility.canOffer()) {
            return;
        }
        CatalogClient.Blocker blocker = eligibility.blockers().isEmpty()
                ? new CatalogClient.Blocker("offers.not-allowed", "You cannot add an offer to this product.")
                : eligibility.blockers().getFirst();
        String message = blocker.message();
        switch (blocker.code()) {
            case "offers.selling-approval-required" ->
                    throw new BusinessRuleException("offers.selling-approval-required", message);
            case "catalog.brand-unavailable" -> throw new BusinessRuleException("offers.brand-unavailable", message);
            case "catalog.department-not-allowed", "catalog.no-departments" ->
                    throw new ForbiddenActionException(blocker.code(), message);
            case "catalog.page-not-live" -> throw new NotFoundException("offers.product-not-found", message);
            case "catalog.selling-unavailable" -> throw new CatalogClient.CatalogUnavailableException(message, null);
            default -> throw new BusinessRuleException("offers.not-allowed", message);
        }
    }

    /**
     * A used or renewed item needs a category that allows it and a short note on what is different (the longest note is a
     * setting). A new item keeps no note. Returns the note to store.
     */
    private static String checkCondition(OfferCondition condition, String note, CatalogClient.Eligibility eligibility,
                                         OfferPolicy limits) {
        if (condition == OfferCondition.NEW) {
            return null;
        }
        if (!eligibility.allowedConditions().contains(condition.name())) {
            throw new BusinessRuleException("offers.condition-not-allowed",
                    "Used or renewed items cannot be sold in this category. Sell it as new.");
        }
        String trimmed = note == null ? "" : note.strip();
        if (trimmed.isEmpty()) {
            throw new BusinessRuleException("offers.condition-note-required",
                    "Say what is different about this item, for example any marks or what is missing.");
        }
        if (trimmed.length() > limits.conditionNoteMaxLength()) {
            throw new OfferRuleViolation("The offer is not valid", Map.of("conditionNote",
                    "At most " + limits.conditionNoteMaxLength() + " characters"));
        }
        return trimmed;
    }

    /** The box of a Fulfilled by AfroTransact offer; no other offer carries one. Returns the package to store. */
    private static OfferPackage checkPackage(Fulfilment fulfilment, OfferPackage box, OfferPolicy limits) {
        if (box == null) {
            return null;
        }
        if (fulfilment != Fulfilment.AFROTRANSACT) {
            throw new BusinessRuleException("offers.package-not-allowed", "Package size and weight are only for "
                    + "Fulfilled by AfroTransact offers. For other offers the page already has the item's package.");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (box.longestEdgeIn() > limits.packageMaxEdgeIn()) {
            errors.put("packaging", "Each side must be at most " + limits.packageMaxEdgeIn() + " in");
        }
        if (box.weightLb() > limits.packageMaxWeightLb()) {
            errors.put("packaging.weightLb", "Weight must be at most " + limits.packageMaxWeightLb() + " lb");
        }
        if (!errors.isEmpty()) {
            throw new OfferRuleViolation("The offer is not valid", errors);
        }
        return box;
    }

    /**
     * A product carries no shipping charge of its own. A seller's own shipping (only while the staff switch is on, off by
     * default) is one flat charge per order set once in the seller's shipping settings (orders), never per product, so any
     * other amount is refused. An existing offer keeps the amount it already has (history); it can only be set to 0.
     */
    private static void requireNoPerProductShipping(Long requestedMinor, long currentMinor) {
        if (requestedMinor != null && requestedMinor != 0 && requestedMinor != currentMinor) {
            throw new OfferRuleViolation("The offer is not valid", Map.of("shippingMinor",
                    "A product has no shipping charge of its own. AfroTransact delivers, or the customer picks up"));
        }
    }

    /**
     * AfroTransact holds no seller stock: Fulfilled by AfroTransact means it collects from the seller's pickup location and
     * delivers (AFROTRANSACT_DELIVERY, with a location). A marketplace seller cannot create a stock-held offer; AfroTransact
     * Retail's own stock is unaffected, and offers that already exist stay readable.
     */
    private void requireNoHeldSellerStock(Fulfilment fulfilment, String org) {
        if (fulfilment == Fulfilment.AFROTRANSACT && kindOf(org) == SellerKind.SELLER) {
            throw new BusinessRuleException("offers.seller-stock-not-held", "AfroTransact does not keep sellers' stock. "
                    + "Choose Fulfilled by AfroTransact with one of your pickup locations: we collect from you and "
                    + "deliver to the customer");
        }
    }

    /** The plan's listing cap: one more live listing must fit (there is no limit on shops or users). */
    private void requireListingRoom(String sellerOrgId) {
        ListingEntitlementsClient.ListingAllowance allowance = entitlements.allowance(sellerOrgId);
        if (allowance.limit() == null) {
            return;
        }
        long live = offers.countActive(sellerOrgId);
        if (!allowance.allows(live)) {
            throw new BusinessRuleException("offers.listing-limit-reached", "Your " + (allowance.planName() == null
                    ? "plan" : allowance.planName() + " plan") + " allows " + allowance.limit() + " live listings and "
                    + "you have " + live + ". Pause or withdraw a listing, or upgrade your plan to list more.");
        }
    }

    @Override
    @Transactional
    public OfferView update(UUID id, UpdateOfferCommand cmd, Long expectedVersion) {
        PageContentLocked.refuse(cmd.unknownFields());
        return change(id, expectedVersion, o -> {
            Offer.Terms t = o.terms();
            Long listPrice = cmd.listPriceMinor() == null ? t.listPriceMinor()
                    : cmd.listPriceMinor() == 0 ? null : cmd.listPriceMinor();
            Offer.Terms proposed = new Offer.Terms(
                    cmd.condition() == null ? t.condition() : cmd.condition(),
                    cmd.priceMinor() == null ? t.priceMinor() : cmd.priceMinor(),
                    listPrice,
                    cmd.shippingMinor() == null ? t.shippingMinor() : cmd.shippingMinor(),
                    cmd.fulfilment() == null ? t.fulfilment() : cmd.fulfilment(),
                    cmd.quantity() == null ? t.sellerQuantity() : cmd.quantity(),
                    cmd.handlingDays() == null ? t.handlingDays() : cmd.handlingDays(),
                    cmd.shipFrom() == null ? t.shipFrom() : cmd.shipFrom(),
                    cmd.inventorySku() == null || cmd.inventorySku().isBlank() ? t.inventorySku()
                            : cmd.inventorySku().trim().toUpperCase(Locale.ROOT));
            if (proposed.fulfilment() == Fulfilment.SELLER) {
                // A named location is looked up again; the stored place of an unchanged offer is only checked for being there.
                proposed = cmd.shipFrom() != null || t.fulfilment() != Fulfilment.SELLER
                        ? withShipFrom(proposed, sellerShipFrom(o.sellerOrgId(), proposed.shipFrom()))
                        : requireKept(proposed);
            }
            if (cmd.shippingMinor() != null) {
                requireNoPerProductShipping(cmd.shippingMinor(), t.shippingMinor());
            }
            if (proposed.fulfilment() == Fulfilment.AFROTRANSACT && t.fulfilment() != Fulfilment.AFROTRANSACT) {
                requireNoHeldSellerStock(proposed.fulfilment(), o.sellerOrgId());
            }
            Offer.Terms next = proposed;
            if (proposed.fulfilment() == Fulfilment.AFROTRANSACT_DELIVERY) {
                // Re-checked on every change: the location may have been disabled since the offer was created.
                next = new Offer.Terms(proposed.condition(), proposed.priceMinor(), proposed.listPriceMinor(),
                        proposed.shippingMinor(), proposed.fulfilment(), proposed.sellerQuantity(),
                        proposed.handlingDays(), pickupShipFrom(o.sellerOrgId(), proposed.shipFrom()),
                        proposed.inventorySku());
            }
            Offer.Terms checked = next;
            boolean fba = checked.fulfilment().fulfilledByAfroTransact();
            if (checked.condition() != t.condition() || fba != t.fulfilment().fulfilledByAfroTransact()) {
                offers.findOpen(o.sellerOrgId(), o.atin(), checked.condition(), fba)
                        .filter(other -> !other.id().equals(o.id()))
                        .ifPresent(other -> {
                            throw new ConflictException("offers.offer-exists", "You already have a "
                                    + checked.condition() + " " + channel(fba) + " offer on " + o.atin());
                        });
                if (fba != t.fulfilment().fulfilledByAfroTransact()
                        && offers.sellerSkuTaken(o.sellerOrgId(), o.sellerSku(), fba)) {
                    throw new ConflictException("offers.sku-taken", "SKU " + o.sellerSku()
                            + " is already used by another of your " + channel(fba) + " offers");
                }
            }
            Instant now = Instant.now(clock);
            Offer revised = o.revise(checked, now);
            String note = o.conditionNote();
            if (checked.condition() != t.condition() || cmd.conditionNote() != null) {
                String sent = cmd.conditionNote() != null ? cmd.conditionNote() : o.conditionNote();
                note = checkCondition(checked.condition(), sent, catalog.eligibility(o.atin(), o.sellerOrgId()),
                        policy.current());
            }
            OfferPackage box = checkPackage(checked.fulfilment(), cmd.packaging(), policy.current());
            if (cmd.packaging() == null && checked.fulfilment() == Fulfilment.AFROTRANSACT) {
                box = o.packaging();
            }
            return revised.withDetails(note, box, now);
        }, OffersEvents.OFFER_UPDATED);
    }

    @Override
    @Transactional
    public OfferView update(UUID id, UpdateOfferCommand cmd) {
        return update(id, cmd, null);
    }

    @Override
    @Transactional
    public OfferView pause(UUID id) {
        return pause(id, null);
    }

    @Override
    @Transactional
    public OfferView resume(UUID id) {
        return resume(id, null);
    }

    @Override
    @Transactional
    public OfferView withdraw(UUID id) {
        return withdraw(id, null);
    }

    @Override
    @Transactional
    public OfferView pause(UUID id, Long expectedVersion) {
        return change(id, expectedVersion, o -> o.pause(Instant.now(clock)), OffersEvents.OFFER_UPDATED);
    }

    @Override
    @Transactional
    public OfferView resume(UUID id, Long expectedVersion) {
        OfferView resumed = change(id, expectedVersion, o -> {
            requireListingRoom(o.sellerOrgId());
            return o.resume(Instant.now(clock));
        }, OffersEvents.OFFER_UPDATED);
        if (resumed.offer().status() != OfferStatus.TAX_HELD) {
            return resumed;
        }
        // Not verified yet (it was paused before the tax check existed): ask now, and release it if the seller has a decision.
        taxHold.releaseFor(resumed.offer().atin());
        return evaluator.view(offers.findById(id).orElseThrow());
    }

    @Override
    @Transactional
    public OfferView withdraw(UUID id, Long expectedVersion) {
        return change(id, expectedVersion, o -> o.withdraw(Instant.now(clock)), OffersEvents.OFFER_WITHDRAWN);
    }

    @Override
    @Transactional
    public StockUpdate setSellerStock(String sellerSku, int quantity, String reason, String sellerOrgId) {
        Actor actor = permissions.require(OffersPermissions.WRITE);
        String org = sellerOrg(actor, sellerOrgId);
        access.requireForOrg(OffersPermissions.WRITE, OffersRelations.ORG_MANAGE_PRODUCTS, org);
        if (quantity < 0 || quantity > MAX_SELLER_QUANTITY) {
            throw new OfferRuleViolation("The quantity is not valid",
                    Map.of("quantity", "Between 0 and " + MAX_SELLER_QUANTITY));
        }
        String sku = sellerSku == null ? "" : sellerSku.trim().toUpperCase(Locale.ROOT);
        List<Offer> owned = sku.isEmpty() ? List.of() : offers.findOpenBySellerSku(org, sku);
        if (owned.isEmpty()) {
            throw new NotFoundException("offers.sku-not-found", "None of your open offers uses SKU " + sku);
        }
        if (owned.stream().noneMatch(o -> o.fulfilment().sellerStock())) {
            throw new ConflictException("offers.stock-managed-by-inventory", "SKU " + sku
                    + " is Fulfilled by AfroTransact; its stock comes from the fulfilment centre");
        }
        // The same SKU may also name the seller's Fulfilled-by-AfroTransact offer (S257): its stock is inventory's.
        owned = owned.stream().filter(o -> o.fulfilment().sellerStock()).toList();
        Instant now = Instant.now(clock);
        List<UUID> ids = new ArrayList<>();
        for (Offer o : owned) {
            ids.add(o.id());
            if (o.sellerQuantity() == quantity) {
                continue;
            }
            Offer.Terms t = o.terms();
            Offer updated = offers.update(o.revise(new Offer.Terms(t.condition(), t.priceMinor(), t.listPriceMinor(),
                    t.shippingMinor(), t.fulfilment(), quantity, t.handlingDays(), t.shipFrom(), t.inventorySku()),
                    now));
            publish(OffersEvents.OFFER_UPDATED, updated);
            buyBox.recompute(List.of(updated.atin()), OffersEvents.OFFER_UPDATED);
        }
        return new StockUpdate(sku, quantity, reason, ids);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenOffer(String sellerOrgId, String atin) {
        Actor actor = permissions.require(OffersPermissions.READ);
        if (!Actors.isStaff(actor) && !actor.orgId().map(sellerOrgId::equals).orElse(false)) {
            throw new ForbiddenActionException("offers.not-your-organisation",
                    "You can only ask about your own organisation's offers");
        }
        return offers.hasOpenOffer(sellerOrgId, atin == null ? "" : atin.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    @Transactional(readOnly = true)
    public OfferView get(UUID id) {
        Offer offer = offers.findById(id).orElseThrow(() -> notFound(id));
        access.requireOwned(OffersPermissions.READ, OffersRelations.CAN_VIEW, OffersRelations.offer(offer.id()),
                offer.sellerOrgId());
        return evaluator.view(offer);
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<OfferView> list(String sellerOrgId, String atin, OfferStatus status, int page, int size) {
        Actor actor = permissions.require(OffersPermissions.READ);
        String org = Actors.isStaff(actor) ? sellerOrgId : actor.orgId().orElseThrow(() ->
                new OfferRuleViolation("Your account is not linked to a seller organisation"));
        if (org != null && !org.isBlank()) {
            access.requireForOrg(OffersPermissions.READ, OffersRelations.ORG_VIEW_PRODUCTS, org);
        } else {
            access.require(OffersPermissions.READ, OffersRelations.PLATFORM_VIEW_ALL, FgaObject.PLATFORM);
        }
        OfferPage result = offers.search(new OfferQuery(org, atin == null ? null : atin.toUpperCase(Locale.ROOT),
                status, Math.max(page, 0), Math.clamp(size, 1, 100)));
        return new PageView<>(evaluator.views(result.items()), result.total(), result.page(), result.size());
    }

    /**
     * Applies a change with optimistic locking (S259): when the caller names the version it edited (If-Match or
     * {@code version}), a newer stored version is a 409 {@code offers.version-mismatch}; the update itself is
     * conditional on the version read here, so concurrent writers get 409 {@code offers.concurrent-update}.
     */
    private OfferView change(UUID id, Long expectedVersion, UnaryOperator<Offer> change, String eventType) {
        Offer current = offers.findById(id).orElseThrow(() -> notFound(id));
        access.requireOwned(OffersPermissions.WRITE, OffersRelations.CAN_EDIT, OffersRelations.offer(current.id()),
                current.sellerOrgId());
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw new ConflictException("offers.version-mismatch", "Offer " + id + " is at version "
                    + current.version() + ", not " + expectedVersion + "; reload it and apply your change again");
        }
        Offer updated = offers.update(change.apply(current));
        publish(eventType, updated);
        buyBox.recompute(List.of(updated.atin()), eventType);
        return evaluator.view(updated);
    }

    private String sellerOrg(Actor actor, String requested) {
        if (Actors.isStaff(actor)) {
            if (requested != null && !requested.isBlank()) {
                return requested.trim();
            }
            return actor.orgId().orElseThrow(() -> new OfferRuleViolation("Staff must name the seller organisation",
                    Map.of("sellerOrgId", "Required")));
        }
        return actor.orgId().orElseThrow(() -> new OfferRuleViolation(
                "Your account is not linked to a seller organisation"));
    }

    private void validateCreate(CreateOfferCommand cmd) {
        Map<String, String> errors = new LinkedHashMap<>();
        boolean hasAtin = cmd.atin() != null && !cmd.atin().isBlank();
        boolean hasGtin = cmd.gtin() != null && !cmd.gtin().isBlank();
        if (hasAtin == hasGtin) {
            errors.put("atin", "Give the product's ATIN or its GTIN (exactly one)");
        }
        if (cmd.sellerSku() == null || cmd.sellerSku().isBlank()) {
            errors.put("sellerSku", "Required");
        }
        if (cmd.fulfilment() == null) {
            errors.put("fulfilment", "Required");
        } else if (cmd.fulfilment() == Fulfilment.SELLER) {
            if (cmd.quantity() == null) {
                errors.put("quantity", "Enter how many you have");
            }
        } else if (cmd.fulfilment() == Fulfilment.AFROTRANSACT_DELIVERY) {
            if (cmd.quantity() == null) {
                errors.put("quantity", "Enter how many you have");
            }
            if (cmd.shipFrom() == null || cmd.shipFrom().locationId() == null
                    || cmd.shipFrom().locationId().isBlank()) {
                errors.put(LOCATION_FIELD, "Choose the location AfroTransact collects from");
            }
        }
        if (!errors.isEmpty()) {
            throw new OfferRuleViolation("The offer is not valid", errors);
        }
    }

    /**
     * The ship-from of an AFROTRANSACT_DELIVERY offer: one of the seller's own locations with AfroTransact pickup
     * enabled (seller-service); city, region, country and ZIP come from the location.
     */
    private ShipFrom pickupShipFrom(String org, ShipFrom requested) {
        String locationId = requested == null || requested.locationId() == null ? "" : requested.locationId().trim();
        if (locationId.isEmpty()) {
            throw new OfferRuleViolation("The offer is not valid", Map.of(LOCATION_FIELD,
                    "Choose the location AfroTransact collects from"));
        }
        PickupLocation l = pickupLocations.find(locationId).filter(p -> org.equals(p.orgId()))
                .orElseThrow(() -> new OfferRuleViolation("The offer is not valid", Map.of(LOCATION_FIELD,
                        "That is not one of your locations")));
        if (!l.pickupEnabled()) {
            throw new OfferRuleViolation("The offer is not valid", Map.of(LOCATION_FIELD,
                    "Turn on Fulfilled by AfroTransact at this location first"));
        }
        return new ShipFrom(l.city(), l.region(), l.country() == null ? "US" : l.country(), l.postalCode(),
                l.id() == null ? locationId : l.id());
    }

    /**
     * The pick-up place of a {@code SELLER} offer (the customer collects): one of the seller's own locations, named by id
     * (the Seller Center form and the gateway send only {@code locationId}); its city, region, country and ZIP come from
     * the location. A full address with a country and no id is still accepted from the other writers (catalog listings).
     * What is missing is named: no choice, a location that is not the seller's, or a location without an address.
     */
    private ShipFrom sellerShipFrom(String org, ShipFrom requested) {
        String locationId = requested == null || requested.locationId() == null ? "" : requested.locationId().trim();
        if (locationId.isEmpty()) {
            if (requested != null && requested.country() != null && !requested.country().isBlank()) {
                return requested;
            }
            throw new OfferRuleViolation("The offer is not valid", Map.of(LOCATION_FIELD,
                    "Choose where customers collect it"));
        }
        PickupLocation l = pickupLocations.find(locationId).filter(p -> org.equals(p.orgId()))
                .orElseThrow(() -> new OfferRuleViolation("The offer is not valid", Map.of(LOCATION_FIELD,
                        "That is not one of your locations")));
        if (l.city() == null || l.city().isBlank()) {
            throw new OfferRuleViolation("The offer is not valid", Map.of(LOCATION_FIELD,
                    "Add the city to this location's address"));
        }
        return new ShipFrom(l.city(), l.region(), l.country() == null || l.country().isBlank() ? "US" : l.country(),
                l.postalCode(), l.id() == null ? locationId : l.id());
    }

    /** A stored place is kept as it is: it only has to be there (a country), not looked up again. */
    private static Offer.Terms requireKept(Offer.Terms t) {
        ShipFrom stored = t.shipFrom();
        if (stored == null || stored.country() == null || stored.country().isBlank()) {
            throw new OfferRuleViolation("The offer is not valid", Map.of(LOCATION_FIELD,
                    "Choose where customers collect it"));
        }
        return t;
    }

    private static Offer.Terms withShipFrom(Offer.Terms t, ShipFrom shipFrom) {
        return new Offer.Terms(t.condition(), t.priceMinor(), t.listPriceMinor(), t.shippingMinor(), t.fulfilment(),
                t.sellerQuantity(), t.handlingDays(), shipFrom, t.inventorySku());
    }

    private CatalogVariant resolve(CreateOfferCommand cmd) {
        if (cmd.atin() != null && !cmd.atin().isBlank()) {
            String atin = cmd.atin().trim().toUpperCase(Locale.ROOT);
            return catalog.liveVariant(atin).orElseThrow(() -> new NotFoundException("offers.product-not-found",
                    "No live product variant with ATIN " + atin));
        }
        String gtin = cmd.gtin().replaceAll("[^0-9]", "");
        return catalog.liveVariantByGtin(gtin).orElseThrow(() -> new NotFoundException("offers.product-not-found",
                "No live product page carries GTIN " + gtin + "; create the product in the catalog first"));
    }

    private static String channel(boolean fulfilledByAfroTransact) {
        return fulfilledByAfroTransact ? "Fulfilled by AfroTransact" : "seller-fulfilled";
    }

    private SellerKind kindOf(String org) {
        return org.equals(settings.retailOrgId()) ? SellerKind.PLATFORM : SellerKind.SELLER;
    }

    private static String currency(String c) {
        return c == null || c.isBlank() ? "USD" : c.trim().toUpperCase(Locale.ROOT);
    }

    private void publish(String type, Offer o) {
        events.publish(type, OffersEvents.AGGREGATE, o.atin(), OfferEventPayload.of(o));
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("offers.offer-not-found", "Offer " + id + " not found");
    }
}
