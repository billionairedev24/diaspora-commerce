package com.afrotransact.offers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultBuyBoxPolicyTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private final DefaultBuyBoxPolicy policy = new DefaultBuyBoxPolicy(BuyBoxWeights.defaults());

    @Test
    void lowestLandedPriceWinsWhenEverythingElseIsEqual() {
        BuyBoxCandidate cheap = offer(1, 899, 0, Fulfilment.SELLER, 20, 6);
        BuyBoxCandidate dear = offer(2, 999, 0, Fulfilment.SELLER, 20, 6);

        BuyBoxDecision d = policy.decide(List.of(dear, cheap));

        assertThat(d.winnerOfferId()).isEqualTo(cheap.offerId());
        assertThat(d.reasons()).extracting(BuyBoxReason::code).contains("HIGHEST_SCORE", "LOWEST_LANDED_PRICE");
        assertThat(d.winner().orElseThrow().components()).extracting(ScoreComponent::factor)
                .containsExactly(BuyBoxFactor.LANDED_PRICE, BuyBoxFactor.FULFILMENT, BuyBoxFactor.DELIVERY_SPEED,
                        BuyBoxFactor.SELLER_PERFORMANCE, BuyBoxFactor.STOCK_DEPTH);
    }

    @Test
    void landedPriceIncludesShipping() {
        BuyBoxCandidate lowPriceHighShipping = offer(1, 800, 599, Fulfilment.SELLER, 20, 6);
        BuyBoxCandidate higherPriceFreeShipping = offer(2, 999, 0, Fulfilment.SELLER, 20, 6);

        BuyBoxDecision d = policy.decide(List.of(lowPriceHighShipping, higherPriceFreeShipping));

        assertThat(d.winnerOfferId()).isEqualTo(higherPriceFreeShipping.offerId());
    }

    @Test
    void fulfilledByAfroTransactAndFasterDeliveryBeatASlightlyCheaperSellerOffer() {
        BuyBoxCandidate seller = offer(1, 949, 0, Fulfilment.SELLER, 20, 6);
        BuyBoxCandidate fba = offer(2, 999, 0, Fulfilment.AFROTRANSACT, 20, 2);

        BuyBoxDecision d = policy.decide(List.of(seller, fba));

        assertThat(d.winnerOfferId()).isEqualTo(fba.offerId());
        assertThat(d.reasons()).extracting(BuyBoxReason::code)
                .contains("COMPETITIVE_LANDED_PRICE", "FULFILLED_BY_AFROTRANSACT", "FASTEST_DELIVERY");
    }

    @Test
    void muchCheaperSellerOfferStillBeatsFulfilledByAfroTransact() {
        BuyBoxCandidate seller = offer(1, 500, 0, Fulfilment.SELLER, 20, 6);
        BuyBoxCandidate fba = offer(2, 999, 0, Fulfilment.AFROTRANSACT, 20, 2);

        assertThat(policy.decide(List.of(fba, seller)).winnerOfferId()).isEqualTo(seller.offerId());
    }

    @Test
    void ineligibleOffersNeverWinEvenWhenCheapest() {
        BuyBoxCandidate normal = offer(1, 999, 0, Fulfilment.SELLER, 20, 6);
        BuyBoxCandidate outOfStock = offer(2, 100, 0, Fulfilment.AFROTRANSACT, 0, 2);
        BuyBoxCandidate restricted = withStanding(offer(3, 100, 0, Fulfilment.AFROTRANSACT, 20, 2),
                new SellerStanding("org_c", null, 5.0, 1.0, 0.0, true, false, T0));
        BuyBoxCandidate hidden = withStanding(offer(4, 100, 0, Fulfilment.AFROTRANSACT, 20, 2),
                new SellerStanding("org_d", null, 5.0, 1.0, 0.0, false, true, T0));
        BuyBoxCandidate paused = new BuyBoxCandidate(id(5), "org_e", SellerKind.SELLER, OfferCondition.NEW, 100, 0,
                "USD", Fulfilment.AFROTRANSACT, 20, 2, false, true, SellerStanding.unknown("org_e"), T0);

        BuyBoxDecision d = policy.decide(List.of(outOfStock, restricted, hidden, paused, normal));

        assertThat(d.winnerOfferId()).isEqualTo(normal.offerId());
        assertThat(d.listed()).extracting(e -> e.candidate().offerId()).containsExactly(normal.offerId());
        assertThat(eval(d, outOfStock).ineligibility()).containsExactly(Ineligibility.OUT_OF_STOCK);
        assertThat(eval(d, restricted).ineligibility()).containsExactly(Ineligibility.SELLER_RESTRICTED);
        assertThat(eval(d, hidden).ineligibility()).containsExactly(Ineligibility.SELLER_LISTINGS_HIDDEN);
        assertThat(eval(d, paused).ineligibility()).containsExactly(Ineligibility.NOT_ACTIVE);
    }

    @Test
    void usedOffersAndUnhealthySellersAreListedButCannotWin() {
        BuyBoxCandidate used = new BuyBoxCandidate(id(1), "org_a", SellerKind.SELLER, OfferCondition.USED_GOOD, 300,
                0, "USD", Fulfilment.SELLER, 5, 6, true, true, SellerStanding.unknown("org_a"), T0);
        BuyBoxCandidate unhealthy = withStanding(offer(2, 400, 0, Fulfilment.SELLER, 5, 6),
                new SellerStanding("org_b", null, 4.8, 0.99, 0.05, false, false, T0));
        BuyBoxCandidate fine = offer(3, 999, 0, Fulfilment.SELLER, 5, 6);

        BuyBoxDecision d = policy.decide(List.of(used, unhealthy, fine));

        assertThat(d.winnerOfferId()).isEqualTo(fine.offerId());
        assertThat(eval(d, used).listable()).isTrue();
        assertThat(eval(d, used).ineligibility()).containsExactly(Ineligibility.CONDITION_NOT_NEW);
        assertThat(eval(d, unhealthy).ineligibility()).containsExactly(Ineligibility.SELLER_HEALTH_BELOW_THRESHOLD);
        assertThat(d.listed()).extracting(e -> e.candidate().offerId())
                .containsExactly(used.offerId(), unhealthy.offerId(), fine.offerId());
    }

    @Test
    void noEligibleOfferMeansNoWinner() {
        BuyBoxDecision d = policy.decide(List.of(offer(1, 999, 0, Fulfilment.SELLER, 0, 6)));

        assertThat(d.winnerOfferId()).isNull();
        assertThat(d.winner()).isEmpty();
        assertThat(d.reasons()).isEmpty();
        assertThat(policy.decide(List.of()).winnerOfferId()).isNull();
    }

    @Test
    void tiesAreBrokenDeterministicallyRegardlessOfInputOrder() {
        BuyBoxCandidate older = new BuyBoxCandidate(id(7), "org_x", SellerKind.SELLER, OfferCondition.NEW, 999, 0,
                "USD", Fulfilment.SELLER, 20, 6, true, true, SellerStanding.unknown("org_x"), T0);
        BuyBoxCandidate newer = new BuyBoxCandidate(id(3), "org_y", SellerKind.SELLER, OfferCondition.NEW, 999, 0,
                "USD", Fulfilment.SELLER, 20, 6, true, true, SellerStanding.unknown("org_y"), T0.plusSeconds(60));
        BuyBoxCandidate sameTimeLowerId = new BuyBoxCandidate(id(1), "org_z", SellerKind.SELLER, OfferCondition.NEW,
                999, 0, "USD", Fulfilment.SELLER, 20, 6, true, true, SellerStanding.unknown("org_z"),
                T0.plusSeconds(60));

        List<BuyBoxCandidate> input = new ArrayList<>(List.of(older, newer, sameTimeLowerId));
        for (int i = 0; i < 5; i++) {
            Collections.shuffle(input);
            BuyBoxDecision d = policy.decide(input);
            assertThat(d.winnerOfferId()).isEqualTo(older.offerId());
            assertThat(d.evaluations()).extracting(OfferEvaluation::rank).containsExactly(1, 2, 3);
            assertThat(d.evaluations()).extracting(e -> e.candidate().offerId())
                    .containsExactly(older.offerId(), sameTimeLowerId.offerId(), newer.offerId());
        }
        BuyBoxDecision d = policy.decide(input);
        assertThat(d.reasons()).filteredOn(r -> r.code().equals("TIE_BREAK")).singleElement()
                .satisfies(r -> assertThat(r.message()).contains("older offer"));
    }

    @Test
    void firstPartyCompetesUnderTheSameRules() {
        BuyBoxCandidate retail = new BuyBoxCandidate(id(1), "org_retail", SellerKind.PLATFORM, OfferCondition.NEW,
                1099, 0, "USD", Fulfilment.AFROTRANSACT, 50, 2, true, true, SellerStanding.unknown("org_retail"), T0);
        BuyBoxCandidate seller = new BuyBoxCandidate(id(2), "org_seller", SellerKind.SELLER, OfferCondition.NEW,
                1099, 0, "USD", Fulfilment.AFROTRANSACT, 50, 2, true, true, SellerStanding.unknown("org_seller"),
                T0.minusSeconds(1));

        BuyBoxDecision d = policy.decide(List.of(retail, seller));

        assertThat(eval(d, retail).score()).isEqualTo(eval(d, seller).score());
        assertThat(d.winnerOfferId()).isEqualTo(seller.offerId());
    }

    @Test
    void healthUsesNeutralDefaultsForNewSellers() {
        double fresh = policy.health(SellerStanding.unknown("org_new"));
        double great = policy.health(new SellerStanding("org_g", null, 5.0, 1.0, 0.0, false, false, T0));
        double poor = policy.health(new SellerStanding("org_p", null, 2.0, 0.85, 0.019, false, false, T0));

        assertThat(fresh).isBetween(0.9, 0.95);
        assertThat(great).isEqualTo(1.0);
        assertThat(poor).isLessThan(fresh);
    }

    private static OfferEvaluation eval(BuyBoxDecision d, BuyBoxCandidate c) {
        return d.evaluations().stream().filter(e -> e.candidate().offerId().equals(c.offerId())).findFirst()
                .orElseThrow();
    }

    private static BuyBoxCandidate offer(int n, long price, long shipping, Fulfilment f, int qty, int days) {
        String org = "org_" + n;
        return new BuyBoxCandidate(id(n), org, SellerKind.SELLER, OfferCondition.NEW, price, shipping, "USD", f, qty,
                days, true, true, SellerStanding.unknown(org), T0);
    }

    private static BuyBoxCandidate withStanding(BuyBoxCandidate c, SellerStanding s) {
        return new BuyBoxCandidate(c.offerId(), s.orgId(), c.sellerKind(), c.condition(), c.priceMinor(),
                c.shippingMinor(), c.currency(), c.fulfilment(), c.availableQty(), c.deliveryDays(), c.active(),
                c.productLive(), s, c.createdAt());
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }
}
