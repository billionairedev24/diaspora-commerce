package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.BillingGrant;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.ChargeGate;
import com.afrotransact.seller.domain.DunningState;
import com.afrotransact.seller.domain.GrantSource;
import com.afrotransact.seller.domain.GrantStatus;
import com.afrotransact.seller.domain.GrantType;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoiceLine;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.domain.SubscriptionStatus;
import com.afrotransact.seller.domain.UsageDay;
import com.afrotransact.seller.domain.UsageMeter;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.InvoiceRepository;
import com.afrotransact.seller.port.PlanRepository;
import com.afrotransact.seller.port.UsageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The plan invoice of one RRULE period: plan fee, usage, waiver by a charge gate, stacked coupons. */
class InvoiceIssuingServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final Instant START = Instant.parse("2026-10-16T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-16T00:00:00Z");

    private final PlanRepository plans = mock(PlanRepository.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final BillingGrantRepository grants = mock(BillingGrantRepository.class);
    private final UsageRepository usage = mock(UsageRepository.class);
    private final ChargeGateEvaluator gates = mock(ChargeGateEvaluator.class);
    private final InvoiceIssuingServiceImpl service = new InvoiceIssuingServiceImpl(plans, invoices, grants, usage,
            gates, Clock.fixed(NOW, ZoneOffset.UTC));
    private final Plan growth = Plan.standard("seller_growth", OrgKind.SELLER, "Growth", null, 4900, 30, List.of(), 2);

    InvoiceIssuingServiceImplTest() {
        when(plans.find("seller_growth", 1)).thenReturn(Optional.of(growth));
        when(invoices.insert(any())).thenReturn(true);
        when(invoices.nextNumber()).thenReturn(1001L);
        when(invoices.findById(any())).thenReturn(Optional.empty());
        when(gates.decide("org_1", NOW)).thenReturn(Optional.empty());
    }

    @Test
    void thePlanFeeBecomesAnOpenInvoiceForThePeriod() {
        Invoice issued = issue();
        assertThat(issued.status()).isEqualTo(Invoice.OPEN);
        assertThat(issued.number()).isEqualTo("AT-INV-2026-1001");
        assertThat(issued.amountDueCents()).isEqualTo(4900);
        assertThat(issued.lines()).singleElement().satisfies(l -> {
            assertThat(l.kind()).isEqualTo(InvoiceLine.PLAN);
            assertThat(l.description()).contains("Growth plan").contains("monthly").contains("Oct 16, 2026")
                    .contains("Nov 15, 2026");
        });
        assertThat(issued.planVersion()).isEqualTo(1);
        assertThat(issued.dueAt()).isEqualTo(END);
    }

    @Test
    void aQuarterlyPeriodUsesTheQuarterlyPrice() {
        Subscription quarterly = sub(Cadence.QUARTERLY);
        service.issue(quarterly, START, Instant.parse("2027-01-16T00:00:00Z"), Invoice.REASON_CYCLE);
        assertThat(captured().amountDueCents()).isEqualTo(Plan.standardPrice(4900, Cadence.QUARTERLY));
    }

    @Test
    void aFreePlanWithoutUsageIssuesNothing() {
        Plan free = Plan.standard("seller_free", OrgKind.SELLER, "Free", null, 0, 0, List.of(), 1);
        when(plans.find("seller_free", 1)).thenReturn(Optional.of(free));
        Subscription s = new Subscription("org_1", "seller_free", 1, Cadence.MONTHLY, SubscriptionStatus.ACTIVE, null,
                START, null, START, END, false, null, null, null, DunningState.NONE, 0, null, NOW, 0);
        assertThat(service.issue(s, START, END, Invoice.REASON_CYCLE)).isEmpty();
        verify(invoices, never()).insert(any());
    }

    @Test
    void anAlreadyInvoicedPeriodIsNotInvoicedTwice() {
        when(invoices.findForPeriod("org_1", START)).thenReturn(Optional.of(mock(Invoice.class)));
        assertThat(service.issue(sub(Cadence.MONTHLY), START, END, Invoice.REASON_CYCLE)).isEmpty();
        verify(invoices, never()).insert(any());
    }

    @Test
    void aGateThatHoldsChargesWaivesThePlanFeeAndSaysWhy() {
        ChargeGate.Decision held = new ChargeGate.Decision(false, "Charges start once you reach $1,000 in sales "
                + "(you're at $420)", false, false);
        when(gates.decide("org_1", NOW)).thenReturn(Optional.of(held));
        Invoice issued = issue();
        assertThat(issued.status()).as("nothing to collect").isEqualTo(Invoice.PAID);
        assertThat(issued.amountDueCents()).isZero();
        assertThat(issued.discountCents()).isEqualTo(4900);
        assertThat(issued.lines()).extracting(InvoiceLine::kind).containsExactly(InvoiceLine.PLAN, InvoiceLine.WAIVER);
        assertThat(issued.lines().get(1).description()).contains("$1,000 in sales");
        verify(gates).afterIssue("org_1", held, NOW);
    }

    @Test
    void couponsStackOnThePlanFeeAndEachUsesUpOneInvoice() {
        BillingGrant ten = coupon("10% launch offer", "10");
        BillingGrant twentyFive = coupon("Partner offer", "25");
        when(grants.activeCoupons("org_1")).thenReturn(List.of(ten, twentyFive));
        Invoice issued = issue();
        assertThat(issued.discountCents()).isEqualTo(490 + 1225);
        assertThat(issued.amountDueCents()).isEqualTo(4900 - 490 - 1225);
        assertThat(issued.lines()).extracting(InvoiceLine::kind).containsExactly(InvoiceLine.PLAN, InvoiceLine.COUPON,
                InvoiceLine.COUPON);
        verify(grants).useCouponOnce(ten.id());
        verify(grants).useCouponOnce(twentyFive.id());
    }

    @Test
    void couponsNeverTakeMoreThanThePlanFee() {
        when(grants.activeCoupons("org_1")).thenReturn(List.of(coupon("Free", "100"), coupon("Extra", "50")));
        Invoice issued = issue();
        assertThat(issued.amountDueCents()).isZero();
        assertThat(issued.status()).isEqualTo(Invoice.PAID);
        assertThat(issued.discountCents()).isEqualTo(4900);
    }

    @Test
    void unbilledUsageIsBilledOnceWithThePlan() {
        UsageDay ads = new UsageDay("org_1", UsageMeter.ADS_SPEND, LocalDate.parse("2026-10-10"), 1250, 0, 0, null,
                null, NOW);
        when(usage.unbilled("org_1", LocalDate.parse("2026-10-14"))).thenReturn(List.of(ads));
        when(usage.lock("org_1", UsageMeter.ADS_SPEND, LocalDate.parse("2026-10-10"))).thenReturn(Optional.of(ads));
        Invoice issued = issue();
        assertThat(issued.meteredLines()).singleElement().satisfies(l -> {
            assertThat(l.amountCents()).isEqualTo(UsageMeter.ADS_SPEND.estimatedCents(1250));
            assertThat(l.meter()).isEqualTo(UsageMeter.ADS_SPEND);
        });
        assertThat(issued.amountDueCents()).isEqualTo(4900 + UsageMeter.ADS_SPEND.estimatedCents(1250));
        verify(usage).markReported(any(), eq(1250L), eq(1), any(), eq(1250L), eq(NOW));
    }

    private Invoice issue() {
        service.issue(sub(Cadence.MONTHLY), START, END, Invoice.REASON_CYCLE);
        return captured();
    }

    private Invoice captured() {
        ArgumentCaptor<Invoice> c = ArgumentCaptor.forClass(Invoice.class);
        verify(invoices).insert(c.capture());
        return c.getValue();
    }

    private static Subscription sub(Cadence cadence) {
        return new Subscription("org_1", "seller_growth", 1, cadence, SubscriptionStatus.ACTIVE, null, START, null,
                START, END, false, null, null, null, DunningState.NONE, 0, null, NOW, 0);
    }

    private static BillingGrant coupon(String summary, String percent) {
        return new BillingGrant(UUID.randomUUID(), "org_1", GrantSource.RULE, UUID.randomUUID(), null,
                GrantType.PERCENT_OFF, null, null, null, new BigDecimal(percent), 3, 3, null, null, null,
                GrantStatus.APPLIED, null, null, summary, "system", null, null, BillingGrant.Effect.NONE, 1, null, NOW,
                NOW, 0);
    }
}
