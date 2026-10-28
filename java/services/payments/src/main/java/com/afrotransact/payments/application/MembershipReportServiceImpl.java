package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.payments.domain.BenefitCode;
import com.afrotransact.payments.domain.MembershipStatus;
import com.afrotransact.payments.domain.MembershipTier;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.port.MembershipReportRepository;
import com.afrotransact.payments.port.MembershipReportRepository.BenefitCost;
import com.afrotransact.payments.port.MembershipReportRepository.TierFlow;
import com.afrotransact.payments.port.MembershipReportRepository.TierRevenue;
import com.afrotransact.payments.port.MembershipReportRepository.TierStart;
import com.afrotransact.payments.port.MembershipRepository;
import com.afrotransact.payments.port.MembershipRepository.TierCount;
import com.afrotransact.payments.port.MembershipTierRepository;
import org.springframework.stereotype.Service;

@Service
class MembershipReportServiceImpl implements MembershipReportService {

    private final MembershipRepository memberships;
    private final MembershipTierRepository tiers;
    private final MembershipReportRepository reports;
    private final PaymentsAccess access;
    private final PaymentsSettings settings;

    MembershipReportServiceImpl(MembershipRepository memberships, MembershipTierRepository tiers,
                                MembershipReportRepository reports, PaymentsAccess access,
                                PaymentsSettings settings) {
        this.memberships = memberships;
        this.tiers = tiers;
        this.reports = reports;
        this.access = access;
        this.settings = settings;
    }

    @Override
    public Report report(Instant from, Instant to) {
        access.reader();
        if (from == null || to == null || !to.isAfter(from)) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Choose a period that ends after it starts",
                    Map.of("to", "After from"));
        }
        Map<String, TierFlow> flows = new HashMap<>();
        reports.flowsByTier(from, to).forEach(f -> flows.put(f.tierCode(), f));
        Map<String, Long> starts = new HashMap<>();
        reports.liveAtStart(from).forEach((TierStart s) -> starts.put(s.tierCode(), s.members()));
        Map<String, TierRevenue> revenue = new HashMap<>();
        reports.revenue(from, to).forEach(r -> revenue.put(r.tierCode(), r));
        Map<String, long[]> live = new HashMap<>();
        for (TierCount c : memberships.countsByTier()) {
            // members, trialing, active, pastDue, cancelling, mrr
            long[] n = live.computeIfAbsent(c.tierCode(), k -> new long[6]);
            n[0] += c.count();
            switch (c.status()) {
                case TRIALING -> n[1] += c.count();
                case ACTIVE -> n[2] += c.count();
                case PAST_DUE -> n[3] += c.count();
                default -> { }
            }
            if (c.cancelling()) {
                n[4] += c.count();
            }
            if (c.status() == MembershipStatus.ACTIVE || c.status() == MembershipStatus.PAST_DUE) {
                n[5] += c.monthlyEquivalentMinor();
            }
        }
        List<TierFigures> figures = new ArrayList<>();
        long members = 0;
        long trialing = 0;
        long mrr = 0;
        long charged = 0;
        long refundedMinor = 0;
        long atStart = 0;
        long joined = 0;
        long canceled = 0;
        long lapsed = 0;
        long refunded = 0;
        for (MembershipTier tier : tiers.all()) {
            long[] n = live.getOrDefault(tier.code(), new long[6]);
            TierFlow f = flows.getOrDefault(tier.code(), new TierFlow(tier.code(), 0, 0, 0, 0, 0));
            TierRevenue r = revenue.getOrDefault(tier.code(), new TierRevenue(tier.code(), 0, 0, 0));
            long start = starts.getOrDefault(tier.code(), 0L);
            figures.add(new TierFigures(tier.code(), tier.name(), tier.status().name(), n[0], n[1], n[2], n[3], n[4],
                    n[5], f.joined(), f.canceled(), f.lapsed(), f.refunded(), start,
                    churn(f.canceled() + f.lapsed() + f.refunded(), start), r.chargedMinor(), r.refundedMinor()));
            members += n[0];
            trialing += n[1];
            mrr += n[5];
            charged += r.chargedMinor();
            refundedMinor += r.refundedMinor();
            atStart += start;
            joined += f.joined();
            canceled += f.canceled();
            lapsed += f.lapsed();
            refunded += f.refunded();
        }
        List<BenefitCostLine> costs = new ArrayList<>();
        long waived = 0;
        long granted = 0;
        for (BenefitCost c : reports.benefitCosts(from, to)) {
            BenefitCode code = BenefitCode.valueOf(c.benefit());
            costs.add(new BenefitCostLine(code, c.uses(), c.amountMinor()));
            if (code == BenefitCode.FREE_DELIVERY || code == BenefitCode.FREE_SHIPPING) {
                waived += c.amountMinor();
            } else {
                granted += c.amountMinor();
            }
        }
        long ended = canceled + lapsed + refunded;
        return new Report(from, to, settings.currency(), members, trialing, mrr, mrr * 12, charged, refundedMinor,
                charged - refundedMinor, waived + granted, granted, waived, atStart, joined, ended, lapsed, canceled,
                refunded, churn(ended, atStart), figures, costs);
    }

    private static int churn(long ended, long atStart) {
        return atStart <= 0 ? 0 : (int) Math.min(10_000, ended * 10_000 / atStart);
    }
}
