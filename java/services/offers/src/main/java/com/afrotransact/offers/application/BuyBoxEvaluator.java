package com.afrotransact.offers.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.offers.domain.BuyBoxCandidate;
import com.afrotransact.offers.domain.BuyBoxDecision;
import com.afrotransact.offers.domain.BuyBoxPolicy;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Ineligibility;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferEvaluation;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.SellerStanding;
import com.afrotransact.offers.domain.StockStatus;
import com.afrotransact.offers.port.SellerStandingRepository;
import com.afrotransact.offers.port.StockRepository;
import com.afrotransact.offers.port.StockRepository.StockKey;
import org.springframework.stereotype.Component;

/** Resolves offers into views (stock, seller standing, delivery estimate) and runs the buy box policy on them. */
@Component
class BuyBoxEvaluator {

    static final String RETAIL_NAME = "AfroTransact";

    private final StockRepository stock;
    private final SellerStandingRepository standings;
    private final BuyBoxPolicy policy;
    private final OffersSettings settings;

    BuyBoxEvaluator(StockRepository stock, SellerStandingRepository standings, BuyBoxPolicy policy,
                    OffersSettings settings) {
        this.stock = stock;
        this.standings = standings;
        this.policy = policy;
        this.settings = settings;
    }

    /** Views of the given offers with live availability and seller standing. */
    List<OfferView> views(Collection<Offer> offers) {
        Set<StockKey> keys = new HashSet<>();
        Set<String> orgs = new HashSet<>();
        for (Offer o : offers) {
            orgs.add(o.sellerOrgId());
            if (o.fulfilment() == Fulfilment.AFROTRANSACT) {
                keys.add(new StockKey(o.sellerOrgId(), o.inventorySku()));
            }
        }
        Map<StockKey, Integer> available = keys.isEmpty() ? Map.of() : stock.available(keys);
        Map<String, SellerStanding> byOrg = orgs.isEmpty() ? Map.of() : standings.findAll(orgs);
        List<OfferView> out = new ArrayList<>();
        for (Offer o : offers) {
            SellerStanding s = byOrg.getOrDefault(o.sellerOrgId(), SellerStanding.unknown(o.sellerOrgId()));
            int qty = !o.fulfilment().sellerStock()
                    ? available.getOrDefault(new StockKey(o.sellerOrgId(), o.inventorySku()), 0)
                    : o.sellerQuantity();
            out.add(new OfferView(o, sellerName(o, s), qty, StockStatus.of(qty, settings.lowStockThreshold()),
                    deliveryDays(o), s.listingsHidden() || s.restricted()));
        }
        return out;
    }

    OfferView view(Offer offer) {
        return views(List.of(offer)).getFirst();
    }

    /** Runs the policy on the views of one ATIN in one currency. */
    Evaluated decide(String atin, String currency, List<OfferView> views) {
        Map<UUID, OfferView> byId = new LinkedHashMap<>();
        List<String> orgs = new ArrayList<>();
        for (OfferView v : views) {
            if (v.offer().atin().equals(atin) && v.offer().currency().equals(currency)
                    && v.offer().status() != OfferStatus.WITHDRAWN) {
                byId.put(v.offer().id(), v);
                orgs.add(v.offer().sellerOrgId());
            }
        }
        Map<String, SellerStanding> byOrg = orgs.isEmpty() ? Map.of() : standings.findAll(orgs);
        List<BuyBoxCandidate> candidates = new ArrayList<>();
        for (OfferView v : byId.values()) {
            Offer o = v.offer();
            candidates.add(new BuyBoxCandidate(o.id(), o.sellerOrgId(), o.sellerKind(), o.condition(), o.priceMinor(),
                    o.shippingMinor(), o.currency(), o.fulfilment(), v.availableQty(), v.deliveryDays(),
                    o.status() == OfferStatus.ACTIVE, o.productLive(),
                    byOrg.getOrDefault(o.sellerOrgId(), SellerStanding.unknown(o.sellerOrgId())), o.createdAt()));
        }
        return new Evaluated(atin, currency, policy.decide(candidates), byId);
    }

