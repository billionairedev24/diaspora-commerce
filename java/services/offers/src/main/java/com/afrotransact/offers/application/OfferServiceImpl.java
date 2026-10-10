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
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.port.CatalogClient;
import com.afrotransact.offers.port.CatalogClient.CatalogVariant;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.offers.port.OfferRepository.OfferPage;
import com.afrotransact.offers.port.OfferRepository.OfferQuery;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.relationship.FgaObject;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OfferServiceImpl implements OfferService {

    static final int MAX_SELLER_QUANTITY = 99_999;

    private final OfferRepository offers;
    private final CatalogClient catalog;
    private final BuyBoxService buyBox;
    private final BuyBoxEvaluator evaluator;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final DomainEventPublisher events;
    private final OffersSettings settings;
    private final Clock clock;

    OfferServiceImpl(OfferRepository offers, CatalogClient catalog, BuyBoxService buyBox, BuyBoxEvaluator evaluator,
                     PermissionChecker permissions, ResourceAccessChecker access, DomainEventPublisher events,
                     OffersSettings settings, Clock clock) {
        this.offers = offers;
        this.catalog = catalog;
        this.buyBox = buyBox;
        this.evaluator = evaluator;
        this.permissions = permissions;
        this.access = access;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OfferView create(CreateOfferCommand cmd) {
        Actor actor = permissions.require(OffersPermissions.WRITE);
        String org = sellerOrg(actor, cmd.sellerOrgId());
        access.requireForOrg(OffersPermissions.WRITE, OffersRelations.ORG_MANAGE_PRODUCTS, org);
        validateCreate(cmd);

        CatalogVariant variant = resolve(cmd);
        OfferCondition condition = cmd.condition() == null ? OfferCondition.NEW : cmd.condition();
        offers.findOpen(org, variant.variantAtin(), condition).ifPresent(existing -> {
            throw new ConflictException("offers.offer-exists", "You already have a " + condition + " offer on "
                    + variant.variantAtin() + " (" + existing.id() + "); update it instead");
        });
        String sku = cmd.sellerSku().trim().toUpperCase(Locale.ROOT);
        if (offers.sellerSkuTaken(org, sku)) {
            throw new ConflictException("offers.sku-taken",
                    "SKU " + sku + " is already used by another of your offers");
        }
        Instant now = Instant.now(clock);
        String inventorySku = cmd.inventorySku() == null || cmd.inventorySku().isBlank() ? sku
                : cmd.inventorySku().trim().toUpperCase(Locale.ROOT);
        Offer offer = offers.insert(new Offer(UUID.randomUUID(), variant.variantAtin(), variant.productAtin(),
                variant.productId(), variant.gtin(), variant.categoryId(), org, kindOf(org), sku, inventorySku,
                condition, cmd.priceMinor(), cmd.listPriceMinor(), currency(cmd.currency()), cmd.shippingMinor(),
                cmd.fulfilment(), cmd.quantity() == null ? 0 : cmd.quantity(),
                cmd.handlingDays() == null ? settings.defaultHandlingDays() : cmd.handlingDays(), cmd.shipFrom(),
                OfferStatus.ACTIVE, true, now, now, 0));
        publish(OffersEvents.OFFER_CREATED, offer);
        buyBox.recompute(List.of(offer.atin()), OffersEvents.OFFER_CREATED);
        return evaluator.view(offer);
    }

    @Override
    @Transactional
    public OfferView update(UUID id, UpdateOfferCommand cmd) {
        return change(id, o -> {
            Offer.Terms t = o.terms();
            Long listPrice = cmd.listPriceMinor() == null ? t.listPriceMinor()
                    : cmd.listPriceMinor() == 0 ? null : cmd.listPriceMinor();
            Offer.Terms next = new Offer.Terms(
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
            if (next.fulfilment() == Fulfilment.SELLER) {
                requireShipFrom(next.shipFrom(), new LinkedHashMap<>(), true);
            }
            if (next.condition() != t.condition()) {
                offers.findOpen(o.sellerOrgId(), o.atin(), next.condition())
                        .filter(other -> !other.id().equals(o.id()))
                        .ifPresent(other -> {
                            throw new ConflictException("offers.offer-exists", "You already have a "
                                    + next.condition() + " offer on " + o.atin());
                        });
            }
            return o.revise(next, Instant.now(clock));
        }, OffersEvents.OFFER_UPDATED);
    }

    @Override
    @Transactional
    public OfferView pause(UUID id) {
        return change(id, o -> o.pause(Instant.now(clock)), OffersEvents.OFFER_UPDATED);
    }

    @Override
    @Transactional
    public OfferView resume(UUID id) {
        return change(id, o -> o.resume(Instant.now(clock)), OffersEvents.OFFER_UPDATED);
    }

    @Override
    @Transactional
    public OfferView withdraw(UUID id) {
        return change(id, o -> o.withdraw(Instant.now(clock)), OffersEvents.OFFER_WITHDRAWN);
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
        if (owned.stream().anyMatch(o -> o.fulfilment() != Fulfilment.SELLER)) {
            throw new ConflictException("offers.stock-managed-by-inventory", "SKU " + sku
                    + " is Fulfilled by AfroTransact; its stock comes from the fulfilment centre");
        }
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

    private OfferView change(UUID id, UnaryOperator<Offer> change, String eventType) {
        Offer current = offers.findById(id).orElseThrow(() -> notFound(id));
        access.requireOwned(OffersPermissions.WRITE, OffersRelations.CAN_EDIT, OffersRelations.offer(current.id()),
                current.sellerOrgId());
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
                errors.put("quantity", "Required for seller-fulfilled offers");
            }
            requireShipFrom(cmd.shipFrom(), errors, false);
        }
        if (!errors.isEmpty()) {
            throw new OfferRuleViolation("The offer is not valid", errors);
        }
    }

    private static void requireShipFrom(ShipFrom shipFrom, Map<String, String> errors, boolean throwNow) {
        if (shipFrom == null || shipFrom.country() == null || shipFrom.country().isBlank()) {
            errors.put("shipFrom", "Seller-fulfilled offers need a ship-from location");
        }
        if (throwNow && !errors.isEmpty()) {
            throw new OfferRuleViolation("The offer is not valid", errors);
        }
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
