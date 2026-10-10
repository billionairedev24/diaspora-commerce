package com.afrotransact.offers.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Default buy box rules (documented in the service README, "Buy box").
 *
 * <ol>
 *   <li><b>Eligibility.</b> An offer is listed only when it is active, its product is live, it has stock and its
 *   seller is neither restricted nor hidden by enforcement. A listed offer competes for the buy box only when it is
 *   in NEW condition and its seller meets the health thresholds (defect rate, on-time rate).</li>
 *   <li><b>Score</b> (default 1000 points): landed price (price + shipping) relative to the lowest eligible landed
 *   price, a bonus for Fulfilled by AfroTransact, delivery speed relative to the fastest eligible estimate,
 *   seller health and stock depth.</li>
 *   <li><b>Ties</b> are broken by lower landed price, then Fulfilled by AfroTransact, then faster delivery, then the
 *   older offer, then the offer id, so the result never depends on input order.</li>
 * </ol>
 * AfroTransact Retail (first party) is scored exactly like any other seller.
 */
public class DefaultBuyBoxPolicy implements BuyBoxPolicy {

    private static final double HEALTH_RATING_WEIGHT = 0.4;
    private static final double HEALTH_ON_TIME_WEIGHT = 0.3;
    private static final double HEALTH_DEFECT_WEIGHT = 0.3;
    private static final double DEFAULT_ON_TIME = 0.95;

    private static final Comparator<Scored> RANKING = Comparator
            .comparingDouble((Scored s) -> s.score).reversed()
            .thenComparingLong(s -> s.candidate.landedPriceMinor())
            .thenComparing(s -> s.candidate.fulfilment() != Fulfilment.AFROTRANSACT)
            .thenComparingInt(s -> s.candidate.deliveryDays())
            .thenComparing(s -> s.candidate.createdAt())
            .thenComparing(s -> s.candidate.offerId());

    private final BuyBoxWeights weights;

    public DefaultBuyBoxPolicy(BuyBoxWeights weights) {
        this.weights = weights;
    }

