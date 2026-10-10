package com.afrotransact.offers.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.afrotransact.offers.application.CouponCartCommand;
import com.afrotransact.offers.application.CouponQuote;
import com.afrotransact.offers.application.CouponService;
import com.afrotransact.offers.application.CreateCouponCommand;
import com.afrotransact.offers.application.CreateOfferCommand;
import com.afrotransact.offers.application.OfferService;
import com.afrotransact.offers.application.OfferView;
import com.afrotransact.offers.application.RedemptionResult;
import com.afrotransact.offers.domain.Coupon;
import com.afrotransact.offers.domain.CouponFunding;
import com.afrotransact.offers.domain.CouponNotApplicable;
import com.afrotransact.offers.domain.CouponRejection;
import com.afrotransact.offers.domain.CouponScope;
import com.afrotransact.offers.domain.CouponStatus;
import com.afrotransact.offers.domain.CouponType;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.RedemptionStatus;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** S042 against real PostgreSQL: funding, clip-to-apply, limits, idempotent redeem and release. */
class CouponFlowIT extends OffersIntegrationTest {

    private final CouponService coupons;
    private final OfferService offers;

    @Autowired
    CouponFlowIT(JdbcClient jdbc, CouponService coupons, OfferService offers) {
        super(jdbc);
        this.coupons = coupons;
        this.offers = offers;
    }

    @Test
    void platformCouponIsValidatedRedeemedIdempotentlyLimitedAndReleased() {
        OfferView lagos = offer(LAGOS, 2500);
        OfferView mamatee = offer(MAMATEE, 1000);

        TestActors.as(TestActors.seller(LAGOS));
        assertThatThrownBy(() -> coupons.create(platform("NOPE" + suffix(), CouponType.PERCENT, 10, 1)))
                .isInstanceOf(ForbiddenActionException.class);

        TestActors.as(TestActors.staff("coupons:platform:write"));
        String code = "WELCOME" + suffix();
        Coupon coupon = coupons.create(platform(code.toLowerCase(Locale.ROOT), CouponType.PERCENT, 10, 1));
        assertThat(coupon.code()).isEqualTo(code);
        assertThat(coupons.list(CouponFunding.PLATFORM, null, null)).extracting(Coupon::code).contains(code);

        TestActors.as(TestActors.customer("cust-ada"));
        CouponCartCommand cart = cart(code, null, null, lagos, mamatee);
        CouponQuote quote = coupons.validate(cart);
        assertThat(quote.applicable()).isTrue();
        assertThat(quote.eligibleSubtotalMinor()).isEqualTo(3500);
        assertThat(quote.discountMinor()).isEqualTo(350);
        assertThat(quote.funding()).isEqualTo(CouponFunding.PLATFORM);
        assertThat(coupons.validate(cart("NOSUCH1", null, null, lagos)).rejection())
                .isEqualTo(CouponRejection.NOT_FOUND);
        assertThatThrownBy(() -> coupons.redeem("k", cart(code, "cust-ada", "ord-1", lagos)))
                .isInstanceOf(ForbiddenActionException.class);

        TestActors.as(TestActors.ORDERS_SERVICE);
        String key = "ord-1:" + code;
        RedemptionResult first = coupons.redeem(key, cart(code, "cust-ada", "ord-1", lagos, mamatee));
        assertThat(first.replayed()).isFalse();
        assertThat(first.redemption().discountMinor()).isEqualTo(350);
        assertThat(first.redemption().lines()).extracting(l -> l.discountMinor()).containsExactly(250L, 100L);

        RedemptionResult replay = coupons.redeem(key, cart(code, "cust-ada", "ord-1", lagos, mamatee));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.redemption().id()).isEqualTo(first.redemption().id());
        assertThatThrownBy(() -> coupons.redeem(key, cart(code, "cust-ada", "ord-1", lagos)))
                .isInstanceOf(ConflictException.class);

        assertThatThrownBy(() -> coupons.redeem("ord-2:" + code, cart(code, "cust-ada", "ord-2", lagos)))
                .isInstanceOfSatisfying(CouponNotApplicable.class,
                        ex -> assertThat(ex.rejection()).isEqualTo(CouponRejection.CUSTOMER_LIMIT_REACHED));

