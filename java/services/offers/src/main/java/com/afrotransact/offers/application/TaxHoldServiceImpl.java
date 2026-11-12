package com.afrotransact.offers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.afrotransact.offers.application.OfferTaxGate.Verdict;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.offers.port.OfferTaxMetrics;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxHoldServiceImpl implements TaxHoldService {

    private static final Logger LOG = LoggerFactory.getLogger(TaxHoldServiceImpl.class);
    private static final String TRIGGER = "tax-release";

    private final OfferRepository offers;
    private final OfferTaxGate gate;
    private final BuyBoxService buyBox;
    private final DomainEventPublisher events;
    private final OfferSettingsService settings;
    private final OfferTaxMetrics metrics;
    private final Clock clock;

    TaxHoldServiceImpl(OfferRepository offers, OfferTaxGate gate, BuyBoxService buyBox, DomainEventPublisher events,
                       OfferSettingsService settings, OfferTaxMetrics metrics, Clock clock) {
        this.offers = offers;
        this.gate = gate;
        this.buyBox = buyBox;
        this.events = events;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int releaseFor(String atin) {
        int released = 0;
        for (Offer held : offers.findTaxHeld(atin, settings.current().taxSweepBatchSize())) {
            if (release(held)) {
                released++;
            }
        }
        return released;
    }

    @Override
    @Transactional
    public int sweep() {
        int batch = settings.current().taxSweepBatchSize();
        int changed = 0;
        for (Offer held : offers.findTaxHeld(null, batch)) {
            if (release(held)) {
                changed++;
            }
        }
        for (Offer unverified : offers.findActiveWithoutTaxBasis(batch)) {
            if (verify(unverified)) {
                changed++;
            }
        }
        return changed;
    }

    /** A held offer whose seller has a RESOLVED decision now is released; otherwise it keeps waiting. */
    private boolean release(Offer held) {
        Verdict verdict = gate.check(held.atin(), held.sellerOrgId(), held.sellerBehaviour());
        if (!verdict.resolved()) {
            return false;
        }
        Instant now = Instant.now(clock);
        try {
            Offer saved = offers.update(held.releasedFromTaxHold(Offer.TAX_BASIS_SELLER_DECISION, now));
            events.publish(OffersEvents.OFFER_TAX_RELEASED, OffersEvents.AGGREGATE, saved.atin(),
                    OfferEventPayload.of(saved));
            events.publish(OffersEvents.OFFER_UPDATED, OffersEvents.AGGREGATE, saved.atin(), OfferEventPayload.of(saved));
            buyBox.recompute(List.of(saved.atin()), TRIGGER);
            metrics.released();
            return true;
        } catch (ConflictException | IllegalStateException ex) {
            // Changed or withdrawn meanwhile: whoever changed it decided; the next run looks again.
            LOG.debug("Offer {} was not released: {}", held.id(), ex.getMessage());
            return false;
        }
    }

    /**
     * An offer that was live before the check existed: stamped when the seller has a RESOLVED decision, held when it has
     * none. While tax-returns cannot be asked it is left as it is (the gauge keeps showing it) instead of taking every
     * live offer off sale for an outage.
     */
    private boolean verify(Offer unverified) {
        Verdict verdict = gate.check(unverified.atin(), unverified.sellerOrgId(), unverified.sellerBehaviour());
        Instant now = Instant.now(clock);
        try {
            if (verdict.resolved()) {
                offers.update(unverified.withTaxBasis(Offer.TAX_BASIS_SELLER_DECISION, now));
                return true;
            }
            if (Offer.HOLD_UNAVAILABLE.equals(verdict.holdReason())) {
                return false;
            }
            Offer saved = offers.update(unverified.heldForTax(verdict.holdReason(), now));
            events.publish(OffersEvents.OFFER_TAX_HELD, OffersEvents.AGGREGATE, saved.atin(), OfferEventPayload.of(saved));
            events.publish(OffersEvents.OFFER_UPDATED, OffersEvents.AGGREGATE, saved.atin(), OfferEventPayload.of(saved));
            buyBox.recompute(List.of(saved.atin()), TRIGGER);
            metrics.held(verdict.holdReason());
            return true;
        } catch (ConflictException | IllegalStateException ex) {
            LOG.debug("Offer {} was not checked: {}", unverified.id(), ex.getMessage());
            return false;
        }
    }
}