    /** Storefront shape: winner, reasons and the other listed offers with badges. */
    BuyBoxView buyBoxView(Evaluated e) {
        List<OfferEvaluation> listed = e.decision().listed();
        long minLanded = listed.stream().mapToLong(x -> x.candidate().landedPriceMinor()).min().orElse(-1);
        long minPrice = listed.stream().mapToLong(x -> x.candidate().priceMinor()).min().orElse(-1);
        int fastest = listed.stream().mapToInt(x -> x.candidate().deliveryDays()).min().orElse(-1);
        ListedOffer winner = null;
        List<ListedOffer> others = new ArrayList<>();
        for (OfferEvaluation ev : listed) {
            ListedOffer lo = listedOffer(ev, e.views().get(ev.candidate().offerId()), minLanded, fastest);
            if (ev.rank() == 1) {
                winner = lo;
            } else {
                others.add(lo);
            }
        }
        return new BuyBoxView(e.atin(), e.currency(), winner, e.decision().reasons(), others, listed.size(),
                minLanded < 0 ? null : minLanded, minPrice < 0 ? null : minPrice);
    }

    /** One offer with its rank, badges and reasons (including reasons that unlist it). */
    ListedOffer listedOffer(Evaluated e, UUID offerId) {
        List<OfferEvaluation> listed = e.decision().listed();
        long minLanded = listed.stream().mapToLong(x -> x.candidate().landedPriceMinor()).min().orElse(-1);
        int fastest = listed.stream().mapToInt(x -> x.candidate().deliveryDays()).min().orElse(-1);
        OfferEvaluation ev = e.decision().evaluations().stream()
                .filter(x -> x.candidate().offerId().equals(offerId)).findFirst().orElseThrow();
        return listedOffer(ev, e.views().get(offerId), minLanded, fastest);
    }

    private ListedOffer listedOffer(OfferEvaluation ev, OfferView v, long minLanded, int fastest) {
        List<OfferBadge> badges = new ArrayList<>();
        Offer o = v.offer();
        if (ev.rank() == 1) {
            badges.add(OfferBadge.BUY_BOX_WINNER);
        }
        if (ev.listable() && o.landedPriceMinor() == minLanded) {
            badges.add(OfferBadge.LOWEST_PRICE);
        }
        if (ev.listable() && v.deliveryDays() == fastest) {
            badges.add(OfferBadge.FASTEST_DELIVERY);
        }
        badges.add(switch (o.fulfilment()) {
            case AFROTRANSACT -> OfferBadge.FULFILLED_BY_AFROTRANSACT;
            case AFROTRANSACT_DELIVERY -> OfferBadge.AFROTRANSACT_DELIVERY;
            case SELLER -> OfferBadge.SHIPS_FROM_SELLER;
        });
        if (o.sellerKind() == SellerKind.PLATFORM) {
            badges.add(OfferBadge.SOLD_BY_AFROTRANSACT);
        }
        if (v.stockStatus() == StockStatus.LOW_STOCK) {
            badges.add(OfferBadge.LOW_STOCK);
        }
        if (o.condition() == OfferCondition.RENEWED) {
            badges.add(OfferBadge.RENEWED);
        } else if (o.condition() != OfferCondition.NEW) {
            badges.add(OfferBadge.USED);
        }
        List<Ineligibility> reasons = ev.ineligibility();
        return new ListedOffer(v, o.landedPriceMinor(), ev.rank(), ev.score(), badges, reasons);
    }

    int deliveryDays(Offer o) {
        return o.handlingDays() + switch (o.fulfilment()) {
            case AFROTRANSACT -> settings.afrotransactTransitDays();
            case AFROTRANSACT_DELIVERY -> settings.afrotransactDeliveryTransitDays();
            case SELLER -> settings.sellerTransitDays();
        };
    }

    private String sellerName(Offer o, SellerStanding s) {
        if (s.displayName() != null && !s.displayName().isBlank()) {
            return s.displayName();
        }
        return o.sellerKind() == SellerKind.PLATFORM ? RETAIL_NAME : null;
    }

    /** Policy decision for one ATIN and currency together with the views it was computed from. */
    record Evaluated(String atin, String currency, BuyBoxDecision decision, Map<UUID, OfferView> views) {
    }
}