        RedemptionResult released = coupons.release(first.redemption().id());
        assertThat(released.redemption().status()).isEqualTo(RedemptionStatus.RELEASED);
        assertThat(coupons.release(first.redemption().id()).replayed()).isTrue();
        assertThat(coupons.redeem("ord-2:" + code, cart(code, "cust-ada", "ord-2", lagos)).replayed()).isFalse();

        TestActors.reset();
        assertThat(coupons.get(coupon.id()).redeemedCount()).isEqualTo(1);
        assertThat(outboxTypes(coupon.id().toString())).containsExactlyInAnyOrder("offers.coupon.created",
                "offers.coupon.redeemed", "offers.coupon.released", "offers.coupon.redeemed");
    }

    @Test
    void sellerFundedClipCouponOnlyAppliesToTheSellersItemsAfterClipping() {
        OfferView lagos = offer(LAGOS, 2500);
        OfferView mamatee = offer(MAMATEE, 1000);

        TestActors.as(TestActors.seller(MAMATEE));
        String code = "MAMA" + suffix();
        Coupon coupon = coupons.create(new CreateCouponCommand(code, "Clip for $2 off", CouponType.FIXED, 200,
                "USD", null, CouponScope.SITEWIDE, null, 500, 0, 0, true, CouponFunding.SELLER, LAGOS, null, null));
        assertThat(coupon.fundedByOrg()).as("funded by the caller's own org").isEqualTo(MAMATEE);
        assertThat(coupons.clippable(mamatee.offer().atin(), "USD")).extracting(Coupon::code).contains(code);
        assertThat(coupons.clippable(lagos.offer().atin(), "USD")).extracting(Coupon::code).doesNotContain(code);

        TestActors.as(TestActors.seller(LAGOS));
        assertThatThrownBy(() -> coupons.changeStatus(coupon.id(), CouponStatus.PAUSED))
                .isInstanceOf(ForbiddenActionException.class);

        TestActors.as(TestActors.customer("cust-bola"));
        assertThat(coupons.validate(cart(code, null, null, lagos, mamatee)).rejection())
                .isEqualTo(CouponRejection.NOT_CLIPPED);
        coupons.clip(code.toLowerCase(Locale.ROOT));
        coupons.clip(code);
        CouponQuote quote = coupons.validate(cart(code, null, null, lagos, mamatee));
        assertThat(quote.applicable()).isTrue();
        assertThat(quote.eligibleSubtotalMinor()).isEqualTo(1000);
        assertThat(quote.discountMinor()).isEqualTo(200);
        assertThat(quote.lines()).singleElement().satisfies(l -> assertThat(l.sellerOrgId()).isEqualTo(MAMATEE));

        TestActors.as(TestActors.seller(MAMATEE));
        coupons.changeStatus(coupon.id(), CouponStatus.EXPIRED);
        assertThatThrownBy(() -> coupons.changeStatus(coupon.id(), CouponStatus.ACTIVE))
                .isInstanceOf(IllegalStateException.class);
        TestActors.as(TestActors.customer("cust-bola"));
        assertThat(coupons.validate(cart(code, null, null, mamatee)).rejection())
                .isEqualTo(CouponRejection.NOT_ACTIVE);
    }

    private OfferView offer(String org, long price) {
        String variant = atin();
        StubCatalog.live(atin(), variant, null, org);
        TestActors.as(TestActors.seller(org));
        OfferView v = offers.create(new CreateOfferCommand(variant, null, null, "SKU-" + variant, null,
                OfferCondition.NEW, price, null, "USD", 0, Fulfilment.SELLER, 10, 1,
                new ShipFrom("Atlanta", "GA", "US", "30301")));
        TestActors.reset();
        return v;
    }

    private static CreateCouponCommand platform(String code, CouponType type, long value, int perCustomer) {
        return new CreateCouponCommand(code, "Welcome", type, value, "USD", null, CouponScope.SITEWIDE, null, 1000,
                100, perCustomer, false, CouponFunding.PLATFORM, null, Instant.now().minusSeconds(60), null);
    }

    private static CouponCartCommand cart(String code, String customer, String order, OfferView... lines) {
        return new CouponCartCommand(code, customer, order, "USD", List.of(lines).stream()
                .map(v -> new CouponCartCommand.Line(v.offer().id(), 1)).toList(), 499, false);
    }

    private static String suffix() {
        return atin().substring(2, 8);
    }
}