    @Override
    public BuyBoxDecision decide(List<BuyBoxCandidate> candidates) {
        List<BuyBoxCandidate> eligible = new ArrayList<>();
        List<OfferEvaluation> listedOnly = new ArrayList<>();
        List<OfferEvaluation> unlisted = new ArrayList<>();
        for (BuyBoxCandidate c : candidates) {
            List<Ineligibility> reasons = ineligibility(c);
            if (reasons.isEmpty()) {
                eligible.add(c);
            } else if (reasons.stream().noneMatch(Ineligibility::unlisted)) {
                listedOnly.add(new OfferEvaluation(c, true, false, reasons, 0, List.of(), 0));
            } else {
                unlisted.add(new OfferEvaluation(c, false, false, reasons, 0, List.of(), 0));
            }
        }
        Comparator<OfferEvaluation> byLanded = Comparator
                .comparingLong((OfferEvaluation e) -> e.candidate().landedPriceMinor())
                .thenComparing(e -> e.candidate().offerId());
        listedOnly.sort(byLanded);
        unlisted.sort(byLanded);

        List<Scored> scored = score(eligible);
        scored.sort(RANKING);
        List<OfferEvaluation> evaluations = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            Scored s = scored.get(i);
            evaluations.add(new OfferEvaluation(s.candidate, true, true, List.of(), s.score, s.components, i + 1));
        }
        evaluations.addAll(listedOnly);
        evaluations.addAll(unlisted);
        if (scored.isEmpty()) {
            return new BuyBoxDecision(null, evaluations, List.of());
        }
        return new BuyBoxDecision(scored.getFirst().candidate.offerId(), evaluations, reasons(scored));
    }

    private List<Ineligibility> ineligibility(BuyBoxCandidate c) {
        List<Ineligibility> out = new ArrayList<>();
        if (!c.active()) {
            out.add(Ineligibility.NOT_ACTIVE);
        }
        if (!c.productLive()) {
            out.add(Ineligibility.PRODUCT_NOT_LIVE);
        }
        if (c.availableQty() <= 0) {
            out.add(Ineligibility.OUT_OF_STOCK);
        }
        if (c.standing().listingsHidden()) {
            out.add(Ineligibility.SELLER_LISTINGS_HIDDEN);
        }
        if (c.standing().restricted()) {
            out.add(Ineligibility.SELLER_RESTRICTED);
        }
        if (c.condition() != OfferCondition.NEW) {
            out.add(Ineligibility.CONDITION_NOT_NEW);
        }
        if (belowHealthThreshold(c.standing())) {
            out.add(Ineligibility.SELLER_HEALTH_BELOW_THRESHOLD);
        }
        return out;
    }

    private boolean belowHealthThreshold(SellerStanding s) {
        return (s.defectRate() != null && s.defectRate() > weights.maxDefectRate())
                || (s.onTimeRate() != null && s.onTimeRate() < weights.minOnTimeRate());
    }

    /** Seller health 0–1 from rating, on-time rate and defect rate; unknown inputs use neutral defaults. */
    double health(SellerStanding s) {
        double rating = s.rating() == null ? weights.newSellerRating() : s.rating();
        double onTime = s.onTimeRate() == null ? DEFAULT_ON_TIME : s.onTimeRate();
        double defect = s.defectRate() == null ? 0 : s.defectRate();
        double defectScore = 1 - Math.min(1, defect / weights.maxDefectRate());
        return HEALTH_RATING_WEIGHT * (rating / 5) + HEALTH_ON_TIME_WEIGHT * onTime
                + HEALTH_DEFECT_WEIGHT * defectScore;
    }

    private List<Scored> score(List<BuyBoxCandidate> eligible) {
        List<Scored> out = new ArrayList<>();
        if (eligible.isEmpty()) {
            return out;
        }
        long lowest = eligible.stream().mapToLong(BuyBoxCandidate::landedPriceMinor).min().orElseThrow();
        int fastest = eligible.stream().mapToInt(c -> Math.max(1, c.deliveryDays())).min().orElseThrow();
        for (BuyBoxCandidate c : eligible) {
            List<ScoreComponent> parts = new ArrayList<>();
            long landed = c.landedPriceMinor();
            double price = landed <= 0 ? weights.pricePoints() : weights.pricePoints() * lowest / landed;
            parts.add(new ScoreComponent(BuyBoxFactor.LANDED_PRICE, round(price), weights.pricePoints(),
                    "Landed price " + money(landed, c.currency()) + " (price " + money(c.priceMinor(), c.currency())
                            + " + shipping " + money(c.shippingMinor(), c.currency()) + "); lowest "
                            + money(lowest, c.currency())));
            boolean fba = c.fulfilment() == Fulfilment.AFROTRANSACT;
            parts.add(new ScoreComponent(BuyBoxFactor.FULFILMENT, fba ? weights.fulfilmentPoints() : 0,
                    weights.fulfilmentPoints(), fba ? "Fulfilled by AfroTransact" : "Ships from seller"));
            int days = Math.max(1, c.deliveryDays());
            parts.add(new ScoreComponent(BuyBoxFactor.DELIVERY_SPEED,
                    round(weights.deliveryPoints() * fastest / days), weights.deliveryPoints(),
                    "Delivery estimate " + c.deliveryDays() + " day(s); fastest " + fastest));
            double health = health(c.standing());
            parts.add(new ScoreComponent(BuyBoxFactor.SELLER_PERFORMANCE, round(weights.performancePoints() * health),
                    weights.performancePoints(), "Seller health " + String.format(Locale.ROOT, "%.2f", health)
                            + (c.standing().rating() == null ? " (new seller, neutral rating)" : "")));
            double depth = Math.min(1, (double) c.availableQty() / weights.fullStockUnits());
            parts.add(new ScoreComponent(BuyBoxFactor.STOCK_DEPTH, round(weights.stockPoints() * depth),
                    weights.stockPoints(), c.availableQty() + " unit(s) available"));
            double total = round(parts.stream().mapToDouble(ScoreComponent::points).sum());
            out.add(new Scored(c, total, parts, lowest, fastest));
        }
        return out;
    }

    private List<BuyBoxReason> reasons(List<Scored> ranked) {
        Scored w = ranked.getFirst();
        BuyBoxCandidate c = w.candidate;
        List<BuyBoxReason> out = new ArrayList<>();
        out.add(new BuyBoxReason("HIGHEST_SCORE", "Highest buy box score " + fmt(w.score) + " of "
                + ranked.size() + " eligible offer(s)"));
        if (ranked.size() == 1) {
            out.add(new BuyBoxReason("ONLY_ELIGIBLE_OFFER", "Only offer eligible for the buy box"));
        }
        if (c.landedPriceMinor() == w.lowest) {
            out.add(new BuyBoxReason("LOWEST_LANDED_PRICE", "Lowest landed price "
                    + money(c.landedPriceMinor(), c.currency())));
        } else {
            out.add(new BuyBoxReason("COMPETITIVE_LANDED_PRICE", "Landed price "
                    + money(c.landedPriceMinor(), c.currency()) + " vs lowest " + money(w.lowest, c.currency())
                    + ", outweighed by other factors"));
        }
        if (c.fulfilment() == Fulfilment.AFROTRANSACT) {
            out.add(new BuyBoxReason("FULFILLED_BY_AFROTRANSACT", "Fulfilled by AfroTransact"));
        }
        if (Math.max(1, c.deliveryDays()) == w.fastest) {
            out.add(new BuyBoxReason("FASTEST_DELIVERY", "Fastest delivery estimate (" + c.deliveryDays()
                    + " day(s))"));
        }
        out.add(new BuyBoxReason("SELLER_HEALTH", "Seller health " + String.format(Locale.ROOT, "%.2f",
                health(c.standing()))));
        if (ranked.size() > 1 && Double.compare(ranked.get(1).score, w.score) == 0) {
            out.add(new BuyBoxReason("TIE_BREAK", "Tied on score with offer " + ranked.get(1).candidate.offerId()
                    + "; won on " + tieBreak(w.candidate, ranked.get(1).candidate)));
        }
        return out;
    }

    private static String tieBreak(BuyBoxCandidate a, BuyBoxCandidate b) {
        if (a.landedPriceMinor() != b.landedPriceMinor()) {
            return "lower landed price";
        }
        if ((a.fulfilment() == Fulfilment.AFROTRANSACT) != (b.fulfilment() == Fulfilment.AFROTRANSACT)) {
            return "Fulfilled by AfroTransact";
        }
        if (a.deliveryDays() != b.deliveryDays()) {
            return "faster delivery";
        }
        if (!a.createdAt().equals(b.createdAt())) {
            return "older offer";
        }
        return "offer id order";
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static String money(long minor, String currency) {
        int digits = 2;
        if (currency != null) {
            try {
                digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
            } catch (IllegalArgumentException ex) {
                digits = 2;
            }
        }
        return BigDecimal.valueOf(minor).movePointLeft(digits).toPlainString() + " " + currency;
    }

    private static final class Scored {
        private final BuyBoxCandidate candidate;
        private final double score;
        private final List<ScoreComponent> components;
        private final long lowest;
        private final int fastest;

        Scored(BuyBoxCandidate candidate, double score, List<ScoreComponent> components, long lowest, int fastest) {
            this.candidate = candidate;
            this.score = score;
            this.components = components;
            this.lowest = lowest;
            this.fastest = fastest;
        }
    }
}
