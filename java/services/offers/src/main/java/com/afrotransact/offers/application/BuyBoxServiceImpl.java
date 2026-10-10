package com.afrotransact.offers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.offers.application.BuyBoxEvaluator.Evaluated;
import com.afrotransact.offers.domain.BuyBoxDecision;
import com.afrotransact.offers.domain.BuyBoxReason;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferEvaluation;
import com.afrotransact.offers.port.BuyBoxRepository;
import com.afrotransact.offers.port.BuyBoxRepository.AuditEntry;
import com.afrotransact.offers.port.BuyBoxRepository.AuditedOffer;
import com.afrotransact.offers.port.BuyBoxRepository.CurrentBuyBox;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BuyBoxServiceImpl implements BuyBoxService {

    private final OfferRepository offers;
    private final BuyBoxRepository buyBoxes;
    private final BuyBoxEvaluator evaluator;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final Clock clock;

    BuyBoxServiceImpl(OfferRepository offers, BuyBoxRepository buyBoxes, BuyBoxEvaluator evaluator,
                      PermissionChecker permissions, DomainEventPublisher events, Clock clock) {
        this.offers = offers;
        this.buyBoxes = buyBoxes;
        this.evaluator = evaluator;
        this.permissions = permissions;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void recompute(Collection<String> atins, String trigger) {
        for (String atin : new TreeSet<>(atins)) {
            buyBoxes.lock(atin);
            List<OfferView> views = evaluator.views(offers.findOpenByAtins(List.of(atin)));
            Set<String> currencies = new TreeSet<>(buyBoxes.currencies(atin));
            views.forEach(v -> currencies.add(v.offer().currency()));
            for (String currency : currencies) {
                apply(evaluator.decide(atin, currency, views), trigger);
            }
        }
    }

    private void apply(Evaluated e, String trigger) {
        Instant now = Instant.now(clock);
        BuyBoxDecision d = e.decision();
        Optional<CurrentBuyBox> previous = buyBoxes.find(e.atin(), e.currency());
        Optional<OfferEvaluation> winner = d.winner();
        List<OfferEvaluation> listed = d.listed();
        Long minLanded = listed.stream().map(x -> x.candidate().landedPriceMinor()).min(Long::compare).orElse(null);
        Long minPrice = listed.stream().map(x -> x.candidate().priceMinor()).min(Long::compare).orElse(null);
        CurrentBuyBox current = new CurrentBuyBox(e.atin(), e.currency(), d.winnerOfferId(),
                winner.map(w -> w.candidate().sellerOrgId()).orElse(null),
                winner.map(w -> w.candidate().priceMinor()).orElse(null),
                winner.map(w -> w.candidate().landedPriceMinor()).orElse(null), listed.size(), minLanded,
                winner.map(OfferEvaluation::score).orElse(0.0), d.reasons(), now);
        buyBoxes.save(current);

        UUID previousWinner = previous.map(CurrentBuyBox::winnerOfferId).orElse(null);
        boolean winnerChanged = !Objects.equals(previousWinner, d.winnerOfferId());
        if (winnerChanged && (previous.isPresent() || d.winnerOfferId() != null)) {
            buyBoxes.audit(new AuditEntry(UUID.randomUUID(), e.atin(), e.currency(), previousWinner,
                    d.winnerOfferId(), trigger, d.evaluations().stream().map(BuyBoxServiceImpl::audited).toList(),
                    d.reasons(), now));
        }
        boolean headlineChanged = previous.map(p -> !sameHeadline(p, current)).orElse(d.winnerOfferId() != null
                || !listed.isEmpty());
        if (headlineChanged) {
            Offer w = winner.map(x -> e.views().get(x.candidate().offerId()).offer()).orElse(null);
            String productAtin = w != null ? w.productAtin()
                    : e.views().values().stream().map(v -> v.offer().productAtin()).findFirst().orElse(null);
            events.publish(OffersEvents.BUYBOX_CHANGED, OffersEvents.AGGREGATE, e.atin(), new BuyBoxChangedPayload(
                    e.atin(), productAtin, e.currency(), previousWinner, d.winnerOfferId(),
                    w == null ? null : w.sellerOrgId(), w == null ? null : w.sellerKind().name(),
                    w == null ? null : w.priceMinor(), w == null ? null : w.listPriceMinor(),
                    w == null ? null : w.shippingMinor(), w == null ? null : w.landedPriceMinor(),
                    w == null ? null : w.fulfilment().name(),
                    winner.map(x -> x.candidate().availableQty()).orElse(null), listed.size(), minLanded, minPrice,
                    d.reasons().stream().map(BuyBoxReason::code).toList(), trigger, now));
        }
    }

    private static boolean sameHeadline(CurrentBuyBox a, CurrentBuyBox b) {
        return Objects.equals(a.winnerOfferId(), b.winnerOfferId())
                && Objects.equals(a.winnerPriceMinor(), b.winnerPriceMinor())
                && Objects.equals(a.winnerLandedPriceMinor(), b.winnerLandedPriceMinor())
                && a.offerCount() == b.offerCount()
                && Objects.equals(a.minLandedPriceMinor(), b.minLandedPriceMinor());
    }

    private static AuditedOffer audited(OfferEvaluation e) {
        return new AuditedOffer(e.candidate().offerId(), e.candidate().sellerOrgId(), e.rank(), e.score(),
                e.buyBoxEligible(), e.listable(), e.ineligibility(), e.candidate().landedPriceMinor(),
                e.candidate().availableQty(), e.components());
    }

    @Override
    @Transactional(readOnly = true)
    public BuyBoxExplanation explain(String atin, String currency) {
        Actor actor = permissions.require(OffersPermissions.READ);
        String a = atin.toUpperCase(Locale.ROOT);
        String c = currency.toUpperCase(Locale.ROOT);
        Evaluated e = evaluator.decide(a, c, evaluator.views(offers.findOpenByAtins(List.of(a))));
        List<OfferEvaluation> visible = e.decision().evaluations().stream()
                .filter(ev -> Actors.isStaff(actor) || ev.rank() == 1
                        || actor.orgId().map(ev.candidate().sellerOrgId()::equals).orElse(false))
                .toList();
        return new BuyBoxExplanation(a, c, e.decision().winnerOfferId(), e.decision().reasons(), visible);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntry> history(String atin, int limit) {
        Actor actor = permissions.require(OffersPermissions.READ);
        if (!Actors.isStaff(actor)) {
            throw new ForbiddenActionException("offers.staff-only", "Buy box history is available to staff only");
        }
        return buyBoxes.history(atin.toUpperCase(Locale.ROOT), Math.clamp(limit, 1, 200));
    }
}
